# Development Log

A running log of hiccups, bugs, gotchas, and fixes encountered while building the
S3 file-streaming endpoint. Each entry feeds the final guide in `docs/article.md`.

> **No runtime secrets.** This log never contains credentials, access tokens,
> authorization header values, or resource identifiers used as secrets. Where a
> development detail would otherwise expose such a value, it is recorded by a
> descriptive name or placeholder (e.g. `<BUCKET_NAME>`, `<API_ENDPOINT>`).

Each entry uses the form:

- **Title** — a short summary of the issue.
- **Symptom / trigger** — what was observed and what caused it.
- **Resolution / status** — the fix, or the current state if still open.

---

## `STREAM` vs `RESPONSE_STREAM` — the SAM/integration naming trap

- **Symptom / trigger:** Enabling Lambda response streaming through API Gateway
  hinges on a transfer-mode value, and there are two different names for two
  different layers. At the SAM `Api` event level the property is
  `ResponseTransferMode: RESPONSE_STREAM`. At the lower-level API Gateway
  integration the equivalent value is `STREAM`, and that path additionally
  requires the integration URI to carry the `/response-streaming-invocations`
  suffix. The two names are **not interchangeable**: using `STREAM` where SAM
  expects `RESPONSE_STREAM` (or vice versa), or setting the right name at the
  wrong layer, silently produces a non-streaming (buffered) endpoint. The failure
  doesn't surface until a deploy completes and the response comes back buffered —
  a full, slow deploy cycle wasted each time.

- **Resolution / status:** **Resolved (convention fixed).** In the SAM template,
  set `ResponseTransferMode` on the `Api` event to the exact value
  `RESPONSE_STREAM`. Treat any other value — including the lower-level integration
  value `STREAM` — as invalid for response streaming, and do not commit it
  (Req 8.2, 8.3). After every template change, run
  `sam validate --template-file deployment/aws/sam/template.yaml` and require
  exit code 0 before committing (Req 8.4, 8.5). Only the `RESPONSE_STREAM` form is
  used at the SAM `Api` level for this project; the `STREAM` + integration-URI
  suffix form is documented here only so the distinction is unmistakable.

---

## Open item: how the content-type marker is conveyed on the JVM `RequestStreamHandler` path

- **Symptom / trigger:** The Node.js streaming helper
  `awslambda.HttpResponseStream.from(underlyingStream, prelude)` marks the stream
  as carrying the metadata-prelude protocol by calling
  `underlyingStream.setContentType("application/vnd.awslambda.http-integration-response")`.
  On the JVM, the `RequestStreamHandler` path hands the handler a **raw
  `OutputStream`** that has no `setContentType` method, while API Gateway invokes
  the function with `ResponseTransferMode: RESPONSE_STREAM`. It is currently a
  **known unknown** whether that content-type marker still needs to be conveyed on
  this path, and if so, how. Two possibilities: (a) the streaming invocation path
  applies the marker automatically and the handler only needs to write
  `metadata JSON → 8 null bytes → body`; or (b) the marker must be emitted some
  other way (e.g. as a header inside the metadata prelude). Getting this wrong
  risks the prelude being treated as body, or headers/status not being honored.

- **Resolution / status:** **OPEN — to be verified against a real deployment.**
  The wire format itself is settled and ported from the Node.js helper: write the
  metadata JSON document, then exactly 8 null bytes (`ByteArray(8)`) as the
  delimiter, then the body bytes, with no NUL bytes inside the prelude JSON. What
  remains unverified is the content-type marker on the JVM streaming path. The
  proving ground is the post-deploy first-byte check (Req 9.3) against the deployed
  `<API_ENDPOINT>`: if the first byte arrives well before completion and the body
  is byte-identical, progressive streaming is confirmed and the marker is being
  handled correctly by whichever mechanism applies. Once verified, update this
  entry with the concrete finding (automatic vs. explicit, and the exact mechanism).
  Until then, treat this as the most likely deploy-cycle trap after the
  `STREAM` vs `RESPONSE_STREAM` distinction above.

---

## Build checkpoint: bleeding-edge toolchain (Gradle 9.0.0 + Kotlin 2.3.0 + Java 25) configures and builds clean

- **Symptom / trigger:** Task 1.2 checkpoint — first `./gradlew clean test` after the
  single-module project setup. The concern was that the combination of Gradle
  `9.0.0`, the Kotlin `2.3.0` plugin (with `kotlin("plugin.serialization")`),
  `com.gradleup.shadow` `9.0.2`, Kover `0.9.1`, the Java 25 toolchain
  (`JavaLanguageVersion.of(25)` / `jvmTarget = JVM_25`), and the declared
  dependency set might hit a version-resolution or plugin-compatibility wall, since
  this is a very new stack.

- **Resolution / status:** **Resolved — no changes needed.** `./gradlew clean test`
  returns exit code 0: the build configures, all dependencies resolve from Maven
  Central, and main sources compile. A `./gradlew compileKotlin --rerun-tasks`
  (cache-bypassing) run also succeeds, confirming the resolve+compile path is clean
  and not just a cache hit. `:test` reports `NO-SOURCE` (expected — no tests exist
  yet at this checkpoint). The foojay toolchain resolver in `settings.gradle.kts`
  provisions Java 25 automatically.
  - **Gotcha to be aware of (non-blocking):** Gradle 9 on a modern JDK emits
    `WARNING: A restricted method in java.lang.System has been called ... Restricted
    methods will be blocked in a future release unless native access is enabled`
    from `native-platform`. It is a warning only and does not affect the build; a
    future Gradle/JDK pairing may require `--enable-native-access=ALL-UNNAMED`.

---

## MockK can't mock final Kotlin classes on Java 25 — `net.bytebuddy.experimental`

- **Symptom / trigger:** `StreamHandlerTest` mocks the handler's concrete
  collaborators (`RequestParser`, `FileNameValidator`, `S3Source`,
  `ResponseWriter`). Every test failed with
  `io.mockk.MockKException: Missing mocked calls inside every { ... } block`.
  MockK's `every`/`coEvery` recorded zero calls because the mocks weren't
  intercepting. Tests that mock *interfaces* (`S3Client`, `Context`) were fine —
  only final concrete classes broke. Cause: MockK instruments final classes via
  byte-buddy class redefinition, and the byte-buddy bundled with MockK 1.14.5
  refuses the Java 25 class-file version unless experimental mode is enabled.
- **Resolution / status:** Resolved. Added
  `systemProperty("net.bytebuddy.experimental", "true")` to `tasks.test` in
  `build.gradle.kts`, so byte-buddy accepts the newer class-file version and
  mocking of final classes works on the Java 25 toolchain.

---

## JUnit 6 rejects Kotlin lifecycle methods that return a value (expression bodies)

- **Symptom / trigger:** During the final `./gradlew clean test` checkpoint, test
  discovery failed with `DiscoveryIssueException` — two **critical** issues against
  `S3StreamingSub6MbIntegrationTest`:
  - `@BeforeAll method 'startContainer()' must not return a value`
  - `@AfterEach method 'cleanObjects()' must not return a value`
  JUnit Jupiter 6 enforces that lifecycle methods (`@BeforeAll`/`@AfterAll`/
  `@BeforeEach`/`@AfterEach`) and `@Test` methods return `void`/`Unit`. The two
  methods used Kotlin **expression bodies** (`fun startContainer() = runBlocking { ... }`).
  The last expression of each block was non-`Unit`: `startContainer` ended with
  `s3.createBucket { ... }` (returns `CreateBucketResponse`), and `cleanObjects`
  ended with `listed.contents?.forEach { ... }` (returns `Unit?` because of the safe
  call). Kotlin compiled those into JVM methods returning `CreateBucketResponse` /
  `kotlin.Unit`, which JUnit 6 rejects. (Sibling tests that used **block bodies**
  — `fun startContainer() { ... }` — were fine, which is why only one file failed.)

- **Resolution / status:** **Resolved.** Convert lifecycle methods to **block bodies**
  so the JVM return type is `void`: wrap the `runBlocking { ... }` in `{ }` rather than
  using `= runBlocking { ... }`. Rule of thumb for this stack: never use an
  expression body for a `@BeforeAll/@AfterAll/@BeforeEach/@AfterEach/@Test` method —
  a trailing builder/`forEach`/safe-call silently makes the method return a value.

- **Non-blocking note (same run):** JUnit also logged two **non-critical** warnings
  that the `@Tag("Feature: ... , Property N: ...")` strings have *invalid tag syntax*
  (commas/colons are reserved), so those tags are **ignored** for tag-based filtering.
  This does not fail the build; the property tests still run. If tag-based selection
  is ever needed, switch to a syntactically valid tag and keep the descriptive
  property string in the test name / a comment.

---

## Coverage gate: covering the write-failure propagation branch in `ResponseWriter`

- **Symptom / trigger:** `./gradlew koverVerify` failed at the final gate —
  `lines covered percentage is 89.673900, but expected minimum is 90` (165/184 lines).
  The Kover XML report showed the only easily-closable production gap was
  `ResponseWriter` at 88.89%: the two `.onFailure { logger.error(...) }.getOrThrow()`
  branches in `writeMetadata`/`writeError` (the write-failure propagation paths,
  Req 4.6) were never exercised. The larger uncovered blocks (`PrimingContext` /
  `PrimingLogger`, 13 lines) are `private object` no-op stubs unreachable from tests
  without changing production visibility, and the handler never reads `Context`, so
  they were left as-is.

- **Resolution / status:** **Resolved without changing production behavior.** Added
  two unit tests to `ResponseWriterTest` using a `FailingOutputStream` (throws
  `IOException` on every `write`) to assert both `writeMetadata` and `writeError`
  propagate the failure rather than swallowing it. Line coverage rose to
  **91.30% (168/184)** and `koverVerify` passes. The 90% threshold was **not**
  weakened.

---

## Streaming protocol metadata: headers must be `Map<String, String>`, not `Map<String, List<String>>`

- **Symptom / trigger:** Lambda completes successfully (12s, 209 MB, no errors in
  CloudWatch) but API Gateway returns HTTP 502 `{"message": "Internal server error"}`.
  The streaming integration is correctly configured (`responseTransferMode: STREAM`,
  URI suffix `/response-streaming-invocations`), and the Lambda writes the metadata
  JSON + 8 null-byte delimiter + body to the `OutputStream`. The issue only surfaces
  against a deployed API Gateway; the LocalStack integration tests pass because
  LocalStack doesn't enforce the metadata prelude format.

- **Root cause:** The metadata JSON prelude's `headers` field was serialized as
  `Map<String, List<String>>` (JSON arrays for header values):
  ```json
  {"statusCode":200,"headers":{"Content-Type":["application/octet-stream"],"Content-Length":["12582912"]}}
  ```
  API Gateway's streaming protocol parser expects `Map<String, String>` (plain string
  values, not arrays):
  ```json
  {"statusCode":200,"headers":{"Content-Type":"application/octet-stream","Content-Length":"12582912"}}
  ```
  The array format is syntactically valid JSON but not recognized as a valid streaming
  metadata prelude by API Gateway, which then returns 502 because it cannot extract the
  HTTP status code and headers from the Lambda output.

- **Resolution / status:** **Resolved.** Changed `ResponseMetadata.headers` from
  `Map<String, List<String>>` to `Map<String, String>`. For repeatable headers (e.g.
  `Set-Cookie`), the API Gateway streaming format provides a separate `cookies` array
  field — it does not use JSON arrays inside the `headers` map. The proven working format
  (matching the MockNest implementation) is:
  ```kotlin
  @Serializable
  data class ResponseMetadata(
      val statusCode: Int,
      val headers: Map<String, String>,
  )
  ```
  This is the single most consequential gotcha for porting the Node.js streaming helper
  to the JVM: the metadata prelude format is implicitly documented through the Node.js
  helper's behavior but never spelled out for other runtimes.
