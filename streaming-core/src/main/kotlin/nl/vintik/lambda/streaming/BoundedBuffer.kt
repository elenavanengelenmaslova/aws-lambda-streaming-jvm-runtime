package nl.vintik.lambda.streaming

import java.io.InputStream
import java.io.OutputStream

/**
 * Fixed transfer-buffer size: 1 MB. This is constant for all S3 object sizes so
 * peak transfer memory is independent of the object size (Req 5.2).
 */
const val BUFFER_SIZE = 1_048_576

/**
 * Copies bytes from [source] to [sink] through a single reused [ByteArray] of
 * [BUFFER_SIZE], flushing after each written chunk so the client observes
 * progressive delivery, and flushing once more after the final partial chunk.
 *
 * The complete object body is never materialized as a single `String`/`ByteArray`:
 * exactly one [BUFFER_SIZE] buffer is allocated regardless of the total bytes copied
 * (Req 5.3). The bytes written to [sink] are byte-identical, in order and count, to
 * the bytes read from [source] (Req 5.6).
 *
 * @param source the S3 object input stream to read from
 * @param sink the Lambda output stream to write to
 * @param flush invoked after each written chunk and once after the final chunk (Req 5.4, 5.5)
 * @return the total number of bytes copied
 */
fun copy(source: InputStream, sink: OutputStream, flush: () -> Unit): Long {
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = source.read(buffer)
        if (read < 0) break
        sink.write(buffer, 0, read)
        flush()
        total += read
    }
    flush()
    return total
}
