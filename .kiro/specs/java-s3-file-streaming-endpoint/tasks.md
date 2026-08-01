# Implementation Plan: Java S3 File Streaming Endpoint

## Overview

This plan builds `streaming-s3-example-java`, a single Java Gradle module that streams a ~15 MB S3 object to an HTTP client through API Gateway response streaming while **consuming the `aws-lambda-streaming-core` library from Java** (proving cross-language interop). It uses the **AWS SDK for Java v2** (synchronous) and drives the library's `ResponseWriter`, `ResponseMetadata`/`fromMultiValue`, and `copy(...)` directly — reimplementing none of the wire format.

Work proceeds bottom-up: build setup → data models → library protocol interop check → leaf components (validator, parser, S3 source with bounded copy) → the orchestrating `StreamHandler` → CRaC priming → LocalStack integration → SAM infrastructure + OIDC changes → CI/CD wiring (build/coverage/validate, real OIDC deploy, pipeline streaming test) → dev log → coverage gate → real-deploy verification. Each component task is followed by its property and/or unit tests, every top-level coding task ends with a `./gradlew` checkpoint, and every SAM change ends with `sam validate`.

Language: **Java 25** (toolchain), consuming the Java-21-compiled library. Package root: `nl.vintik.streaming.java`. Test packages mirror `main/java` under `test/java`. The five Correctness Properties from the design each become a single `@ParameterizedTest`, tagged with the exact `Feature: java-s3-file-streaming-endpoint, Property N: …` string.

The Kotlin example (`streaming-s3-example`), its SAM stack, and the existing pipeline are the reference; the Java example mirrors them and must not modify or break them.

## Tasks

- [x] 1. Set up the Java Gradle module and build configuration
  - [x] 1.1 Register the module and create its build file
    - Add `include("streaming-s3-example-java")` to `settings.gradle.kts`.
    - Add versions to the root `build.gradle.kts` `extra` block: `awsSdkJavaVersion` (AWS SDK for Java v2 BOM), `jacksonVersion`, `mockitoVersion`.
    - Create `streaming-s3-example-java/build.gradle.kts`: apply `java`, `com.gradleup.shadow`, `org.jetbrains.kotlinx.kover`; Java 25 toolchain (`JavaLanguageVersion.of(25)`).
    - Dependencies: `implementation(project(":streaming-core"))`, `com.amazonaws:aws-lambda-java-core`, `platform(software.amazon.awssdk:bom)` + `software.amazon.awssdk:s3`, `com.fasterxml.jackson.core:jackson-databind`, `org.slf4j:slf4j-simple`, `org.crac:crac`; test: `org.junit.jupiter:junit-jupiter`, `org.mockito:mockito-core`, `org.mockito:mockito-junit-jupiter`, `org.testcontainers:testcontainers` + `:junit-jupiter` + `:localstack`, `testRuntimeOnly org.junit.platform:junit-platform-launcher`.
    - Configure `tasks.test { useJUnitPlatform { excludeTags support via -PexcludeTags }; systemProperty("net.bytebuddy.experimental","true") }` (Mockito on Java 25).
    - Configure `tasks.shadowJar { archiveFileName = "streaming-endpoint-java.jar"; destinationDirectory = build/dist; mergeServiceFiles() }` and `tasks.build { dependsOn(shadowJar) }`.
    - Configure Kover `koverVerify` with `minValue = 80`.
    - Create source roots: `src/main/java/nl/vintik/streaming/java`, `src/test/java/nl/vintik/streaming/java`, `src/test/resources/test-data`.
    - _Requirements: 1.1, 4, 6, 13.1, 13.3, 15.5_
  - [x] 1.2 Checkpoint - run `./gradlew :streaming-s3-example-java:build -PexcludeTags=integration` and confirm the module configures, compiles, and the (empty) test task succeeds. Confirm `streaming-core` and `streaming-s3-example` still build. Ask the user if questions arise.

- [x] 2. Implement the data models
  - [x] 2.1 Create the domain and outcome types (Java records + sealed interfaces)
    - `FileRequest(String fileName)`, `StreamRequest(String fileName)` records.
    - `sealed interface ParseResult` with `Parsed(StreamRequest)` and `ParseError()`.
    - `sealed interface ValidationResult` with `Valid(String)`, `Invalid(Reason)`, and `enum Reason { MISSING, TOO_LONG, ILLEGAL_CHARACTER, PATH_SEPARATOR, PARENT_DIR, ABSOLUTE_PATH }`.
    - `sealed interface HeadResult` with `Exists(long size)` (compact ctor asserts `size >= 0`), `NotFound()`, `Failure(Throwable cause)`.
    - `ResponseMetadata`/`ErrorBody` are NOT redefined — they come from the library.
    - _Requirements: 2.1, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 4.2, 4.3_
  - [x] 2.2 Checkpoint - run `./gradlew :streaming-s3-example-java:build -PexcludeTags=integration`. Ensure it builds, ask the user if questions arise.

- [x] 3. Verify the library's response protocol works from Java (Property 1)
  - [x] 3.1 Write the metadata round-trip property test
    - **Property 1: Metadata round-trip preserves status and all headers** — `@ParameterizedTest` over 10–20 cases building `ResponseMetadata` from Java (direct ctor with `null` cookies, cookies present, and via `ResponseMetadata.Companion.fromMultiValue`), writing through `new ResponseWriter(...)` to a `ByteArrayOutputStream`, splitting on the 8-null-byte delimiter, decoding the metadata JSON, and asserting the status code and every header round-trip. Exercises the Java call sites `new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN)`, `new ResponseMetadata(int, Map, null)`, and `ResponseMetadata.Companion.fromMultiValue(...)`.
    - Tag: `Feature: java-s3-file-streaming-endpoint, Property 1: metadata round-trip preserves statusCode and every header`.
    - **Validates: Requirements 1.2, 1.3, 5.1, 5.2**
  - [x] 3.2 Checkpoint - run `./gradlew :streaming-s3-example-java:test -PexcludeTags=integration`. Ensure the property test passes; record any Java-interop friction in `docs/log.md` (Req 1.5, 14). Ask the user if questions arise.

- [x] 4. Implement the File_Name_Validator
  - [x] 4.1 Implement `FileNameValidator.validate(String)`
    - Pure function, no I/O. Ordered checks returning a specific `Reason`: `MISSING` (null/blank) → `TOO_LONG` (>1024) → `ABSOLUTE_PATH` (leading `/`, `\`, or `X:`) → `PATH_SEPARATOR` (`/` or `\`) → `PARENT_DIR` (`..`) → `ILLEGAL_CHARACTER` (outside `A–Z a–z 0–9 - _ .`). Otherwise `Valid`.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_
  - [x] 4.2 Write unit tests for `FileNameValidator`
    - Cover each rejection `Reason` and representative acceptances as explicit examples (complementing Property 4).
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_
  - [x] 4.3 Checkpoint - run `./gradlew :streaming-s3-example-java:test -PexcludeTags=integration`. Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement the Request_Parser
  - [x] 5.1 Implement `RequestParser.parse(InputStream)`
    - Use a Jackson `ObjectMapper` (`FAIL_ON_UNKNOWN_PROPERTIES = false`) to read the proxy event tree and lift `/pathParameters/proxy` into `StreamRequest` (empty string when absent — left for the validator). Return `ParseError` only when the JSON cannot be decoded (malformed/empty/truncated).
    - _Requirements: 2.1, 2.3_
  - [x] 5.2 Write unit tests for `RequestParser`
    - Representative `/{proxy+}` proxy events parse to `StreamRequest`; malformed/empty/truncated input returns `ParseError`.
    - _Requirements: 2.1, 2.3_
  - [x] 5.3 Checkpoint - run `./gradlew :streaming-s3-example-java:test -PexcludeTags=integration`. Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement the S3_Source (AWS SDK for Java v2) and bounded streaming
  - [x] 6.1 Implement `S3Source`
    - Sync `S3Client` (default chain); bucket from `BUCKET_NAME` env var (never logged).
    - `head(FileRequest)`: `headObject` with request-level `overrideConfiguration(apiCallTimeout(Duration.ofSeconds(10)))`; catch `NoSuchKeyException` → `NotFound`; `ApiCallTimeoutException` and any other exception → `Failure(cause)`; success → `Exists(contentLength())`.
    - `streamBody(FileRequest, OutputStream)`: `try (ResponseInputStream<GetObjectResponse> in = client.getObject(get)) { return BoundedBufferKt.copy(in, sink); }` — the library `copy` (2-arg overload) flushes the sink per chunk; try-with-resources releases the stream on success and failure.
    - _Requirements: 1.4, 4.1, 4.2, 4.4, 6.1, 6.4, 6.6_
  - [x] 6.2 Write unit tests for `S3Source`
    - Mockito `S3Client`: `NoSuchKeyException` → `NotFound`; other exception and `ApiCallTimeoutException` → `Failure`; success → `Exists(size)`; `streamBody` releases the source stream on mid-copy failure and propagates.
    - _Requirements: 4.1, 4.2, 4.4, 6.6_
  - [x] 6.3 Write property test for byte-identical streaming
    - **Property 2: Streaming output is byte-identical to the source** — `@ParameterizedTest` over 10–20 boundary/random sizes (0, < 1 MB, exactly 1 MB, multiples, non-aligned); drive `S3Source.streamBody` (or the library `copy` behind it) from an in-memory source into a counting sink; assert identical bytes/order/count and at-least-once-per-chunk plus final flush.
    - Tag: `Feature: java-s3-file-streaming-endpoint, Property 2: streamed bytes are byte-identical to the source`.
    - **Validates: Requirements 1.4, 6.1, 6.4, 6.5**
  - [x] 6.4 Write property test for bounded transfer memory
    - **Property 3: Transfer memory is bounded independent of object size** — `@ParameterizedTest` over 10–20 sizes (small, ~6 MB, ~15 MB and beyond); assert the copy reuses a fixed buffer and peak transfer memory stays at buffer + fixed overhead, with no single full-object `String`/`byte[]` allocation.
    - Tag: `Feature: java-s3-file-streaming-endpoint, Property 3: transfer memory bounded independent of object size`.
    - **Validates: Requirements 6.2, 6.3**
  - [x] 6.5 Checkpoint - run `./gradlew :streaming-s3-example-java:test -PexcludeTags=integration`. Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement the Stream_Handler orchestration
  - [x] 7.1 Implement `StreamHandler` (`RequestStreamHandler`)
    - Constructor injection: public no-arg ctor wiring real collaborators (`RequestParser`, `FileNameValidator`, `S3Source`, `new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN)`); package-private ctor taking collaborators for tests.
    - `handleRequest`: `try (output) { … output.flush(); }` (flush+close, Req 5.5). Parse → on `ParseError` write 400. Validate → map each `Invalid` reason to 400 with no S3 call. `head` → `NotFound` 404, `Failure` 502, `Exists(size)` → `writeMetadata` 200 with `Content-Type: application/octet-stream` and `Content-Length = size` before any body, then `streamBody`.
    - Use pattern-matching `switch` over the sealed types. Guarantee no body on any pre-commit error; after commit allow only body truncation.
    - _Requirements: 1.2, 1.4, 2.2, 2.3, 2.4, 3.7, 4.2, 4.3, 4.4, 5.1, 5.3, 5.4, 5.5, 7.3_
  - [x] 7.2 Write property test for file-name validation and zero S3 access on rejection
    - **Property 4: Validator accepts exactly the safe set and never reaches S3 when rejecting** — `@ParameterizedTest` over 10–20 valid and adversarial strings; assert accept-iff-safe against an independent reference predicate and, via a Mockito `S3Source` mock, `verifyNoInteractions` on every rejection (handler writes 400).
    - Tag: `Feature: java-s3-file-streaming-endpoint, Property 4: validator accepts iff safe and rejected names issue no S3 request`.
    - **Validates: Requirements 2.2, 2.4, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7**
  - [x] 7.3 Write property test for status-committed-once
    - **Property 5: Status is committed exactly once; failures never rewrite it** — `@ParameterizedTest` over 10–20 (outcome, failure-offset) injection points; assert pre-commit failures write no body and the mapped status (400/404/502), and post-commit failures leave the status byte-prefix unchanged, truncate the body, release the source stream, and propagate.
    - Tag: `Feature: java-s3-file-streaming-endpoint, Property 5: status committed once, failures never rewrite it`.
    - **Validates: Requirements 2.2, 2.3, 4.1, 4.2, 4.3, 4.4, 5.3, 5.4, 6.6, 7.3**
  - [x] 7.4 Write branch unit tests for `StreamHandler`
    - With Mockito collaborators: `NotFound` → 404, `Failure`/timeout → 502, `Exists(size)` → 200 with `Content-Length = size` written before body; verify head-before-metadata ordering.
    - _Requirements: 4.2, 4.3, 4.4_
  - [x] 7.5 Checkpoint - run `./gradlew :streaming-s3-example-java:test -PexcludeTags=integration`. Ensure all tests pass, ask the user if questions arise.

- [x] 8. Implement the Priming_Hook (CRaC)
  - [x] 8.1 Implement `Priming` (`org.crac.Resource`)
    - Register in the constructor; `beforeCheckpoint` exercises, in one pass, S3 client init, one `StreamHandler` invocation against a primed proxy event (discard output), and metadata serialization via `ResponseWriter.writeMetadata`. Provide a minimal no-op `Context`. Do not catch errors — a failure fails the snapshot.
    - _Requirements: 8.2, 8.3_
  - [x] 8.2 Write unit tests for `Priming`
    - Assert `beforeCheckpoint` touches S3 init + one handler invocation + metadata serialization in a single pass (inject factory/collaborators); a throwing primed path propagates.
    - _Requirements: 8.2, 8.3_
  - [x] 8.3 Checkpoint - run `./gradlew :streaming-s3-example-java:test -PexcludeTags=integration`. Ensure all tests pass, ask the user if questions arise.

- [x] 9. Implement LocalStack integration tests
  - [x] 9.1 Write the sub-6 MB integration test
    - TestContainers + LocalStack S3, AWS SDK for Java v2 client, one container shared via `@BeforeAll`/`@AfterAll` with `Wait.forHttp("/_localstack/health").forStatusCode(200)`; clean only object data between tests. Stream a sub-6 MB uploaded object end-to-end through the handler and assert byte-identical received bytes. Tag `integration`.
    - _Requirements: 6.5, 13.2, 13.4_
  - [x] 9.2 Write the ~15 MB Test_Object integration test
    - Upload a ~15 MB object and stream it through the handler; assert HTTP 200, received total byte count equals stored size, and body byte-identical — proving delivery past the 6 MB limit. Tag `integration`.
    - _Requirements: 7.1, 7.2, 13.2, 13.4_
  - [x] 9.3 Checkpoint - run `./gradlew :streaming-s3-example-java:test` (with Colima running) and confirm unit + integration tests pass. Ask the user if questions arise.

- [x] 10. Author the Java SAM infrastructure and deploy scripts
  - [x] 10.1 Create `deployment/aws/sam-java/template.yaml`
    - Structurally identical to the Kotlin template. `Globals` `Runtime: java25`, `Architectures: [arm64]` (no per-function override). Function: `FunctionName: java-s3-file-streaming-endpoint`, `Handler: nl.vintik.streaming.java.StreamHandler`, `CodeUri: ../../../build/dist/streaming-endpoint-java.jar`, `AutoPublishAlias: live`, `SnapStart: ApplyOn: PublishedVersions`, `JAVA_TOOL_OPTIONS: "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"`, bounded `MemorySize` (128–1024) and `Timeout` (1–300 s), no provisioned concurrency, `BUCKET_NAME` env from the bucket. REST API proxy `GET /{proxy+}` with `ResponseTransferMode: RESPONSE_STREAM` (never `STREAM`), API key required. `s3:GetObject`-only role scoped to the bucket ARN/objects. S3 bucket with SSE, all four Block Public Access flags, versioning, lifecycle expiry (current + non-current). Log group with finite retention. Outputs named `StreamingEndpointUrl`, `FunctionName`, `SourceBucketName`, `ApiKeyId`.
    - _Requirements: 8.1, 8.4, 8.5, 9.1, 9.2, 9.3, 11.1, 11.2, 11.3, 11.6, 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_
  - [x] 10.2 Create `deployment/aws/sam-java/{samconfig.toml,build.sh,deploy.sh}`
    - `samconfig.toml` with `stack_name = java-s3-file-streaming-endpoint`. `build.sh` runs `./gradlew clean :streaming-s3-example-java:build` (produces `streaming-endpoint-java.jar`). `deploy.sh` runs build.sh + `sam build` + `sam deploy`. HTTPS/TLS enforcement and plain-HTTP rejection are at the API Gateway layer (no handler code).
    - _Requirements: 11.4, 11.5, 15.5_
  - [x] 10.3 Checkpoint - run `sam validate --template-file deployment/aws/sam-java/template.yaml` and confirm exit code 0. Ask the user if questions arise.
    - _Requirements: 9.4, 9.5_

- [x] 11. Widen the OIDC role for the Java stack
  - [x] 11.1 Update `deployment/aws/oidc/github-oidc-role.yaml`
    - In the GitHub Actions role's streaming-test permissions, add S3 access for `arn:aws:s3:::java-s3-file-streaming-endpoint-*` (+ `/*`) and CloudWatch Logs read for `/aws/lambda/java-s3-file-streaming-endpoint*`. Confirm the API-key read and CloudFormation actions already cover the Java stack; do not add new wildcards beyond the existing pattern. Update the CFN execution role only if a scope is stack-name-specific (it is not).
    - _Requirements: 16.3_
  - [x] 11.2 Checkpoint - validate the OIDC template (`aws cloudformation validate-template` or `cfn-lint`) and confirm it still parses; note that applying it requires re-running the OIDC bootstrap with account credentials. Ask the user if questions arise.
    - _Requirements: 16.3_

- [x] 12. Wire the Java example into the CI/CD pipeline
  - [x] 12.1 Extend `workflow-build.yml` to build, cover, and validate the Java example
    - Add `:streaming-s3-example-java:test :streaming-s3-example-java:koverVerify -PexcludeTags=integration` to the test job's Gradle invocation, and add a `sam validate` step for `deployment/aws/sam-java/template.yaml`. Both examples are always built/validated.
    - _Requirements: 15.1, 15.2, 15.3, 15.4_
  - [x] 12.2 Parameterize the reusable deploy and streaming-test workflows
    - Add inputs to `workflow-deploy-aws.yml`: `sam-dir` (default `deployment/aws/sam`), `stack-name` (default `s3-file-streaming-endpoint`), `build-task` (default `clean build`), `jar-name` (default `streaming-endpoint.jar`); use them for the build step, `cd` into `sam-dir`, deploy `stack-name`, and resolve outputs by `stack-name`. Add input `test-script` (default `scripts/pipeline-streaming-test.sh`) and `stack-name` to `workflow-streaming-test.yml` and run the given script with `STACK_NAME`/object-key env. Defaults preserve current Kotlin behavior.
    - _Requirements: 16.1, 16.2, 16.4, 17.1, 17.7_
  - [x] 12.3 Add the Java stack to the entry-point pipelines via a stack matrix
    - In `ci-feature-build.yml` and `cd-deploy-on-demand.yml`, run the `deploy` and `streaming-tests` reusable-workflow calls under a matrix covering both the Kotlin stack and the Java stack (`sam-dir: deployment/aws/sam-java`, `stack-name: java-s3-file-streaming-endpoint`, `build-task: :streaming-s3-example-java:build`, `jar-name: streaming-endpoint-java.jar`, Java `TEST_OBJECT_KEY`). Keep `test → deploy → streaming-tests` ordering; `streaming-tests` still `needs: deploy`.
    - _Requirements: 16.5, 16.6, 17.2, 17.3, 17.4, 17.5, 17.6, 17.8_
  - [x] 12.4 Confirm the shared streaming test targets the Java stack
    - Verify `scripts/pipeline-streaming-test.sh` runs unchanged against the Java stack via `STACK_NAME=java-s3-file-streaming-endpoint` and a Java `TEST_OBJECT_KEY` (e.g. `streaming-test-12mb-java.bin`) — same stages, config surface, and exit codes as the Kotlin run (Req 17.7). Add a Java object key default only where needed; do not fork the script.
    - _Requirements: 17.7_

- [x] 13. Record development knowledge in the dev log
  - [x] 13.1 Append Java-specific entries to `docs/log.md`
    - Record: Java-from-Kotlin interop friction (default-arg `ResponseWriter` ctor needing `Json.Default`; top-level consts on `ResponseWriterKt`; companion `fromMultiValue`; 2-arg `copy` to avoid `Function0`) and any candidate library ergonomics improvement; Mockito on Java 25 (Byte Buddy class-file version / `net.bytebuddy.experimental`); Kover-on-a-pure-Java-module outcome (worked / fell back to JaCoCo). Use descriptive names/placeholders, no runtime secrets.
    - _Requirements: 1.5, 14.1, 14.2, 14.3, 14.4_

- [x] 14. Final coverage verification
  - [x] 14.1 Run `./gradlew :streaming-s3-example-java:koverHtmlReport :streaming-s3-example-java:koverVerify`
    - Confirm the report generates and the 80% coverage gate passes; address gaps if `koverVerify` fails. (If Kover cannot instrument the pure-Java module, switch the module to JaCoCo and log it — Req 14.)
    - _Requirements: 13.3, 15.2_

- [x] 15. Real deploy and pipeline verification (requires AWS access)
  - [x] 15.1 Deploy the Java stack and prove streaming against the live endpoint
    - Via the pipeline (push to `feature/**` runs test → deploy → streaming-tests for both stacks) or locally: `./deployment/aws/sam-java/deploy.sh --guided` then `STACK_NAME=java-s3-file-streaming-endpoint scripts/pipeline-streaming-test.sh`. Confirm a >6 MB payload is delivered in full and byte-identical, and TTFB < 50% of total (progressive delivery), over HTTP/1.1.
    - _Requirements: 7.1, 7.2, 10.1, 10.2, 10.3, 10.5, 10.6, 10.7, 16.1, 16.2, 16.4, 16.5, 17.1, 17.2, 17.3, 17.4, 17.5, 17.6_
  - [x] 15.2 Run the full post-deploy verification and teardown
    - `STACK_NAME=java-s3-file-streaming-endpoint scripts/post-deploy-test.sh` to also verify the ≥15 MB vs 6 MB memory-bounded check (within 10%); then re-run with `TEARDOWN=true` to remove the Java stack and confirm no resources remain.
    - _Requirements: 10.4, 12.7, 16.6_

## Notes

- Package root `nl.vintik.streaming.java`; test packages mirror `main/java`.
- Each property-based test is a single `@ParameterizedTest` (10–20 diverse cases) tagged with the exact tag string from the design's Correctness Properties.
- Checkpoint sub-tasks run `./gradlew :streaming-s3-example-java:…` after each top-level coding task, `sam validate` after each SAM change, and OIDC/template validation after IAM changes.
- The library is consumed, not reimplemented: `ResponseWriter`, `ResponseMetadata`/`fromMultiValue`, and `copy(...)` come from `:streaming-core`. Any interop friction is logged (Req 1.5, 14).
- Tasks 11, 12, and 15 touch shared infrastructure (OIDC role, reusable workflows) and a real AWS account; changes must preserve the existing Kotlin deploy behavior (defaults unchanged) and require AWS credentials to execute.
- HTTPS enforcement, plain-HTTP rejection, and all SAM correctness checks are configuration concerns verified by `sam validate` plus template assertions, not property tests.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "10.1", "11.1", "13.1"] },
    { "id": 1, "tasks": ["2.1", "10.2", "12.1", "12.2"] },
    { "id": 2, "tasks": ["3.1", "4.1", "5.1", "6.1"] },
    { "id": 3, "tasks": ["4.2", "5.2", "6.2", "6.3", "6.4", "7.1"] },
    { "id": 4, "tasks": ["7.2", "7.3", "7.4", "8.1", "9.1", "9.2"] },
    { "id": 5, "tasks": ["8.2", "12.3", "12.4"] },
    { "id": 6, "tasks": ["14.1", "15.1"] },
    { "id": 7, "tasks": ["15.2"] }
  ]
}
```
