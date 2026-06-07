package com.example.streaming

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.createBucket
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.net.url.Url
import com.amazonaws.services.lambda.runtime.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * End-to-end LocalStack integration test for the sub-6 MB streaming path (Req 5.6).
 *
 * A single LocalStack S3 container is shared across the whole class — started in
 * [BeforeAll] and stopped in [AfterAll] — with `Wait.forHttp("/_localstack/health")
 * .forStatusCode(200)` as the readiness gate (per `tech.md`). Only object data is
 * cleaned between tests ([AfterEach]); the container itself is never restarted.
 *
 * The test uploads a sub-6 MB object through the real Kotlin AWS SDK
 * ([aws.sdk.kotlin.services.s3.S3Client]) pointed at the container (path-style addressing,
 * static credentials), then drives the full [StreamHandler] from an API Gateway proxy event
 * carrying the file name. The handler runs parse -> validate -> head -> stream against the
 * containerized S3, and the streamed response body is asserted byte-identical to the bytes
 * uploaded — proving the protocol writer, bounded-buffer copy, and S3 source work together
 * end to end against a real S3 implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3StreamingSub6MbIntegrationTest {

    private val bucket = "streaming-test-bucket"

    private val localstack: LocalStackContainer =
        LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
            .withServices(LocalStackContainer.Service.S3)
            .waitingFor(Wait.forHttp("/_localstack/health").forStatusCode(200))

    private lateinit var s3: S3Client

    @BeforeAll
    fun startContainer() {
        runBlocking {
            localstack.start()
            s3 = buildClient()
            s3.createBucket { bucket = this@S3StreamingSub6MbIntegrationTest.bucket }
        }
    }

    @AfterAll
    fun stopContainer() {
        if (::s3.isInitialized) s3.close()
        localstack.stop()
    }

    /** Clean only object data between tests; the shared container keeps running (per `tech.md`). */
    @AfterEach
    fun cleanObjects() {
        runBlocking {
            val listed = s3.listObjectsV2 { bucket = this@S3StreamingSub6MbIntegrationTest.bucket }
            listed.contents?.forEach { obj ->
                obj.key?.let { objectKey ->
                    s3.deleteObject {
                        bucket = this@S3StreamingSub6MbIntegrationTest.bucket
                        key = objectKey
                    }
                }
            }
        }
    }

    /** Builds an [S3Client] aimed at the shared LocalStack container (path-style, static creds). */
    private fun buildClient(): S3Client = S3Client {
        region = localstack.region
        endpointUrl = Url.parse(localstack.endpoint.toString())
        forcePathStyle = true
        credentialsProvider = StaticCredentialsProvider {
            accessKeyId = localstack.accessKey
            secretAccessKey = localstack.secretKey
        }
    }

    @Test
    fun `Given a sub-6 MB S3 object When streamed end-to-end through the handler Then the body is byte-identical`() = runBlocking {
        val key = "sub6.bin"
        // 5 MB of random bytes: comfortably under the 6 MB legacy buffered limit, spanning
        // several 1 MB bounded-buffer chunks plus a partial final chunk.
        val payload = Random(42).nextBytes(5 * 1024 * 1024)
        s3.putObject {
            bucket = this@S3StreamingSub6MbIntegrationTest.bucket
            this.key = key
            body = ByteStream.fromBytes(payload)
        }

        val handler = StreamHandler(s3Source = { S3Source(bucket = bucket, client = s3) })
        val event = """{"pathParameters":{"proxy":"$key"}}"""
        val output = ByteArrayOutputStream()

        handler.handleRequest(event.byteInputStream(), output, mockk<Context>(relaxed = true))

        val responseBytes = output.toByteArray()
        val delimiterStart = indexOfDelimiter(responseBytes)
        assertTrue(delimiterStart >= 0, "the 8 null-byte metadata/body delimiter must be present")

        // Segment 1: metadata JSON -> status 200 with the declared content length.
        val metadataJson = responseBytes.copyOfRange(0, delimiterStart).decodeToString()
        val metadata = Json.decodeFromString<ResponseMetadata>(metadataJson)
        assertEquals(200, metadata.statusCode)
        assertEquals(listOf(payload.size.toString()), metadata.headers["Content-Length"])

        // Segment 3: body bytes after the 8 null-byte delimiter -> byte-identical to the upload.
        val body = responseBytes.copyOfRange(delimiterStart + DELIMITER_LEN, responseBytes.size)
        assertEquals(payload.size, body.size, "received body length must equal the uploaded size")
        assertArrayEquals(payload, body, "streamed body must be byte-identical to the uploaded object")
    }

    /**
     * Returns the start index of the first run of [DELIMITER_LEN] consecutive zero bytes
     * (the metadata/body delimiter), or -1 if absent. The metadata JSON never contains a
     * NUL byte, so the first such run reliably separates metadata from body.
     */
    private fun indexOfDelimiter(bytes: ByteArray): Int {
        var run = 0
        for (i in bytes.indices) {
            if (bytes[i].toInt() == 0) {
                run++
                if (run == DELIMITER_LEN) return i - DELIMITER_LEN + 1
            } else {
                run = 0
            }
        }
        return -1
    }
}
