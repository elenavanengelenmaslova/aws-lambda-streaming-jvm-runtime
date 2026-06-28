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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * LocalStack S3 integration test proving the **~15 MB `Test_Object`** streaming path end to
 * end — delivery of a payload well past the legacy 6 MB buffered Lambda limit (Req 6.1, 6.2).
 *
 * Mirrors [SubSixMbStreamingIntegrationTest]: a single LocalStack container (S3 only) is
 * shared across the whole class via [BeforeAll]/[AfterAll]; readiness is gated on the
 * LocalStack health endpoint (`/_localstack/health` returning 200) per `tech.md`. Only object
 * data is cleaned between tests (the bucket is kept, objects deleted in [cleanObjects]).
 *
 * The test uploads a ~15 MB object through the real Kotlin AWS SDK [S3Client] pointed at the
 * container, then drives the production [StreamHandler] with a synthetic API Gateway
 * `/{proxy+}` event. From the protocol response it then:
 *  - decodes the metadata JSON written before the [DELIMITER_LEN] null-byte delimiter and
 *    asserts the committed status is `200` (Req 6.1);
 *  - asserts the received body byte count equals the stored object size (~15 MB, > 6 MB);
 *  - asserts the body is byte-identical to the uploaded object (Req 6.2).
 *
 * ## Container runtime
 * This project runs on Colima, not Docker Desktop (see `tech.md`). TestContainers connects
 * via the Colima Docker socket (`DOCKER_HOST` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`).
 * The test is tagged `integration`; it is not skip-annotated, so it runs whenever a container
 * runtime is reachable.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FifteenMbStreamingIntegrationTest {

    private val context: Context = mockk(relaxed = true)

    @BeforeAll
    fun startContainer() {
        localstack = LocalStackContainer(LOCALSTACK_IMAGE)
            .withServices(LocalStackContainer.Service.S3)
            .waitingFor(Wait.forHttp("/_localstack/health").forStatusCode(200))
        localstack.start()

        val endpoint = localstack.getEndpointOverride(LocalStackContainer.Service.S3)
        s3 = S3Client {
            region = localstack.region
            endpointUrl = Url.parse(endpoint.toString())
            // LocalStack uses path-style addressing (bucket in the path, not the host).
            forcePathStyle = true
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = localstack.accessKey
                secretAccessKey = localstack.secretKey
            }
        }

        runBlocking { s3.createBucket { bucket = BUCKET } }
    }

    @AfterAll
    fun stopContainer() {
        s3.close()
        localstack.stop()
    }

    /** Clean only object data between tests — the shared bucket and container are kept. */
    @AfterEach
    fun cleanObjects() {
        runBlocking {
            val listed = s3.listObjectsV2 { bucket = BUCKET }
            listed.contents?.forEach { obj ->
                obj.key?.let { key -> s3.deleteObject { bucket = BUCKET; this.key = key } }
            }
        }
    }

    @Test
    fun `Given a ~15 MB S3 object When streamed through the handler Then status is 200 and the received body is byte-identical past the 6 MB limit`() {
        // ~15 MB of pseudo-random bytes: well over the legacy 6 MB buffered limit and many
        // multiples of the 1 MB transfer buffer, so the bounded-buffer copy loops repeatedly.
        val key = "Test_Object"
        val payload = Random(15).nextBytes(15 * 1024 * 1024)
        assertTrue(payload.size > 6 * 1024 * 1024, "fixture must exceed the legacy 6 MB limit")
        runBlocking {
            s3.putObject {
                bucket = BUCKET
                this.key = key
                body = ByteStream.fromBytes(payload)
            }
        }

        // Drive the real handler against the live S3 source, injected via the factory lambda.
        val source = S3Source(bucket = BUCKET, client = s3)
        val handler = StreamHandler(source = { source })

        val output = ByteArrayOutputStream()
        handler.handleRequest(
            ByteArrayInputStream(proxyEvent(key).toByteArray(Charsets.UTF_8)),
            output,
            context,
        )

        val response = output.toByteArray()

        // Status is committed in the metadata JSON segment before the 8 null-byte delimiter.
        val metadata = extractMetadata(response)
        assertEquals(200, metadata.statusCode, "committed status must be 200 for the ~15 MB object")

        val received = extractBody(response)
        assertEquals(
            payload.size,
            received.size,
            "received body byte count must equal the stored object size (> 6 MB)",
        )
        assertArrayEquals(payload, received, "received body must be byte-identical to the uploaded object")
    }

    /** A minimal API Gateway `/{proxy+}` event carrying the requested file name. */
    private fun proxyEvent(fileName: String): String =
        """{"pathParameters":{"proxy":"$fileName"}}"""

    /** Index of the first run of [DELIMITER_LEN] consecutive zero bytes, or -1 if absent. */
    private fun delimiterIndex(response: ByteArray): Int {
        var i = 0
        while (i <= response.size - DELIMITER_LEN) {
            var allZero = true
            for (j in 0 until DELIMITER_LEN) {
                if (response[i + j].toInt() != 0) {
                    allZero = false
                    break
                }
            }
            if (allZero) return i
            i++
        }
        return -1
    }

    /**
     * Decodes the metadata JSON written as segment 1 of the protocol. The metadata JSON
     * contains no NUL bytes by construction, so everything before the first run of
     * [DELIMITER_LEN] zero bytes is the metadata document.
     */
    private fun extractMetadata(response: ByteArray): ResponseMetadata {
        val end = delimiterIndex(response)
        assertTrue(end >= 0, "protocol delimiter (8 null bytes) not found in the response")
        val jsonText = String(response.copyOfRange(0, end), Charsets.UTF_8)
        return Json.decodeFromString(ResponseMetadata.serializer(), jsonText)
    }

    /**
     * Extracts the streamed body: everything after the first run of [DELIMITER_LEN] zero bytes
     * that marks the metadata/body boundary.
     */
    private fun extractBody(response: ByteArray): ByteArray {
        val start = delimiterIndex(response)
        assertTrue(start >= 0, "protocol delimiter (8 null bytes) not found in the response")
        return response.copyOfRange(start + DELIMITER_LEN, response.size)
    }

    companion object {
        private const val BUCKET = "streaming-test-bucket"

        // Pinned LocalStack image; S3 only. TestContainers manages it via the Colima socket.
        private val LOCALSTACK_IMAGE: DockerImageName = DockerImageName.parse("localstack/localstack:3.8.1")

        private lateinit var localstack: LocalStackContainer
        private lateinit var s3: S3Client
    }
}
