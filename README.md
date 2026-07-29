# aws-lambda-streaming-core

A JVM library that implements the **AWS Lambda / API Gateway HTTP response streaming protocol** for Kotlin and Java.

AWS provides Node.js helpers (`HttpResponseStream.from()`) that hide the wire format. On the JVM there is no equivalent — you write the protocol by hand or use this library.

## What the library does

When a Lambda is configured for response streaming, API Gateway expects a specific binary format on the output stream:

```
[metadata JSON] [8 null bytes] [body bytes…]
```

The library handles:

- **Protocol encoding** — serializes the metadata JSON (`statusCode`, `headers`) and writes the 8-byte delimiter before any body bytes (`ResponseWriter`)
- **Memory-bounded streaming** — copies the body through a fixed 1 MB buffer with per-chunk flush, keeping memory flat regardless of payload size (`copy()`)

## Dependency

```kotlin
implementation("nl.vintik:aws-lambda-streaming-core:2.0.0")
```

**One dependency:** `kotlinx-serialization-json`, for metadata encoding. No AWS artifacts — this module implements the wire protocol and never touches the Lambda or S3 APIs, so you bring your own `aws-lambda-java-core` for the `RequestStreamHandler` interface. No logging framework either: every failure propagates to your code, which has the request context needed to log it usefully.

Compiled for **Java 21**, so it runs on the `java21` and `java25` Lambda runtimes alike.

## Usage

Implement `RequestStreamHandler` and use `ResponseWriter` to write the protocol:

```kotlin
class MyHandler : RequestStreamHandler {
    private val writer = ResponseWriter()

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        output.use {
            // --- parse and validate ---
            val request = parseRequest(input)  // returns null on invalid input
            if (request == null) {
                writer.writeError(output, 400, "The request could not be parsed.")
                return@use
            }

            // --- success: write metadata, then stream body ---
            val contentLength: Long = request.contentLength
            val sourceStream: InputStream = request.openStream()
            writer.writeMetadata(output, ResponseMetadata(
                statusCode = 200,
                headers = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Content-Length" to contentLength.toString(),
                ),
            ))
            // status is now committed — stream body bytes
            copy(sourceStream, output)
            output.flush()
        }
    }
}
```

`ResponseWriter` owns the wire encoding. Once `writeMetadata` returns, the status is committed — a later failure can only truncate the body, never rewrite the status.

## API reference

| Type | Description |
|---|---|
| `ResponseWriter` | Writes the metadata JSON + 8-byte delimiter |
| `ResponseWriter.writeMetadata(output, metadata)` | Commits status + headers, writes delimiter |
| `ResponseWriter.writeResponse(output, metadata, body?)` | Whole response in one call: metadata, delimiter, optional body, flush |
| `ResponseWriter.writeError(output, status, message)` | Convenience error response — `application/json` with a `{"message":…}` body. For any other shape, build it and call `writeResponse` |
| `ResponseMetadata` | Data class: `statusCode: Int`, `headers: Map<String, String>`, `cookies: List<String>?` |
| `ResponseMetadata.fromMultiValue(status, headers)` | Collapses `Map<String, List<String>>` — joins values with `", "`, routes `Set-Cookie` to `cookies` |
| `copy(source, sink, flush?)` | 1 MB bounded copy utility; `flush` defaults to flushing the sink |
| `DELIMITER_LEN` | The 8-byte delimiter length constant |
| `OBSERVED_MAX_PRELUDE_LEN` | Optional prelude ceiling — see below |

### Repeated headers and `Set-Cookie`

The prelude models headers as name → single value, so repeated headers have to be collapsed. `fromMultiValue` joins them with `", "` — except `Set-Cookie`, which it routes to the dedicated `cookies` array. Comma-joining cookies would corrupt them, since a comma is legal inside a cookie's `Expires` date and clients would mis-split the result. When there are no cookies the field is omitted from the JSON entirely.

### Optional prelude size limit

By default the writer imposes no size limit, matching AWS's reference implementation, which validates nothing. If you would rather fail fast than let an oversized prelude reach the runtime — where it may be rejected or silently folded into the response body — opt in:

```kotlin
val writer = ResponseWriter(maxPreludeLen = OBSERVED_MAX_PRELUDE_LEN)
```

An oversized prelude then raises `MetadataTooLargeException` **before anything is written**, so the stream is untouched and the status is still uncommitted — you can write a different response instead. Note that `OBSERVED_MAX_PRELUDE_LEN` (16376) is not an AWS-documented limit; it is the commonly cited 16 KiB budget less the delimiter.

## S3 example

The companion module `streaming-s3-example` is a complete AWS Lambda deployment that streams files from S3. It demonstrates one way to build a full handler on top of this library, including request parsing, validation, a `head`-before-commit existence check, and streaming via the AWS S3 Kotlin SDK.

See the `streaming-s3-example` source for `FileKeyResolver`, `FileRequest`, `S3Source`, and `StreamHandler` as reference implementations.

## License

See LICENSE file.
