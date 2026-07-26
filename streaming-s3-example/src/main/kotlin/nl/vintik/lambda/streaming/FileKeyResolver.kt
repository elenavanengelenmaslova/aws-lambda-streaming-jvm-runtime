package nl.vintik.lambda.streaming

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.vintik.lambda.streaming.ValidationResult.Reason
import java.io.InputStream

private val logger = KotlinLogging.logger {}

/**
 * [RequestResolver] implementation for an API Gateway `/{proxy+}` file-serving endpoint.
 *
 * Combines [RequestParser] (extracts the file name from the proxy path parameter) and
 * [FileNameValidator] (rejects path-traversal, illegal characters, etc.) to produce a
 * [RequestResult]. All validation failures map to HTTP 400 with a specific message; a parse
 * failure also maps to HTTP 400. No source request is issued for any rejected input.
 *
 * Returns [RequestResult.Resolved] carrying a [FileRequest] with the validated file name,
 * or [RequestResult.Error] with an appropriate HTTP status and message.
 */
class FileKeyResolver(
    private val parser: RequestParser = RequestParser(),
    private val validator: FileNameValidator = FileNameValidator(),
) : RequestResolver<FileRequest> {

    override fun resolve(input: InputStream): RequestResult<FileRequest> {
        return when (val parsed = parser.parse(input)) {
            is ParseResult.ParseError -> {
                logger.warn { "API Gateway proxy event could not be parsed; responding 400" }
                RequestResult.Error(400, "The request could not be parsed.")
            }

            is ParseResult.Parsed -> resolveFileName(parsed.request.fileName)
        }
    }

    private fun resolveFileName(fileName: String): RequestResult<FileRequest> {
        return when (val validation = validator.validate(fileName)) {
            is ValidationResult.Invalid -> {
                val message = when (validation.reason) {
                    Reason.MISSING -> "The file name is missing."
                    Reason.TOO_LONG -> "The file name is invalid."
                    Reason.ILLEGAL_CHARACTER,
                    Reason.PATH_SEPARATOR,
                    Reason.PARENT_DIR,
                    Reason.ABSOLUTE_PATH,
                    -> "The file name was rejected."
                }
                logger.warn { "File name rejected (reason=${validation.reason}); responding 400 with no source request" }
                RequestResult.Error(400, message)
            }

            is ValidationResult.Valid -> RequestResult.Resolved(FileRequest(validation.fileName))
        }
    }
}
