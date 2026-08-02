package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import kotlinx.serialization.json.Json;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.ResponseWriterKt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Shared LocalStack S3 harness for the Java example's end-to-end integration tests
 * (Req 13.2, 13.4). Streams a real S3 object THROUGH the production {@link StreamHandler}
 * against a containerized S3 reached with the AWS SDK for Java v2, so the wire protocol,
 * bounded-buffer copy, and S3 source are all exercised together against a real S3
 * implementation &mdash; not a mock.
 *
 * <p><b>One container per class.</b> A single {@link LocalStackContainer} (S3 only) is
 * started in {@link #startContainer()} and stopped in {@link #stopContainer()}. The class
 * is {@link TestInstance.Lifecycle#PER_CLASS} so those hooks are instance methods and each
 * concrete subclass gets its own container shared across all of its test methods; readiness
 * is gated on {@code Wait.forHttp("/_localstack/health").forStatusCode(200)} per
 * {@code tech.md}. Between tests only object data is cleaned ({@link #cleanObjects()}) &mdash;
 * the container and bucket keep running.
 *
 * <p><b>Reuse.</b> This base is self-contained and holds no test methods (it is abstract, so
 * JUnit does not run it directly). Subclasses add {@code @Test} methods and drive the handler
 * via {@link #handlerForBucket()} / {@link #upload(String, byte[])} / {@link #proxyEvent(String)},
 * then read the protocol response with {@link #extractMetadataJson(byte[])} and
 * {@link #extractBody(byte[])}. The sub-6 MB and ~15 MB integration tests both build on it.
 *
 * <p><b>Container runtime.</b> This project runs on Colima, not Docker Desktop (see
 * {@code tech.md}); TestContainers connects via the Colima Docker socket
 * ({@code DOCKER_HOST} + {@code TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE}). Subclasses are tagged
 * {@code integration} so they are excluded by {@code -PexcludeTags=integration} when no
 * container runtime is available; they are not skip-annotated, so they run whenever one is.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class LocalStackS3IntegrationTestBase {

    /** Pinned LocalStack image (S3 only), matching the Kotlin example. */
    private static final DockerImageName LOCALSTACK_IMAGE =
            DockerImageName.parse("localstack/localstack:3.8.1");

    /** Shared source bucket, created once per class in {@link #startContainer()}. */
    protected static final String BUCKET = "streaming-test-bucket";

    /**
     * Protocol delimiter length, read from the library facade so the metadata/body boundary
     * constant is never duplicated in the Java example.
     */
    private static final int DELIMITER_LEN = ResponseWriterKt.DELIMITER_LEN;

    private LocalStackContainer localstack;
    private S3Client s3;

    // The S3Client is a long-lived field closed in stopContainer(); its lifecycle spans methods.
    @SuppressWarnings("resource")
    @BeforeAll
    void startContainer() {
        localstack = new LocalStackContainer(LOCALSTACK_IMAGE)
                .withServices(LocalStackContainer.Service.S3)
                .waitingFor(Wait.forHttp("/_localstack/health").forStatusCode(200));
        localstack.start();

        s3 = S3Client.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.S3))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .region(Region.of(localstack.getRegion()))
                // LocalStack uses path-style addressing (bucket in the path, not the host).
                .forcePathStyle(true)
                .build();

        s3.createBucket(b -> b.bucket(BUCKET));
    }

    @AfterAll
    void stopContainer() {
        if (s3 != null) {
            s3.close();
        }
        if (localstack != null) {
            localstack.stop();
        }
    }

    /** Clean only object data between tests &mdash; the shared container and bucket keep running. */
    @AfterEach
    void cleanObjects() {
        ListObjectsV2Response listed = s3.listObjectsV2(b -> b.bucket(BUCKET));
        for (S3Object object : listed.contents()) {
            s3.deleteObject(b -> b.bucket(BUCKET).key(object.key()));
        }
    }

    // ---- helpers for subclasses -------------------------------------------------------------

    /** Uploads {@code payload} under {@code key} into the shared bucket via SDK v2. */
    protected void upload(String key, byte[] payload) {
        s3.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromBytes(payload));
    }

    /**
     * Builds the production {@link StreamHandler} wired to the LocalStack-backed S3. Real
     * {@link RequestParser}, {@link FileNameValidator}, and library {@link ResponseWriter}
     * collaborators are injected via the package-private constructor; only the {@link S3Source}
     * is pointed at the container (its client + bucket), so the full parse &rarr; validate
     * &rarr; head &rarr; stream pipeline runs against real S3.
     */
    protected StreamHandler handlerForBucket() {
        return new StreamHandler(
                new RequestParser(),
                new FileNameValidator(),
                new S3Source(s3, BUCKET),
                new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN));
    }

    /** A minimal API Gateway {@code /{proxy+}} event carrying the requested file name. */
    protected static InputStream proxyEvent(String fileName) {
        String json = "{\"pathParameters\":{\"proxy\":\"" + fileName + "\"}}";
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Start index of the first run of {@link #DELIMITER_LEN} consecutive zero bytes (the
     * metadata/body delimiter), or {@code -1} if absent. The metadata JSON never contains a raw
     * NUL byte, so the first such run reliably separates the metadata prelude from the body.
     */
    private static int indexOfDelimiter(byte[] response) {
        int run = 0;
        for (int i = 0; i < response.length; i++) {
            if (response[i] == 0) {
                run++;
                if (run == DELIMITER_LEN) {
                    return i - DELIMITER_LEN + 1;
                }
            } else {
                run = 0;
            }
        }
        return -1;
    }

    /** The metadata JSON prelude: every byte before the 8 null-byte delimiter. */
    protected static String extractMetadataJson(byte[] response) {
        int end = indexOfDelimiter(response);
        assertTrue(end >= 0, "the 8 null-byte metadata/body delimiter must be present in the response");
        return new String(response, 0, end, StandardCharsets.UTF_8);
    }

    /** The streamed body: every byte after the metadata JSON and the 8 null-byte delimiter. */
    protected static byte[] extractBody(byte[] response) {
        int start = indexOfDelimiter(response);
        assertTrue(start >= 0, "the 8 null-byte metadata/body delimiter must be present in the response");
        return Arrays.copyOfRange(response, start + DELIMITER_LEN, response.length);
    }
}
