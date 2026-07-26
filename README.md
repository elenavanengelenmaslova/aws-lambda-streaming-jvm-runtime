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
implementation("nl.vintik:aws-lambda-streaming-core:<!-- VERSION -->")
```

No AWS SDK dependency. The library only depends on `aws-lambda-java-core` (the `RequestStreamHandler` interface) and `kotlinx-serialization-json` (metadata encoding).

## Usage

Implement `RequestStreamHandler` and use `ResponseWriter` to write the protocol:

```kotlin
class MyHandler : RequestStreamHandler {
    private val writer = ResponseWriter()

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        output.use {
            // --- parse input and validate ---
            // on error:
            writer.writeError(output, 400, "The request could not be parsed.")
            return

            // --- on success: write metadata, then stream body ---
            val metadata = ResponseMetadata(
                statusCode = 200,
                headers = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Content-Length" to contentLength.toString(),
                ),
            )
            writer.writeMetadata(output, metadata)
            // status is now committed — stream body bytes
            copy(sourceStream, output) { output.flush() }
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
| `ResponseWriter.writeError(output, status, message)` | Writes an error response (metadata + JSON body) |
| `ResponseMetadata` | Data class: `statusCode: Int`, `headers: Map<String, String>` |
| `copy(source, sink, flush)` | 1 MB bounded copy utility for streaming body bytes |
| `DELIMITER_LEN` | The 8-byte delimiter length constant |

## S3 example

The companion module `streaming-s3-example` is a complete AWS Lambda deployment that streams files from S3. It demonstrates one way to build a full handler on top of this library, including request parsing, validation, a `head`-before-commit existence check, and streaming via the AWS S3 Kotlin SDK.

See the `streaming-s3-example` source for `FileKeyResolver`, `FileRequest`, `S3Source`, and `StreamHandler` as reference implementations.

## License

See LICENSE file.
