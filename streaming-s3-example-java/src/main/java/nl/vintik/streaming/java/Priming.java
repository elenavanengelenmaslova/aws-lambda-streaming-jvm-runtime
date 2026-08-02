package nl.vintik.streaming.java;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import kotlinx.serialization.json.Json;
import nl.vintik.lambda.streaming.ResponseMetadata;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.ResponseWriterKt;
import org.crac.Core;
import org.crac.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CRaC warm-up hook for SnapStart. Registered with the global CRaC context at construction
 * so the AWS Lambda runtime invokes {@link #beforeCheckpoint} while the SnapStart snapshot is
 * being created, leaving the restored snapshot fully warm (Req 8.2).
 *
 * <p>{@link #beforeCheckpoint} exercises, in a single pass, the three critical paths the
 * snapshot benefits from being warm:
 * <ol>
 *   <li>S3 client initialization ({@link S3Source} construction builds the underlying
 *       SDK v2 client);</li>
 *   <li>one {@link StreamHandler} invocation against a primed {@code /{proxy+}} event, with
 *       the output discarded to {@link OutputStream#nullOutputStream()};</li>
 *   <li>response metadata serialization through the library's
 *       {@link ResponseWriter#writeMetadata}.</li>
 * </ol>
 * It deliberately does <strong>not</strong> catch errors &mdash; any failure on a primed path
 * propagates so snapshot creation fails and no Lambda version is published (Req 8.3).
 *
 * <p>Java counterpart of the Kotlin example's {@code Priming}. Collaborators are supplied as
 * {@link Supplier} factories rather than Kotlin default-valued lambda parameters: the public
 * no-arg constructor wires the production factories and registers with CRaC, while a
 * package-private constructor takes the factories so tests can substitute doubles and assert
 * the single-pass contract without registering a real snapshot resource.
 *
 * <p>The response writer factory constructs the library writer via its Java-visible
 * {@code (Json, Integer)} constructor &mdash; Java cannot use Kotlin default arguments, so
 * {@link Json#Default} and the top-level {@code OBSERVED_MAX_PRELUDE_LEN} constant on the
 * generated {@code *Kt} facade are passed explicitly (Req 1.5), matching how {@link StreamHandler}
 * builds it in production so the same construction path is warmed.
 */
public final class Priming implements Resource {

    private static final Logger logger = LoggerFactory.getLogger(Priming.class);

    /** File name carried by the primed proxy event used to warm the handler path. */
    private static final String PRIMED_FILE_NAME = "primer.bin";

    /**
     * A representative API Gateway {@code /{proxy+}} event whose {@code pathParameters.proxy}
     * is the primed file name, so the parse &rarr; validate &rarr; head path is exercised end to end.
     */
    private static final String PRIMED_EVENT_JSON =
        "{\"pathParameters\":{\"proxy\":\"" + PRIMED_FILE_NAME + "\"}}";

    /** Representative success metadata serialized to warm the library's serialization path. */
    private static final ResponseMetadata PRIMED_METADATA = new ResponseMetadata(
        200,
        Map.of("Content-Type", "application/octet-stream", "Content-Length", "0"),
        null);

    /** Minimal no-op {@link Context} backing the primed handler invocation. */
    private static final Context PRIMING_CONTEXT = new PrimingContext();

    private final Supplier<S3Source> s3SourceFactory;
    private final Supplier<StreamHandler> handlerFactory;
    private final Supplier<ResponseWriter> responseWriterFactory;
    private final Supplier<InputStream> primedRequest;

    /**
     * Production constructor: wires the real collaborator factories and registers this hook with
     * the global CRaC context so the runtime drives {@link #beforeCheckpoint} during snapshotting.
     */
    public Priming() {
        this(
            S3Source::new,
            StreamHandler::new,
            () -> new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN),
            Priming::primedEvent);
        Core.getGlobalContext().register(this);
    }

    /**
     * Package-private constructor for unit tests: injects the collaborator factories so the
     * single-pass contract can be asserted (each critical path touched exactly once) and a
     * throwing primed path can be verified to propagate, without registering a snapshot resource.
     *
     * @param s3SourceFactory       builds the S3 source, exercising S3 client initialization
     * @param handlerFactory        builds the handler invoked against the primed request
     * @param responseWriterFactory builds the library writer used for metadata serialization
     * @param primedRequest         supplies the primed proxy event as a fresh input stream
     */
    Priming(
            Supplier<S3Source> s3SourceFactory,
            Supplier<StreamHandler> handlerFactory,
            Supplier<ResponseWriter> responseWriterFactory,
            Supplier<InputStream> primedRequest) {
        this.s3SourceFactory = s3SourceFactory;
        this.handlerFactory = handlerFactory;
        this.responseWriterFactory = responseWriterFactory;
        this.primedRequest = primedRequest;
    }

    /**
     * Exercises the critical path in one pass during the before-checkpoint phase: S3 client
     * initialization, one handler invocation against a primed proxy event (output discarded), and
     * response metadata serialization. No error handling wraps these steps &mdash; a failure on any
     * primed path propagates so the snapshot fails and no version is published (Req 8.3).
     */
    @Override
    public void beforeCheckpoint(org.crac.Context<? extends Resource> context) throws IOException {
        logger.info("Priming critical paths before checkpoint");

        // 1. S3 client initialization.
        s3SourceFactory.get();

        // 2. One handler invocation against a primed request; output is discarded.
        handlerFactory.get().handleRequest(primedRequest.get(), OutputStream.nullOutputStream(), PRIMING_CONTEXT);

        // 3. Response metadata serialization.
        responseWriterFactory.get().writeMetadata(OutputStream.nullOutputStream(), PRIMED_METADATA);

        logger.info("Priming complete");
    }

    /** No work is required on restore; the snapshot is already warm. */
    @Override
    public void afterRestore(org.crac.Context<? extends Resource> context) {
        // Intentionally empty.
    }

    /** Supplies the primed proxy event as a fresh UTF-8 input stream for each priming pass. */
    private static InputStream primedEvent() {
        return new ByteArrayInputStream(PRIMED_EVENT_JSON.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Minimal no-op {@link Context} used only for the priming handler invocation. The handler
     * does not read any context fields, so every accessor returns an empty/zero default and
     * logging is discarded. Mirrors the Kotlin example's {@code PrimingContext}.
     */
    private static final class PrimingContext implements Context {

        private static final LambdaLogger PRIMING_LOGGER = new PrimingLogger();

        @Override
        public String getAwsRequestId() {
            return "";
        }

        @Override
        public String getLogGroupName() {
            return "";
        }

        @Override
        public String getLogStreamName() {
            return "";
        }

        @Override
        public String getFunctionName() {
            return "";
        }

        @Override
        public String getFunctionVersion() {
            return "";
        }

        @Override
        public String getInvokedFunctionArn() {
            return "";
        }

        @Override
        public CognitoIdentity getIdentity() {
            return null;
        }

        @Override
        public ClientContext getClientContext() {
            return null;
        }

        @Override
        public int getRemainingTimeInMillis() {
            return 0;
        }

        @Override
        public int getMemoryLimitInMB() {
            return 0;
        }

        @Override
        public LambdaLogger getLogger() {
            return PRIMING_LOGGER;
        }
    }

    /** No-op {@link LambdaLogger} backing {@link PrimingContext}; priming output is not logged. */
    private static final class PrimingLogger implements LambdaLogger {

        @Override
        public void log(String message) {
            // Intentionally discarded.
        }

        @Override
        public void log(byte[] message) {
            // Intentionally discarded.
        }
    }
}
