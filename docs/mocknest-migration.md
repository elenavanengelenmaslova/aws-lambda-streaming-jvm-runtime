# Plan: Adopt aws-lambda-streaming-core in MockNest Serverless

## Published artifact

```
nl.vintik:aws-lambda-streaming-core:1.0.0
```

Available at: https://central.sonatype.com/artifact/nl.vintik/aws-lambda-streaming-core

---

## What the library provides

| Type | Role |
|---|---|
| `ResponseWriter` | Writes the metadata JSON + 8-byte null delimiter to the Lambda output stream |
| `ResponseMetadata` | `data class` — `statusCode: Int`, `headers: Map<String, String>` |
| `ErrorBody` | `data class` — `message: String` (used internally by `ResponseWriter.writeError`) |
| `copy(source, sink, flush)` | 1 MB bounded copy utility; flushes after every chunk |
| `DELIMITER_LEN` | Constant `8` — length of the null-byte delimiter |

---

## Steps

### 1. Add the dependency

In MockNest Serverless's Gradle build file, add:

```kotlin
implementation("nl.vintik:aws-lambda-streaming-core:1.0.0")
```

The library brings `aws-lambda-java-core`, `kotlinx-serialization-json`, and
`kotlin-logging-jvm` transitively. An SLF4J provider (e.g. `slf4j-simple`) must
be on the runtime classpath — add one if not already present.

---

### 2. Identify hand-rolled protocol code to replace

Look for any code in MockNest that:

- Builds the metadata JSON manually (statusCode + headers as a JSON string)
- Writes null bytes as a delimiter (8 × `\0`)
- Copies S3/body bytes to the Lambda output stream in a loop

These are the three things `ResponseWriter` and `copy()` replace.

---

### 3. Replace with library calls

**Before (hand-rolled):**
```kotlin
val json = """{"statusCode":200,"headers":{"Content-Type":"application/octet-stream"}}"""
output.write(json.toByteArray())
output.write(ByteArray(8))  // delimiter
// ... copy loop
```

**After (library):**
```kotlin
val writer = ResponseWriter()
writer.writeMetadata(output, ResponseMetadata(
    statusCode = 200,
    headers = mapOf("Content-Type" to "application/octet-stream"),
))
copy(sourceStream, output) { output.flush() }
output.flush()
```

For error responses:
```kotlin
writer.writeError(output, 400, "Bad request.")
```

---

### 4. Stream lifecycle

The handler must:
- Wrap the entire `handleRequest` body in `output.use { }` so `close()` is called on every exit path
- Call `output.flush()` explicitly before `use { }` closes — avoids a race condition in the Lambda streaming runtime where the last ~200 KB may not be delivered on fast warm invocations

```kotlin
override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
    output.use {
        // ... resolve, validate, stream ...
        output.flush()
    }
}
```

---

### 5. Remove hand-rolled code

Delete the existing protocol-encoding code that is now covered by the library.
Keep MockNest's own routing, SSE, dribble, and any other delivery logic —
the library only handles the wire format (metadata JSON + delimiter + body copy).

---

### 6. Test

- Run MockNest's existing unit tests — no behavioral change expected
- Deploy to a dev environment and run a streaming request end-to-end
- Verify first-byte arrives before completion (progressive delivery, not buffered)
- Verify body is byte-identical to the source

---

## Notes

- `ResponseMetadata.headers` is `Map<String, String>` — not `Map<String, List<String>>`.
  API Gateway streaming requires plain string values, not arrays.
- MockNest's routing/SSE/dribble logic stays in MockNest — the library has no opinion
  about request routing or how bytes are chunked to the client.
- The library does not depend on the AWS SDK; S3 or any other source is handled
  by MockNest's own code.
