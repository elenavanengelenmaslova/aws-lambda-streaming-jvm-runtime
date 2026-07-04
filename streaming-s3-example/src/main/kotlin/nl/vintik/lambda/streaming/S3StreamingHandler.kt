package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import java.io.InputStream
import java.io.OutputStream

class S3StreamingHandler : RequestStreamHandler {
    private val handler = StreamHandler(
        requestResolver = ::FileKeyResolver,
        source = ::S3Source,
    )

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) =
        handler.handleRequest(input, output, context)
}
