package nl.vintik.streaming.java;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import kotlinx.serialization.json.Json;
import nl.vintik.lambda.streaming.ResponseMetadata;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.ResponseWriterKt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the streaming pipeline for the Java example: parse the API Gateway proxy
 * event, validate the requested file name, confirm the S3 object exists (and its size),
 * then drive the library's {@link ResponseWriter} to write a 200 metadata prelude and
 * stream the body. Owns the top-level mapping of every outcome to an HTTP status code
 * (400/404/502/200) and the status-committed-early ordering rule.
 *
 * <p>Java counterpart of the Kotlin example's {@code StreamHandler}. It is synchronous
 * straight-line code (no coroutines): the AWS SDK for Java v2 calls in {@link S3Source}
 * block directly. Collaborators are supplied by constructor injection rather than Kotlin
 * delegates &mdash; a public no-arg constructor wires the real defaults for the Lambda
 * runtime, and a package-private constructor takes collaborators so tests can substitute
 * Mockito doubles.
 *
 * <p>Ordering guarantees:
 * <ul>
 *   <li>No body bytes are written on any pre-commit error path &mdash; a parse failure, a
 *       validation rejection, a not-found, or a head failure/timeout all produce a
 *       metadata-only error response via {@link ResponseWriter#writeError} (Req 1.2, 2.2,
 *       2.3, 2.4, 3.7, 4.2, 4.4, 5.4).</li>
 *   <li>Existence/size is confirmed before any metadata is written (head-before-commit,
 *       Req 4.1, 4.3).</li>
 *   <li>Once the metadata JSON and the 8 null-byte delimiter are written the status is
 *       committed; a later failure can only truncate the body and propagate, never rewrite
 *       the status (Req 5.3, 7.3).</li>
 *   <li>The output stream is flushed and then closed on every exit path via
 *       try-with-resources, so the streaming runtime delivers the complete response and does
 *       not truncate the body on fast/warm invocations (Req 5.5).</li>
 * </ul>
 */
public final class StreamHandler implements RequestStreamHandler {

    private static final Logger logger = LoggerFactory.getLogger(StreamHandler.class);

    /** Body content type declared for a successful stream (Req 4.3). */
    private static final String OCTET_STREAM = "application/octet-stream";

    private final RequestParser parser;
    private final FileNameValidator validator;
    private final S3Source source;
    private final ResponseWriter writer;

    /**
     * Production constructor: wires the real collaborators. The {@link ResponseWriter} is
     * constructed via the library's Java-visible {@code (Json, Integer)} constructor &mdash;
     * Java cannot use Kotlin default arguments, so {@link Json#Default} and the top-level
     * {@code OBSERVED_MAX_PRELUDE_LEN} constant on the generated {@code *Kt} facade are passed
     * explicitly (Req 1.5). Opting into the observed prelude limit exercises the oversized-prelude
     * fail-fast from Java, matching how MockNest constructs the writer.
     */
    public StreamHandler() {
        this(
            new RequestParser(),
            new FileNameValidator(),
            new S3Source(),
            new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN));
    }

    /**
     * Package-private constructor for unit tests: injects the collaborators so the status
     * mapping and ordering can be exercised without touching AWS or the real wire format.
     *
     * @param parser    parses the proxy event into a {@link StreamRequest}
     * @param validator validates the requested file name
     * @param source    confirms existence/size and streams the object body
     * @param writer    the library writer that owns the wire-format encoding
     */
    StreamHandler(RequestParser parser, FileNameValidator validator, S3Source source, ResponseWriter writer) {
        this.parser = parser;
        this.validator = validator;
        this.source = source;
        this.writer = writer;
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        try (output) {
            switch (parser.parse(input)) {
                case ParseResult.ParseError _ -> {
                    logger.warn("API Gateway proxy event could not be parsed; responding 400");
                    writer.writeError(output, 400, "The request could not be parsed.");
                }
                case ParseResult.Parsed parsed -> handleValidated(parsed.request(), output);
            }
            // Flush before the try-with-resources close so the streaming runtime drains its
            // buffer completely before receiving the close signal (Req 5.5).
            output.flush();
        }
    }

    /**
     * Validates the requested file name and maps every rejection to HTTP 400 with a specific
     * message, issuing no S3 request for a rejected name (Req 2.2, 2.4, 3.7). A valid name
     * proceeds to the existence check.
     */
    private void handleValidated(StreamRequest request, OutputStream output) throws IOException {
        switch (validator.validate(request.fileName())) {
            case ValidationResult.Invalid invalid -> {
                logger.warn("File name rejected (reason={}); responding 400 with no S3 request", invalid.reason());
                writer.writeError(output, 400, messageFor(invalid.reason()));
            }
            case ValidationResult.Valid valid -> handleValidObject(new FileRequest(valid.fileName()), output);
        }
    }

    /**
     * Confirms the object exists and its size before any metadata is written (Req 4.1):
     * {@link HeadResult.NotFound} maps to 404 (Req 4.2), {@link HeadResult.Failure} (any
     * non-missing error or timeout) maps to 502 (Req 4.4), and {@link HeadResult.Exists}
     * proceeds to stream the body with a committed 200.
     */
    private void handleValidObject(FileRequest request, OutputStream output) throws IOException {
        switch (source.head(request)) {
            case HeadResult.NotFound _ -> {
                logger.warn("Requested object not found; responding 404");
                writer.writeError(output, 404, "The requested object was not found.");
            }
            case HeadResult.Failure failure -> {
                logger.warn("Object head failed or timed out; responding 502", failure.cause());
                writer.writeError(output, 502, "Object retrieval failed.");
            }
            case HeadResult.Exists exists -> streamObject(request, exists.size(), output);
        }
    }

    /**
     * Writes the success response: metadata (status 200, {@code Content-Type:
     * application/octet-stream}, {@code Content-Length} = {@code size}) before any body bytes
     * (Req 4.3, 5.1), then streams the body through the library's bounded buffer. The status is
     * committed once {@link ResponseWriter#writeMetadata} returns; a body failure after that can
     * only truncate the body &mdash; it is logged at ERROR and propagated, leaving the committed
     * status unchanged (Req 5.3, 7.3).
     */
    private void streamObject(FileRequest request, long size, OutputStream output) throws IOException {
        ResponseMetadata metadata = new ResponseMetadata(
            200,
            Map.of("Content-Type", OCTET_STREAM, "Content-Length", String.valueOf(size)),
            null);
        writer.writeMetadata(output, metadata);
        // Status committed. From here a failure can only truncate the body — never rewrite the status.
        try {
            source.streamBody(request, output);
        } catch (IOException | RuntimeException e) {
            logger.error("Body stream failed after status commit; response body truncated", e);
            throw e;
        }
    }

    /**
     * Maps a rejection {@link ValidationResult.Reason} to a client-facing 400 message,
     * mirroring the Kotlin example's wording. Missing and too-long names get distinct
     * messages; every path-safety rejection collapses to a single generic message so the
     * response does not disclose which specific rule tripped.
     */
    private static String messageFor(ValidationResult.Reason reason) {
        return switch (reason) {
            case MISSING -> "The file name is missing.";
            case TOO_LONG -> "The file name is invalid.";
            case ILLEGAL_CHARACTER, PATH_SEPARATOR, PARENT_DIR, ABSOLUTE_PATH -> "The file name was rejected.";
        };
    }
}
