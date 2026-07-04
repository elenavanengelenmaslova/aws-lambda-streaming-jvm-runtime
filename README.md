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
- **Head-before-commit** — confirms the resource exists and its size before writing the metadata, so `Content-Length` is known and the status cannot be changed once written
- **Memory-bounded streaming** — copies the body through a fixed 1 MB buffer with per-chunk flush, keeping memory flat regardless of payload size (`BoundedBuffer`)
- **Full pipeline orchestration** — resolves the request, checks existence, writes metadata, streams the body, maps every error (parse failure / not-found / source failure / mid-stream failure) to the correct HTTP status (`StreamHandler`)

You provide two things:

| Your implementation | Library interface | What it does |
|---|---|---|
| `MyRequestResolver` | `RequestResolver<R>` | Reads the Lambda event stream, returns a typed request `R` or an error status |
| `MySource` | `StreamSource<R>` | Given `R`, confirms the resource exists (`head`) and streams it (`streamBody`) |

## Dependency

```kotlin
implementation("nl.vintik:aws-lambda-streaming-core:<!-- VERSION -->")
```

No AWS SDK dependency. The library only depends on `aws-lambda-java-core` (the `RequestStreamHandler` interface), `kotlinx-serialization-json` (metadata encoding), and `kotlinx-coroutines-core`.

## Usage

### 1. Define your request type

```kotlin
data class VideoRequest(val videoId: String, val quality: String)
```

### 2. Implement `RequestResolver<R>`

Reads and validates the incoming Lambda event. Returns `RequestResult.Resolved(request)` or `RequestResult.Error(statusCode, message)`.

The library provides a `jsonRequestResolver<Event, R>` helper that handles JSON deserialization for you:

```kotlin
@Serializable
data class ApiGatewayEvent(val pathParameters: Map<String, String?>? = null)

val videoResolver = jsonRequestResolver<ApiGatewayEvent, VideoRequest> { event ->
    val videoId = event.pathParameters?.get("videoId")
        ?: return@jsonRequestResolver RequestResult.Error(400, "Missing video ID.")
    val quality = event.pathParameters["quality"] ?: "hd"
    RequestResult.Resolved(VideoRequest(videoId, quality))
}
```

JSON parse failures (empty stream, malformed JSON) are caught automatically and returned as HTTP 400. The default `Json` instance uses `ignoreUnknownKeys = true` so you only declare the fields you need.

You can also implement `RequestResolver<R>` directly as a class or `fun interface` lambda if you need more control over the event format.
```

### 3. Implement `StreamSource<R>`

Confirms the resource exists (`head`) and streams it (`streamBody`).

```kotlin
class VideoSource : StreamSource<VideoRequest> {
    override suspend fun head(request: VideoRequest): HeadResult {
        val metadata = db.findVideo(request.videoId, request.quality)
            ?: return HeadResult.NotFound
        return HeadResult.Exists(metadata.sizeBytes)
    }

    override suspend fun streamBody(request: VideoRequest, sink: OutputStream, flush: () -> Unit): Long {
        return storage.openStream(request.videoId, request.quality).use { stream ->
            BoundedBuffer.copy(stream, sink, flush)
        }
    }
}
```

### 4. Wire to `StreamHandler` (your Lambda entry point)

```kotlin
class VideoHandler : RequestStreamHandler {
    private val handler = StreamHandler(
        requestResolver = ::VideoRequestResolver,
        source = ::VideoSource,
    )

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) =
        handler.handleRequest(input, output, context)
}
```

## HTTP status mapping

`StreamHandler` maps every outcome automatically:

| Outcome | HTTP status |
|---|---|
| `RequestResult.Error` | Whatever status you return (e.g. 400) |
| `HeadResult.NotFound` | 404 |
| `HeadResult.Failure` | 502 |
| `HeadResult.Exists` → stream | 200 with `Content-Length` |

Once the 200 metadata + delimiter are written, the status is committed. A mid-stream failure truncates the body but cannot change the status.

## API reference

| Type | Description |
|---|---|
| `jsonRequestResolver<Event, R> { event -> ... }` | Helper: deserializes the Lambda event JSON to `Event`, maps it to `RequestResult<R>` |
| `RequestResolver<out R>` | `fun interface` — implement directly if you need custom event parsing |
| `RequestResult.Resolved<R>` | Carries the typed request to pass to `StreamSource` |
| `RequestResult.Error` | Carries an HTTP status + message; no source request is issued |
| `StreamSource<in R>` | Implement `head(request)` and `streamBody(request, sink, flush)` |
| `HeadResult.Exists(size)` / `.NotFound` / `.Failure(cause)` | Outcomes of `head` |
| `StreamHandler<R>` | Wires resolver + source; implements `RequestStreamHandler` |
| `ResponseWriter` | Writes the metadata JSON + 8-byte delimiter (available directly if needed) |
| `ResponseMetadata` | Data class: `statusCode` + `headers` map |
| `BoundedBuffer.copy(source, sink, flush)` | 1 MB bounded copy utility |
| `LambdaJson` | Lenient `Json` instance (`ignoreUnknownKeys = true`) for decoding Lambda events |

## S3 example

The companion module `streaming-s3-example` is a complete AWS Lambda deployment that streams files from S3 using this library. It includes `FileKeyResolver`, `FileRequest`, and `S3Source` as reference implementations of `RequestResolver` and `StreamSource`.

See [`streaming-s3-example/README.md`](streaming-s3-example/README.md) for deployment instructions.

## License

See LICENSE file.
