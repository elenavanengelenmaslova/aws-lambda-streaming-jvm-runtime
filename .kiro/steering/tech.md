# Tech

> The repo is documentation-only today. This describes the intended stack and the standards to build it with. Versions track MockNest; bump together and keep `build.gradle.kts` as the source of truth.

## Stack

- **Kotlin 2.3.x** targeting **JVM / Java 25** (Gradle toolchain `JavaLanguageVersion.of(25)`, `jvmTarget = JVM_25`).
- **Gradle 9.x** with the Kotlin DSL. **Single module** (no clean-architecture split — this is an example).
- **Shadow plugin** (`com.gradleup.shadow`) to build the fat jar deployed to Lambda.
- **Kover** (`org.jetbrains.kotlinx.kover`) for coverage, 90% threshold (`koverVerify`).

## Libraries

- **aws-lambda-java-core** `1.4.0` — `RequestStreamHandler`, `Context`.
- **aws-lambda-java-events** `3.16.1` — `APIGatewayProxyRequestEvent` shape (request is parsed manually from the `InputStream`).
- **Kotlin AWS SDK** (`aws.sdk.kotlin:s3`) — NOT the Java SDK. Use it for all AWS interactions (S3 streaming source).
- **kotlinx-serialization-json** — response metadata JSON and any JSON handling. Preferred over Jackson.
- **kotlin-logging-jvm** (`io.github.oshai`) — logging.
- DI - use kotlin delegates

## Testing

- **JUnit 6** (`org.junit.jupiter:junit-jupiter` 6.x, `useJUnitPlatform()`).
- **MockK** for mocking; **kotlinx-coroutines-test** for coroutine tests.
- **TestContainers + LocalStack** for S3 integration tests (real Kotlin AWS SDK calls against a containerized S3). Use `Wait.forHttp("/_localstack/health").forStatusCode(200)` as readiness; share one container across the class via `@BeforeAll`/`@AfterAll`, clean only data between tests.

### Container runtime: Colima (not Docker Desktop)

This project runs on **Colima** — there is **no Docker Desktop**. TestContainers talks to the Colima-provided Docker socket. Do **not** assume Docker Desktop, and do not invoke `docker` directly in build/test code; let TestContainers manage containers.

- **Start the runtime first:** `colima start` (TestContainers fails fast if the daemon is down). Verify with `docker context ls` / `docker info`.
- **Point TestContainers at Colima's socket** via environment (e.g. in `~/.zshrc` or the test run config):
  ```bash
  export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
  ```
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` is required so Ryuk (the TestContainers reaper) mounts the correct socket path inside the VM.
- **If Ryuk misbehaves** on Colima, set `TESTCONTAINERS_RYUK_DISABLED=true` as a last resort (containers are then cleaned up by the shared `@AfterAll` rather than the reaper).
- **CI/automation:** the same env vars apply; ensure `colima start` runs before the Gradle test task. Never call `docker` CLI commands from Gradle, scripts, or test setup — rely on TestContainers and the configured socket.

## Cold-Start Optimization (match MockNest)

Every Lambda in the SAM template MUST have all of:

1. **SnapStart** — `AutoPublishAlias: live` + `SnapStart: ApplyOn: PublishedVersions`.
2. **Priming** — a CRaC warm-up hook that exercises the critical path (S3 client init, handler, serialization) during the snapshot phase, so the restored snapshot is fully warm:
   ```kotlin
   class Priming : org.crac.Resource {
       init { org.crac.Core.getGlobalContext().register(this) }
       override fun beforeCheckpoint(ctx: org.crac.Context<out org.crac.Resource>?) {
           // exercise critical paths: build S3 client, run handler against a primed request
       }
       override fun afterRestore(ctx: org.crac.Context<out org.crac.Resource>?) {}
   }
   ```
3. **arm64** + **java25** — inherited from `Globals`, do not override per function.
4. **Tiered compilation (L1)** — `JAVA_TOOL_OPTIONS: "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"` in `Environment.Variables`.

## Streaming Protocol Conventions

- Move from `RequestHandler` to **`RequestStreamHandler`**: read the API GW event from `InputStream`, write bytes to `OutputStream`.
- Write the API Gateway streaming response format explicitly: **metadata JSON → 8 null bytes (`ByteArray(8)`) → body bytes**.
- Stream the S3 object with a **bounded buffer** (e.g. 1 MB); flush per chunk. Never load the body into a `String`/`ByteArray`.
- **Validate before writing metadata** — the status code is committed once metadata + delimiter are written. Check the S3 object exists and its size first; only then write `200` and stream.
- **Flush deliberately** after each chunk so the client observes progressive delivery. Keep inter-chunk gaps under API Gateway idle timeouts (5 min regional/private, 30 s edge-optimized).
- Model repeatable headers (`Set-Cookie`) as `Map<String, List<String>>` / a `cookies` field, not `Map<String, String>`.

## SAM / API Gateway

- AWS SAM, REST API with proxy integration (`/{proxy+}`, `ANY`).
- Enable streaming via the `Api` event property **`ResponseTransferMode: RESPONSE_STREAM`** (SAM). The lower-level integration equivalent is `STREAM`, and the integration URI needs the `/response-streaming-invocations` suffix. **These names are not interchangeable — this is a known deploy-cycle trap; log it in `docs/log.md`.**
- Run `sam validate --template-file deployment/aws/sam/template.yaml` after every template change; require exit code 0 before committing.

## Coding Standards (from MockNest)

**Kotlin idioms**
- Use `runCatching { }` (incl. as a scope function `obj.runCatching { ... }`) over try/catch/finally; `.use { }` for closeables.
- Avoid `!!`. Prefer `check`/`error`/`checkNotNull` over `IllegalStateException`, and `require`/`requireNotNull` over `IllegalArgumentException` (for bug detection, not user-input validation).
- Prefer latest features (`enum.entries` over `values()`). For `$` escaping use multi-dollar interpolation (`$$"${'$'}{x}"`), not backslash.
- Use proper imports, never fully-qualified class names in code.

**Constructor visibility**
- Minimum visibility. Dependency only forwarded to a superclass → plain parameter (no `val`). Stored for internal use → `private val`. Public `val` only when it is genuinely part of the class API. Never add explicit `public`.

**Logging** (kotlin-logging)
- `private val logger = KotlinLogging.logger {}` as a private top-level member.
- Structured lambdas with context: `logger.info { "Streaming object: key=$key, size=$size" }`.
- Always pass the exception: `logger.error(e) { "..." }` (not `${e.message}`).
- Levels: ERROR (operation-blocking), WARN (recoverable/expected failure), INFO (normal flow), DEBUG (detail). Never log secrets (bucket names, keys, auth headers) — note `docs/log.md` is for developer gotchas, not runtime secrets.

**Exceptions**
- Never swallow silently — log at WARN+ before handling. Scope-function `runCatching` for single calls; traditional `runCatching { }` for multi-step blocks.

**Serialization**
- Prefer kotlinx Serialization: `@Serializable` data classes, `Json.encodeToString` / `Json.decodeFromString`. Only fall back to Jackson when a library requires it.

**Tests**
- Given-When-Then backtick names: `` `Given a 15MB S3 object When streamed Then body is byte-identical and memory stays bounded` ``.
- MockK: declare mocks `mockk(relaxed = true)` at property level; reset with `clearMocks(...)` (not `clearAllMocks()`).
- Check nullables with `assertNotNull` before asserting on them; use `assertEquals` (not `assertTrue(a == b)`).
- **Property-based tests** via `@ParameterizedTest` (`@ValueSource`/`@MethodSource`), 10–20 diverse cases, testing universal properties (e.g. "output is byte-identical to input", "buffer allocation stays bounded"). Store larger fixtures in `src/test/resources/test-data/`.
- Coverage ≥ 90% (`./gradlew koverHtmlReport`, `./gradlew koverVerify`).

## Common Commands

```bash
# Build (fat jar via shadow)
./gradlew clean build

# Tests + coverage
./gradlew test
./gradlew koverHtmlReport koverVerify

# Validate, build & deploy (AWS SAM)
sam validate --template-file deployment/aws/sam/template.yaml
sam build
sam deploy --guided        # or ./deployment/aws/sam/deploy.sh

# Prove streaming against the deployed endpoint
./scripts/post-deploy-test.sh
```

## Gradle Cache Policy

Never rely on the Gradle cache to determine what is available. Resolve from declared repositories (Maven Central). To understand a library's API, read its official docs or its GitHub source at the version pinned in `build.gradle.kts` — do not dig into cached/compiled artifacts.
