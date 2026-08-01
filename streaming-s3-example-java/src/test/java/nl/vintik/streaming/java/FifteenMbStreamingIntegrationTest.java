package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * LocalStack S3 integration test proving delivery of the ~15 MB {@code Test_Object}
 * end to end, past the legacy 6 MB buffered limit (Req 7.1, 7.2, 13.2, 13.4).
 *
 * <p>An approximately 15 MB object is uploaded to the shared containerized S3 through the
 * AWS SDK for Java v2, then streamed <b>through the production {@link StreamHandler}</b> from
 * a synthetic API Gateway {@code /{proxy+}} event naming the object. The handler runs the full
 * pipeline &mdash; parse &rarr; validate &rarr; head &rarr; write metadata + 8 null-byte
 * delimiter + body &mdash; against the live S3 endpoint. The response is asserted to commit
 * HTTP 200, the received total body byte count is asserted to equal the stored object size,
 * and the received body is asserted byte-identical to the upload.
 *
 * <p>At ~15 MB the payload is well beyond the 6 MB buffered response limit that response
 * streaming bypasses and many times the library's 1 MB transfer buffer, so the bounded-buffer
 * copy loops over roughly fifteen full chunks plus a partial final chunk while never holding
 * the whole object in memory. Delivery of every byte without truncation proves the streaming
 * path clears the {@code 6MB_Limit}. Container lifecycle, S3 client wiring, and protocol
 * parsing are inherited from {@link LocalStackS3IntegrationTestBase}, mirroring the sub-6 MB
 * companion test.
 *
 * <p>Validates: Requirements 7.1, 7.2, 13.2, 13.4
 */
@Tag("integration")
class FifteenMbStreamingIntegrationTest extends LocalStackS3IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ~15 MB &mdash; comfortably above the 6 MB buffered response limit that streaming bypasses. */
    private static final int FIFTEEN_MB = 15 * 1024 * 1024;

    /** Legacy buffered response payload ceiling that response streaming exists to exceed. */
    private static final int SIX_MB_LIMIT = 6 * 1024 * 1024;

    @Test
    @DisplayName("Given a ~15 MB S3 object When streamed end-to-end through the handler Then status is 200 and the full body is byte-identical past the 6 MB limit")
    void fifteenMbObjectStreamsByteIdenticalPastSixMbLimit() throws IOException {
        // Given: ~15 MB of deterministic pseudo-random bytes (fixed seed so failures reproduce)
        // uploaded to the containerized S3 &mdash; larger than the 6 MB buffered limit.
        String key = "fifteen-mb-test-object.bin";
        byte[] payload = new byte[FIFTEEN_MB];
        new Random(1515).nextBytes(payload);
        assertTrue(payload.length > SIX_MB_LIMIT, "the Test_Object must exceed the 6 MB buffered limit");
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

        // And: the received total byte count equals the stored size and the body is byte-identical
        // (same bytes, order, and count) &mdash; every byte delivered without truncation past 6 MB.
        byte[] body = extractBody(response);
        assertEquals(payload.length, body.length, "received body byte count must equal the stored object size");
        assertArrayEquals(payload, body, "streamed body must be byte-identical to the uploaded ~15 MB object");
    }
}
