package nl.vintik.lambda.streaming

import java.io.InputStream

/**
 * Resolves the incoming Lambda [InputStream] to a typed request [R].
 *
 * Implementations read and parse the incoming event (e.g. an API Gateway proxy event),
 * validate the input, and return either a [RequestResult.Resolved] carrying the request
 * object that [StreamHandler] will pass to [StreamSource], or a [RequestResult.Error]
 * carrying an HTTP status code and message that [StreamHandler] will write as the error
 * response — without issuing any source request.
 *
 * The interface is intentionally narrow: it has no knowledge of what [R] means or how
 * the source uses it. Parsing, validation, and error-message wording are entirely the
 * implementor's responsibility.
 */
fun interface RequestResolver<out R : Any> {
    fun resolve(input: InputStream): RequestResult<R>
}

/**
 * Outcome of resolving a typed request from an incoming Lambda event.
 *
 * [Resolved] carries the request object [R] to pass to [StreamSource].
 * [Error] carries an HTTP status code and message to return immediately,
 * without issuing any source request.
 */
sealed interface RequestResult<out R : Any> {
    data class Resolved<R : Any>(val request: R) : RequestResult<R>
    data class Error(val statusCode: Int, val message: String) : RequestResult<Nothing>
}
