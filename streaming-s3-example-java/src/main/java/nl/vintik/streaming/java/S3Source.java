package nl.vintik.streaming.java;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.vintik.lambda.streaming.BoundedBufferKt;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Wraps the AWS SDK for Java v2 synchronous {@link S3Client} for the two streaming
 * concerns: confirm the object exists (and its size) before the response status is
 * committed, then open and copy its body through the library's bounded buffer.
 *
 * <p>Java counterpart of the Kotlin example's {@code S3Source}. The Kotlin example uses
 * the coroutine-based Kotlin SDK; this one uses SDK v2 sync so the handler is
 * straight-line blocking code with no {@code runBlocking}/{@code suspend}.
 *
 * <p>The bucket name comes from the {@code BUCKET_NAME} environment variable and is never
 * logged; object keys are likewise never logged (project logging rules — never log
 * secrets or resource identifiers).
 */
public final class S3Source {

    private static final Logger logger = LoggerFactory.getLogger(S3Source.class);

    /** Existence/size confirmation must not hang the request (Req 4.1). */
    private static final Duration HEAD_TIMEOUT = Duration.ofSeconds(10);

    private final S3Client client;
    private final String bucket;

    /**
     * Production constructor: builds a default-configuration-chain {@link S3Client}
     * (region and credentials resolved from the environment) and reads the bucket name
     * from the {@code BUCKET_NAME} environment variable.
     */
    public S3Source() {
        this(S3Client.create(), System.getenv("BUCKET_NAME"));
    }

    /**
     * Package-private constructor for unit tests: injects a (Mockito) {@link S3Client}
     * and the bucket name so behaviour can be exercised without touching AWS.
     *
     * @param client the S3 client to use
     * @param bucket the source bucket name
     */
    S3Source(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /**
     * Confirms the requested object's existence and size via {@code headObject}, bounded
     * by a request-level 10 second API call timeout (Req 4.1). A missing object maps to
     * {@link HeadResult.NotFound} (Req 4.2); an {@link ApiCallTimeoutException} or any
     * other failure maps to {@link HeadResult.Failure} (Req 4.4). On success the object
     * size is returned as {@link HeadResult.Exists} for the {@code Content-Length}
     * (Req 4.3).
     *
     * <p><strong>SDK behavior note:</strong> The AWS SDK for Java v2 only throws
     * {@link NoSuchKeyException} for a true HTTP 404 from S3, which requires the caller
     * to have {@code s3:ListBucket} permission. When the role has only
     * {@code s3:GetObject}, S3 returns HTTP 403 for a missing key (to prevent probing).
     * The SDK surfaces this as a generic {@link S3Exception} with status code 403. This
     * method treats both 403 and 404 status codes from {@code S3Exception} as
     * {@link HeadResult.NotFound} so that missing objects are correctly reported as 404
     * to the client regardless of the IAM policy shape.
     *
     * @param request the validated file request
     * @return the existence/size outcome
     */
    public HeadResult head(FileRequest request) {
        try {
            HeadObjectResponse response = client.headObject(b -> b
                    .bucket(bucket)
                    .key(request.fileName())
                    .overrideConfiguration(o -> o.apiCallTimeout(HEAD_TIMEOUT)));
            Long contentLength = response.contentLength();
            return new HeadResult.Exists(contentLength != null ? contentLength : 0L);
        } catch (NoSuchKeyException e) {
            logger.warn("S3 object not found during head", e);
            return new HeadResult.NotFound();
        } catch (S3Exception e) {
            // With s3:GetObject-only (no s3:ListBucket), S3 returns 403 for a missing key.
            // The Kotlin SDK maps this to NotFound; Java SDK v2 does not — handle it here.
            if (e.statusCode() == 403 || e.statusCode() == 404) {
                logger.warn("S3 object not found during head (status {})", e.statusCode(), e);
                return new HeadResult.NotFound();
            }
            logger.warn("S3 head failed", e);
            return new HeadResult.Failure(e);
        } catch (ApiCallTimeoutException e) {
            logger.warn("S3 head timed out", e);
            return new HeadResult.Failure(e);
        } catch (RuntimeException e) {
            logger.warn("S3 head failed", e);
            return new HeadResult.Failure(e);
        }
    }

    /**
     * Streams the object body to {@code sink} by copying through the library's bounded
     * buffer, which flushes the sink per chunk so the client observes progressive
     * delivery (Req 1.4, 6.1, 6.4). The SDK v2 {@link ResponseInputStream} is
     * {@link java.io.Closeable}, so try-with-resources releases it on both success and
     * failure; a mid-copy failure stops the copy, releases the stream, and propagates
     * (Req 6.6). The whole object is never materialized as a single {@code String}/
     * {@code byte[]} (Req 6.3).
     *
     * @param request the validated file request
     * @param sink    the Lambda output stream to write the body to
     * @return the total number of bytes copied
     * @throws IOException if reading from S3 or writing to the sink fails
     */
    public long streamBody(FileRequest request, OutputStream sink) throws IOException {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(request.fileName())
                .build();
        try (ResponseInputStream<GetObjectResponse> in = client.getObject(get)) {
            return BoundedBufferKt.copy(in, sink);
        }
    }
}
