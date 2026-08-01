package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * LocalStack S3 integration test proving the <b>sub-6 MB</b> streaming path end to end
 * (Req 6.5, 13.2, 13.4).
 *
 * <p>A sub-6 MB object is uploaded to the shared containerized S3 through the AWS SDK for
 * Java v2, then streamed <b>through the production {@link StreamHandler}</b> from a synthetic
 * API Gateway {@code /{proxy+}} event naming the object. The handler runs the full pipeline
 * &mdash; parse &rarr; validate &rarr; head &rarr; write metadata + 8 null-byte delimiter +
 * body &mdash; against the live S3 endpoint. The received body bytes (everything after the
 * protocol delimiter) are asserted byte-identical to the uploaded object.
 *
 * <p>The 5 MB fixture is comfortably under the legacy 6 MB buffered limit yet larger than the
 * library's 1 MB transfer buffer, so the bounded-buffer copy loops over several full chunks
 * plus a partial final chunk. Container lifecycle, S3 client wiring, and protocol parsing are
 * inherited from {@link LocalStackS3IntegrationTestBase}; the ~15 MB companion test reuses the
 * same base.
 *
 * <p>Validates: Requirements 6.5, 13.2, 13.4
 */
@Tag("integration")
class SubSixMbStreamingIntegrationTest extends LocalStackS3IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Given a sub-6 MB S3 object When streamed end-to-end through the handler Then the received body is byte-identical")
    void subSixMbObjectStreamsByteIdenticalThroughHandler() throws IOException {
        // Given: 5 MB of deterministic pseudo-random bytes uploaded to the containerized S3.
        String key = "sub-six.bin";
        byte[] payload = new byte[5 * 1024 * 1024];
        new Random(42).nextBytes(payload);
        upload(key, payload);

        // When: the production handler streams it end-to-end against the live S3 source.
        StreamHandler handler = handlerForBucket();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        handler.handleRequest(proxyEvent(key), output, mock(Context.class));

        // Then: the committed metadata is 200 with a Content-Length equal to the stored size.
        byte[] response = output.toByteArray();
        JsonNode metadata = MAPPER.readTree(extractMetadataJson(response));
        assertEquals(200, metadata.get("statusCode").asInt(), "committed status must be 200");
        assertEquals(
                String.valueOf(payload.length),
                metadata.get("headers").get("Content-Length").asText(),
                "declared Content-Length must equal the uploaded object size");

        // And: the streamed body is byte-identical (same bytes, order, and count) to the upload.
        byte[] body = extractBody(response);
        assertEquals(payload.length, body.length, "received body length must equal the uploaded size");
        assertArrayEquals(payload, body, "streamed body must be byte-identical to the uploaded object");
    }
}
