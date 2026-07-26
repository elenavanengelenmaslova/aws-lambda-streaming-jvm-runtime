package nl.vintik.lambda.streaming

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
 * LocalStack S3 integration test proving the sub-6 MB streaming path end to end (Req 5.6).
 *
 * A single LocalStack container (S3 only) is shared across the whole class via
 * [BeforeAll]/[AfterAll]; readiness is gated on the LocalStack health endpoint
 * (`/_localstack/health` returning 200) per `tech.md`. Only object data is cleaned
 * between tests (the bucket is kept, objects are deleted in [cleanObjects]).
 *
 * The test uploads a sub-6 MB object through the real Kotlin AWS SDK [S3Client] pointed
 * at the container, then drives the production [StreamHandler] with a synthetic API
 * Gateway proxy event. The handler runs the full pipeline — parse -> validate -> head ->
 * write metadata + 8 null-byte delimiter + body — against the live S3 endpoint. The
 * received body bytes (everything after the protocol delimiter) are asserted
 * byte-identical to the uploaded object (Req 5.6).
 *
 * ## Container runtime
 * This project runs on Colima, not Docker Desktop (see `tech.md`). TestContainers connects
 * via the Colima Docker socket (`DOCKER_HOST` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`).
 * The test is tagged `integration` so it can be included/excluded by tag; it is not
 * skip-annotated, so it runs whenever a container runtime is reachable.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubSixMbStreamingIntegrationTest {

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
    fun `Given a sub-6 MB S3 object When streamed through the handler Then the received body is byte-identical`() {
        // 5 MB of pseudo-random bytes: comfortably under the legacy 6 MB buffered limit,
        // larger than the 1 MB transfer buffer so multiple chunks are exercised.
        val key = "sub-six.bin"
        val payload = Random(42).nextBytes(5 * 1024 * 1024)
        runBlocking {
            s3.putObject {
                bucket = BUCKET
                this.key = key
                body = ByteStream.fromBytes(payload)
            }
        }

        // Drive the real handler against the live S3 source, injected via the factory lambda.
        val source = S3Source(bucket = BUCKET, client = s3)
        val handler = StreamHandler(requestResolver = ::FileKeyResolver, source = { source })

        val output = ByteArrayOutputStream()
        handler.handleRequest(
            ByteArrayInputStream(proxyEvent(key).toByteArray(Charsets.UTF_8)),
            output,
            context,
        )

        val received = extractBody(output.toByteArray())
        assertEquals(payload.size, received.size, "received body length must equal the uploaded object size")
        assertArrayEquals(payload, received, "received body must be byte-identical to the uploaded object")
    }

    /** A minimal API Gateway `/{proxy+}` event carrying the requested file name. */
    private fun proxyEvent(fileName: String): String =
        """{"pathParameters":{"proxy":"$fileName"}}"""

    /**
     * Extracts the streamed body from a protocol response: metadata JSON, then the
     * [DELIMITER_LEN]-byte run of zero bytes, then the body. The metadata JSON contains no
     * NUL bytes by construction, so the first run of [DELIMITER_LEN] consecutive zero bytes
     * marks the metadata/body boundary; everything after it is the body.
     */
    private fun extractBody(response: ByteArray): ByteArray {
        var i = 0
        while (i <= response.size - DELIMITER_LEN) {
            var allZero = true
            for (j in 0 until DELIMITER_LEN) {
                if (response[i + j].toInt() != 0) {
                    allZero = false
                    break
                }
            }
            if (allZero) return response.copyOfRange(i + DELIMITER_LEN, response.size)
            i++
        }
        assertTrue(false, "protocol delimiter (8 null bytes) not found in the response")
        return ByteArray(0)
    }

    companion object {
        private const val BUCKET = "streaming-test-bucket"

        // Pinned LocalStack image; S3 only. TestContainers manages it via the Colima socket.
        private val LOCALSTACK_IMAGE: DockerImageName = DockerImageName.parse("localstack/localstack:3.8.1")

        private lateinit var localstack: LocalStackContainer
        private lateinit var s3: S3Client
    }
}
