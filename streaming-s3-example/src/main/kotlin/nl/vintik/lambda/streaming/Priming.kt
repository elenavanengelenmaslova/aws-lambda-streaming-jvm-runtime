package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.crac.Core
import org.crac.Resource
import java.io.InputStream
import java.io.OutputStream
import org.crac.Context as CracContext

private val logger = KotlinLogging.logger {}

/** File name carried by the primed proxy event used to warm the handler path. */
private const val PRIMED_FILE_NAME = "primer.bin"

/** A representative API Gateway `/{proxy+}` event whose `pathParameters.proxy` is the primed file name. */
private val PRIMED_EVENT_JSON = """{"pathParameters":{"proxy":"$PRIMED_FILE_NAME"}}"""

/** Representative success metadata serialized to warm the kotlinx-serialization path. */
private val PRIMED_METADATA = ResponseMetadata(
    statusCode = 200,
    headers = mapOf(
        "Content-Type" to "application/octet-stream",
        "Content-Length" to "0",
    ),
)

/**
 * CRaC warm-up hook for SnapStart. Registered with the global CRaC context at construction
 * so the AWS Lambda runtime invokes [beforeCheckpoint] while the SnapStart snapshot is being
 * created, leaving the restored snapshot fully warm (Req 7.2).
 *
 * [beforeCheckpoint] exercises, in a single pass, the three critical paths the snapshot
 * benefits from being warm: S3 client initialization, one invocation of the [StreamHandler]
 * against a primed request, and serialization of the response metadata. It deliberately does
 * NOT catch errors — any failure propagates so snapshot creation fails and no Lambda version
 * is published (Req 7.3).
 *
 * Collaborators are injected as factory lambdas (defaulting to the production constructors) so
 * the priming pass stays testable: a test can substitute mocks to assert each critical path is
 * touched exactly once, and inject a throwing factory to assert the failure propagates.
 */
class Priming(
    private val s3SourceFactory: () -> StreamSource<FileRequest> = ::S3Source,
    private val handlerFactory: () -> StreamHandler<FileRequest> = { StreamHandler(requestResolver = ::FileKeyResolver, source = ::S3Source) },
    private val responseWriterFactory: () -> ResponseWriter = { ResponseWriter() },
    private val primedRequest: () -> InputStream = { PRIMED_EVENT_JSON.byteInputStream() },
) : Resource {

    init {
        Core.getGlobalContext().register(this)
    }

    /**
     * Exercises the critical path in one pass during the before-checkpoint phase:
     *  1. S3 client initialization ([S3Source] construction builds the underlying client).
     *  2. One [StreamHandler] invocation against a primed proxy event, discarding the output.
     *  3. Response metadata serialization via [ResponseWriter.writeMetadata].
     *
     * No error handling wraps these steps: a failure on any primed path propagates so the
     * snapshot fails (Req 7.3).
     */
    override fun beforeCheckpoint(context: CracContext<out Resource>?) {
        logger.info { "Priming critical paths before checkpoint" }

        // 1. S3 client initialization.
        s3SourceFactory()

        // 2. One handler invocation against a primed request; output is discarded.
        handlerFactory().handleRequest(primedRequest(), OutputStream.nullOutputStream(), PrimingContext)

        // 3. Response metadata serialization.
        responseWriterFactory().writeMetadata(OutputStream.nullOutputStream(), PRIMED_METADATA)

        logger.info { "Priming complete" }
    }

    /** No work is required on restore; the snapshot is already warm. */
    override fun afterRestore(context: CracContext<out Resource>?) = Unit
}

/**
 * Minimal no-op [Context] used only for the priming handler invocation. The handler does not
 * read any context fields, so every accessor returns an empty/zero default and logging is
 * discarded.
 */
private object PrimingContext : Context {
    override fun getAwsRequestId(): String = ""
    override fun getLogGroupName(): String = ""
    override fun getLogStreamName(): String = ""
    override fun getFunctionName(): String = ""
    override fun getFunctionVersion(): String = ""
    override fun getInvokedFunctionArn(): String = ""
    override fun getIdentity(): CognitoIdentity? = null
    override fun getClientContext(): ClientContext? = null
    override fun getRemainingTimeInMillis(): Int = 0
    override fun getMemoryLimitInMB(): Int = 0
    override fun getLogger(): LambdaLogger = PrimingLogger
}

/** No-op [LambdaLogger] backing [PrimingContext]; priming output is not logged through the runtime. */
private object PrimingLogger : LambdaLogger {
    override fun log(message: String) = Unit
    override fun log(message: ByteArray) = Unit
}
