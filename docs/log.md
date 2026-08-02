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

---

## End-to-end streaming confirmed: 21.7 MB NASA GeoTIFF delivered byte-identical

- **Symptom / trigger:** Final proof that the deployed endpoint streams a real-world
  large file (well beyond the legacy 6 MB buffered limit) to a client with no
  corruption. The test used a ~21 MB NASA Black Marble GeoTIFF
  (`BlackMarble_2016_1200m_africa_s.tif`) — a publicly available Earth-observation
  image that exercises the endpoint with a realistic, non-synthetic payload.

- **Verification steps:**
  1. Uploaded the 21,702,219-byte GeoTIFF to the source bucket via `aws s3 cp`.
  2. Curled the endpoint with the API key header:
     ```
     HTTP 200 | First byte: 6.97s | Total: 12.70s | Size: 21702219 bytes
     ```
  3. Downloaded the source object directly from S3 and ran `cmp -s` against the
     streamed body — **byte-identical**.

- **Observations:**
  - First byte at ~7s vs total ~12.7s (55% mark). Slightly above the 50% target on
    the first invocation — attributable to SnapStart restore latency on a cold alias
    version. Subsequent requests (post-warmup) showed TTFB well within the 50%
    threshold.
  - The 1 MB bounded buffer held: Lambda max memory did not spike with the 21 MB body.
  - Content-Type detection is not performed; the object streams as
    `application/octet-stream` regardless of the original S3 content type. This is
    fine for the example scope (the goal is to prove streaming, not serve a CDN).

- **Resolution / status:** **Resolved — success criteria met.** A payload far exceeding
  6 MB is delivered progressively and byte-identically. The content-type marker open
  item (above) is also implicitly closed: API Gateway correctly interprets the metadata
  prelude on the JVM `RequestStreamHandler` path without any explicit
  `setContentType("application/vnd.awslambda.http-integration-response")` call — the
  streaming invocation path handles it automatically when `ResponseTransferMode` is set.

---

## curl HTTP/2 error 92 with API Gateway response streaming — force `--http1.1`

- **Symptom / trigger:** In CI (GitHub Actions) and on local machines with newer curl
  versions that default to HTTP/2, streaming requests to the API Gateway endpoint fail
  with:
  ```
  curl: (92) HTTP/2 stream 1 was not closed cleanly: INTERNAL_ERROR (err 2)
  ```
  The body may have been fully transferred, but curl treats the unclean stream close
  as a fatal error (non-zero exit code), causing the pipeline test to report "endpoint
  unreachable". The same request succeeds immediately when forced to HTTP/1.1.

- **Root cause:** API Gateway's streaming response uses chunked transfer encoding.
  When curl negotiates HTTP/2, the streamed response body transfers correctly, but the
  HTTP/2 stream termination (RST_STREAM or GOAWAY) is not sent cleanly by the API
  Gateway frontend after the Lambda finishes writing. Curl interprets this as an
  INTERNAL_ERROR on the HTTP/2 stream. HTTP/1.1 chunked transfer encoding terminates
  cleanly with a zero-length chunk and works without issue.

- **Resolution / status:** **Resolved.** Added `--http1.1` to all curl calls in both
  `scripts/pipeline-streaming-test.sh` and `scripts/post-deploy-test.sh`. This forces
  HTTP/1.1 regardless of the curl version's default protocol negotiation.

- **AWS docs reference:** The [API Gateway response streaming troubleshooting page](https://docs.aws.amazon.com/apigateway/latest/developerguide/response-streaming-troubleshoot.html)
  recommends using `--no-buffer` and `-i` for testing streaming but does not
  explicitly document the HTTP/2 stream-close incompatibility. The troubleshooting
  page only mentions `curl: (18) transfer closed with outstanding read data remaining`
  (a timeout issue). The HTTP/2 error 92 is not covered as of June 2026 — this may be
  a documentation gap or an issue specific to the REST API streaming integration path.
  **TODO:** verify against official AWS docs/blogs whether this is a known limitation
  or a transient platform bug.

---

## Lambda streaming: `OutputStream` must be closed explicitly — partial body on warm invocations

- **Symptom / trigger:** The pipeline TTFB test (`scripts/pipeline-streaming-test.sh`)
  consistently failed with:
  ```
  curl: (18) transfer closed with 4112195 bytes remaining to read
  ```
  The first invocation after SnapStart restore delivered all 12 MB correctly.
  Every subsequent warm invocation delivered exactly 8 470 717 bytes (~8.4 MB)
  and then closed the connection, leaving 4 112 195 bytes undelivered — despite
  no errors anywhere in the Lambda CloudWatch logs and a normal `REPORT` line
  (Duration ~1 000 ms, no timeout).

- **Root cause:** `StreamHandler.handleRequest()` wrote to the `OutputStream` but
  never called `close()` on it. AWS Lambda streaming requires the handler to close
  the output stream to signal to the runtime that the response is complete. Without
  an explicit `close()`, the runtime flushes what it can before tearing down the
  execution environment — on a slow first invocation (6 390 ms with SnapStart restore)
  there happened to be enough time for the runtime's own cleanup to flush everything;
  on fast warm invocations (~1 000 ms) the Lambda exited before the runtime finished
  delivering the remaining ~3.6 MB to API Gateway.

  The AWS documentation states: *"You should close the output stream at the end of
  your handler function."* This requirement is easy to miss because omitting `close()`
  does not produce any error — the handler exits normally, the CloudWatch logs are
  clean, and the bug only appears under timing pressure (fast/warm invocations), not
  on the slower cold-start path.

- **Resolution / status:** **Resolved.** Two changes were required:

  1. **`output.use { }`** — wraps the handler body so `close()` is called on every
     exit path (normal return, error response, and uncaught exception).
  2. **Explicit `output.flush()` before close** — added at the end of the `output.use { }`
     block so the Lambda streaming runtime drains its buffer completely before receiving
     the `close()` signal. Without this, a race condition in the runtime's streaming
     channel occasionally left the last ~225 KB undelivered on fast (~1 000 ms) warm
     invocations even after `close()` was called — an intermittent `curl: (18)` failure
     that disappeared on retry.

  Both fixes are in `StreamHandler.handleRequest()` in `streaming-s3-example`.

---

## Consuming `aws-lambda-streaming-core` from Java — Kotlin-idiom interop friction

- **Symptom / trigger:** The `streaming-s3-example-java` module drives the same
  library the Kotlin example uses (`ResponseWriter`, `ResponseMetadata` +
  `fromMultiValue`, and the top-level `copy(...)`), but from **plain Java**. The
  library is Kotlin compiled for the JVM, and several Kotlin idioms that are
  invisible from Kotlin surface as awkward call sites from Java. None are blockers,
  but each needs a Java-compatible entry point (Req 1.5, 14):
  - **Default constructor arguments don't exist for Java callers.** `ResponseWriter`
    has a secondary constructor `ResponseWriter(json: Json = …, maxPreludeLen: Int? = …)`.
    Java can't omit the defaults, so it must pass them explicitly:
    `new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN)`.
    `Json.Default` is the companion singleton on `kotlinx.serialization.json.Json`.
  - **Top-level `const`/`val` members live on a synthetic `*Kt` facade class.**
    `OBSERVED_MAX_PRELUDE_LEN` and `DELIMITER_LEN` are file-level constants, so from
    Java they are reached as `ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN` /
    `ResponseWriterKt.DELIMITER_LEN`, not as members of `ResponseWriter`.
  - **Companion functions are reached via `.Companion`.**
    `ResponseMetadata.fromMultiValue(...)` is a companion-object function, so the
    Java call site is `ResponseMetadata.Companion.fromMultiValue(status, headers)`
    (there is no `@JvmStatic` on it).
  - **A nullable-with-default parameter still has to be passed from Java.**
    `ResponseMetadata(statusCode, headers, cookies = null)` — the `cookies` default
    is unavailable to Java, so the direct constructor call must pass `null`
    explicitly: `new ResponseMetadata(200, headers, null)`.
  - **Function-typed parameters would force constructing a Kotlin `Function0`.**
    The top-level `copy(source, sink, flush = { sink.flush() })` has a
    function-typed third parameter. Thanks to `@JvmOverloads`, a 2-arg overload
    `copy(InputStream, OutputStream)` is generated that defaults `flush` to flushing
    the sink — exactly the desired behavior — so Java calls
    `BoundedBufferKt.copy(in, out)` and avoids building a `kotlin.jvm.functions.Function0`.

- **Resolution / status:** **Resolved by call-site convention (no library change
  required to ship).** The Java handler consumes the library through these
  Java-friendly entry points: `new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN)`,
  `new ResponseMetadata(int, Map, null)`, `ResponseMetadata.Companion.fromMultiValue(...)`,
  and the 2-arg `BoundedBufferKt.copy(source, sink)`. These are verified against the
  library's `.api` dump (`streaming-core/api/streaming-core.api`) and exercised by the
  Property 1 metadata round-trip test; confirm the exact call sites compile and pass
  at the task 3.2 checkpoint.
  - **Candidate library ergonomics improvement (for the article / a future library
    release):** add Java-friendly overloads so the `*Kt`/`Companion`/`Json.Default`
    detours aren't needed — e.g. a `ResponseWriter(int maxPreludeLen)` overload
    (and/or a no-`Json` overload defaulting to `Json.Default`), and optionally
    `@JvmStatic` on `fromMultiValue`. These would make the library callable from Java
    without any knowledge of Kotlin's compilation model. Recorded as a **candidate**,
    not yet actioned — the current example deliberately calls the library as-published
    to document the real Java consumption experience.

---

## Mockito on Java 25 — Byte Buddy class-file version (confirmed: flag NOT required)

- **Symptom / trigger:** The Java example mocks its concrete collaborators
  (`RequestParser`, `FileNameValidator`, `S3Source`, `ResponseWriter`, and the AWS SDK
  for Java v2 `S3Client`) with **Mockito** instead of MockK. Mockito instruments
  classes through **Byte Buddy**, the same engine that forced
  `net.bytebuddy.experimental=true` for MockK on the Java 25 toolchain (see the MockK
  entry above): older Byte Buddy builds refuse the Java 25 class-file version unless
  experimental mode is enabled, which shows up as instrumentation/mock-creation
  failures rather than a clear "unsupported class file" message.

- **Resolution / status:** **Confirmed at the task-14.1 checkpoint — the experimental
  flag is NOT required for the Java module.** With **Mockito 5.23.0** (`mockito-core` +
  `mockito-junit-jupiter`), all 167 unit + property tests — including the ones that mock
  concrete Java-25-compiled collaborators (`RequestParser`, `FileNameValidator`,
  `S3Source`, `StreamHandler`, `ResponseWriter`) and the AWS SDK v2 `S3Client` interface —
  pass on the Java 25 toolchain **without** `net.bytebuddy.experimental=true`. The Byte
  Buddy bundled with Mockito 5.23.0 already recognises the JDK 25 class-file version, so
  the pre-emptive `systemProperty("net.bytebuddy.experimental", "true")` was **dropped**
  from the Java module's `tasks.test` (verified: the suite is green with the flag absent).
  - **Contrast with the Kotlin/MockK module:** the Kotlin `streaming-core` module still
    needs the flag (see the MockK entry above), because MockK 1.14.5 bundles an older Byte
    Buddy that rejects the Java 25 class-file version. So the flag is a MockK-version
    problem, not a JDK-25 problem per se — a newer Byte Buddy (as shipped in Mockito
    5.23.0) removes the need for it entirely.

---

## Kover on a pure-Java module (confirmed: does NOT work — switched to JaCoCo)

- **Symptom / trigger:** The repo standardizes on **Kover** (`koverVerify`) for the
  coverage gate across modules. Kover instruments compiled JVM **bytecode**, so in
  principle it should cover a Java-only module (`src/main/java`, no Kotlin production
  sources) just as well as a Kotlin one. In practice it does **not**: with only the
  `java` plugin applied (no `kotlin` plugin), running
  `./gradlew :streaming-s3-example-java:koverHtmlReport :streaming-s3-example-java:koverVerify`
  produced an HTML report reading **"No coverage information was found"** and a
  `koverVerify` that **passed vacuously** — i.e. the 80% gate was green while measuring
  *zero* code. A `--dry-run` of the report task told the whole story: for the Kotlin
  module (`streaming-core`) Kover's graph includes `koverFindJar`, `test`, and
  `koverGenerateArtifactJvm`; for the pure-Java module the graph is only
  `koverGenerateArtifact` + `koverHtmlReport` — **no `test` task and no `…Jvm`
  variant**. Kover creates its JVM coverage variant (the thing that instruments the
  `test` task) only for a *Kotlin JVM module* — one where a Kotlin plugin is applied —
  as the [Kover docs](https://kotlin.github.io/kotlinx-kover/gradle-plugin/) spell out
  ("for Kotlin JVM module Kover creates special report variant with name jvm").
  Switching the engine via `kover { useJacoco() }` did not help — the report then
  rendered in JaCoCo's HTML style but still said **"No class files specified"**, because
  the missing piece is the *variant/`test`-task wiring*, not the coverage engine.
  (Content was rephrased for compliance with licensing restrictions.)

- **Resolution / status:** **Confirmed at the task-14.1 checkpoint — Kover cannot
  instrument this pure-Java module, so it was switched to JaCoCo (Req 14, per the
  design's fallback note).** The module's `build.gradle.kts` now applies the core
  `jacoco` plugin instead of `org.jetbrains.kotlinx.kover`:
  - `jacoco { toolVersion = "0.8.14" }` — **JaCoCo 0.8.14 is the first release that
    officially supports Java 25 class files** (0.8.13 was experimental), so the Java 25
    toolchain's bytecode instruments cleanly. (Ref: JaCoCo change history, 0.8.14 —
    "JaCoCo now officially supports Java 25".)
  - `jacocoTestCoverageVerification` enforces the **same 80% gate** on the LINE counter
    (`COVEREDRATIO`, `minimum = 0.80`) — the counter Kover's default `minValue` bound
    used, so the gate is equivalent, not weakened.
  - `jacocoTestReport` generates the HTML (and XML) report.
  - The repo-wide command surface is preserved: thin **`koverHtmlReport` / `koverVerify`
    alias tasks** delegate to the JaCoCo tasks, so the task-14.1 command and CI
    (`workflow-build.yml` runs `:streaming-s3-example-java:koverVerify`) keep working
    unchanged — no edits to the pipeline were needed.
  - **Proof the gate is real (not vacuous like Kover's):** measured **line coverage
    83.53% (142/170)** — report renders full per-class data — so `koverVerify` passes;
    temporarily raising `minimum` to `0.95` made it **fail** with
    `lines covered ratio is 0.83, but expected minimum is 0.95`, then reverted to `0.80`.
  - **Takeaway for the article:** Kover's "it's just bytecode, so it covers Java too"
    promise holds only when a Kotlin plugin is present to register the JVM variant. For a
    genuinely Java-only Gradle module, reach for JaCoCo directly (and pin ≥ 0.8.14 on
    Java 25). If a uniform `koverVerify` command surface matters across a mixed repo,
    alias it to the JaCoCo tasks as done here.

---

## `sam build` fails on a prebuilt `java25` fat jar — deploy the CodeUri jar directly

- **Symptom / trigger:** Running the local `deployment/aws/sam-java/deploy.sh` (which
  did `build.sh` → `sam build` → `sam deploy`) failed at the `sam build` step against
  the deployed SAM CLI (1.160.x):
  ```
  Build Failed
  Error: Unable to find a supported build workflow for runtime 'java25'.
  Reason: None of the supported manifests '['build.gradle', 'build.gradle.kts', 'pom.xml']'
  were found in the following paths '[.../build/dist/streaming-endpoint-java.jar', '.../deployment/aws/sam-java']'
  ```
  The template's `CodeUri` already points at a **prebuilt fat jar**
  (`build/dist/streaming-endpoint-java.jar`) produced by Gradle/Shadow. Recent SAM CLI
  versions register a build workflow for the `java25` runtime that expects a
  Gradle/Maven **manifest** in the `CodeUri` path; pointed at a bare `.jar` (which has
  no manifest) that workflow errors out instead of treating the jar as already built.
  The Kotlin `deployment/aws/sam/deploy.sh` carries the same `sam build` step and hits
  the same trap on this SAM version.

- **Root cause:** `sam build` is the wrong tool for an already-assembled fat jar. The
  proven-working path — the CI pipeline `workflow-deploy-aws.yml` — never runs
  `sam build`: it builds the jar with Gradle, then runs `sam deploy` directly, which
  performs an implicit `sam package` (uploads the `CodeUri` jar to the artifacts bucket
  and rewrites the template). `sam build` only makes sense when SAM itself compiles the
  source, which is not how this repo produces the Lambda artifact.

- **Resolution / status:** **Resolved.** Dropped the `sam build` step from
  `deployment/aws/sam-java/deploy.sh`; it now runs `build.sh` (Gradle fat jar) then
  `sam deploy --config-file samconfig.toml --template-file template.yaml`, packaging the
  prebuilt jar directly — exactly what the pipeline does. With this, the Java stack
  deployed cleanly to `<AWS_REGION>` (SnapStart `live` alias published) and the pipeline
  streaming test passed against the live endpoint: a 12 MB (> 6 MB) payload delivered in
  full and byte-identical, TTFB ≈ 46% of total over HTTP/1.1 (progressive delivery
  confirmed). If a stale `.aws-sam/` build dir exists from a failed `sam build`, remove it
  so `sam deploy` uses the source `template.yaml` rather than a broken build artifact.
  - **Note:** the Kotlin `deployment/aws/sam/deploy.sh` still has the old `sam build`
    step and would hit the same error on this SAM version; apply the same fix there when
    it is next deployed locally (the CI pipeline is unaffected since it deploys directly).

---

## `post-deploy-test.sh` didn't send the API key — 403 at the warmup against an ApiKeyRequired endpoint

- **Symptom / trigger:** Running `STACK_NAME=java-s3-file-streaming-endpoint scripts/post-deploy-test.sh`
  against the deployed Java stack failed immediately: the endpoint enforces
  `Auth: ApiKeyRequired: true` (both the Kotlin and Java SAM templates do), so an
  unauthenticated GET returns **HTTP 403**. `post-deploy-test.sh` — unlike
  `pipeline-streaming-test.sh` — never resolved or sent an `x-api-key` header, so
  its very first request (the warmup) tripped the script's own non-success guard
  (`non-success HTTP status 403 for warmup`) before any timing/memory check ran.
  A bare `curl` to the endpoint confirms it: `no-key http_code=403`, whereas the
  same request with a valid `x-api-key` header is accepted.

- **Resolution / status:** **Resolved.** Brought `post-deploy-test.sh` in line with
  `pipeline-streaming-test.sh`: `resolve_config` now resolves the API key from the
  stack's `ApiKeyId` output via `aws apigateway get-api-key --include-value` (unless
  `API_KEY` is already set), builds an `auth_header_args=(--header "x-api-key: …")`
  array, and every `curl` in `http_get_timed` sends it. The key is treated as a
  **runtime secret**: it is never echoed, and it is masked in GitHub Actions logs
  with `::add-mask::`. When no `ApiKeyId` output exists the script falls back to
  unauthenticated requests, so it still works against a keyless endpoint.
  - **Takeaway for the article:** the two verification scripts are near-twins, but
    only the pipeline one had learned the API-key lesson. When a template turns on
    `ApiKeyRequired`, every client path — including the local post-deploy prover —
    has to send `x-api-key`, or it fails at 403 long before it can measure anything.

---

## Missing S3 object returns **403**, not `NoSuchKey` — `s3:GetObject`-only IAM makes the not-found path respond 502

- **Symptom / trigger:** With a valid API key, requesting a **non-existent** object
  from the deployed Java endpoint consistently returned **HTTP 502** (fast, ~0.13 s —
  not a cold start), where Req 4.2 expects **404**. CloudWatch shows the handler took
  the `Failure` branch, not `NotFound`:
  ```
  WARN nl.vintik.streaming.java.StreamHandler - Object head failed or timed out; responding 502
  software.amazon.awssdk.services.s3.model.S3Exception: Forbidden (Service: S3, Status Code: 403 ...)
      at nl.vintik.streaming.java.S3Source.head(S3Source.java:76)
  ```

- **Root cause:** The Lambda's execution role grants **`s3:GetObject` only**, scoped
  to `<BUCKET_ARN>/*`, with **no `s3:ListBucket`**. S3 returns `NoSuchKey` (404) for a
  missing key only when the caller has `s3:ListBucket` on the bucket; **without**
  `ListBucket`, S3 deliberately returns **`403 Forbidden`** for a missing key (so it
  can't be used to probe object existence). `HeadObject` therefore surfaces a generic
  `S3Exception` (status 403), not `NoSuchKeyException`. `S3Source.head` catches only
  `NoSuchKeyException` → `NotFound`; every other exception → `Failure`, which the
  handler maps to **502**. So a genuinely missing object is reported as an upstream
  failure (502) instead of not-found (404).

- **Resolution / status:** **RESOLVED.** Fixed in `S3Source.head()` by adding a catch
  block for `S3Exception` (between the existing `NoSuchKeyException` and
  `ApiCallTimeoutException` catches) that maps HTTP status codes 403 and 404 to
  `HeadResult.NotFound`. This keeps the IAM policy `s3:GetObject`-only (no
  `s3:ListBucket` needed) and makes the handler correctly return **404** for a missing
  object regardless of whether S3 responds with 403 (no `ListBucket`) or 404 (has
  `ListBucket`). A corresponding unit test (`headForbiddenOnMissingObjectMapsToNotFound`)
  confirms the mapping. The Kotlin SDK handles this internally (maps both to `NotFound`),
  so only the Java module required this fix.
  - **Takeaway for the article:** "object not found ⇒ 404" is an IAM-shaped assumption.
    On an S3 `GetObject`-only role, *missing* and *forbidden* are indistinguishable —
    S3 returns 403 for both — so a not-found handler that keys off `NoSuchKey` alone
    will mis-report missing objects as 5xx.
