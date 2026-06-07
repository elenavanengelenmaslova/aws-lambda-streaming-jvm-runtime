# Implementation Plan: S3 File Streaming Endpoint

## Overview

This plan implements a single-module Kotlin/JVM AWS Lambda that streams a ~15 MB S3 object to an HTTP client through API Gateway response streaming, without ever buffering the whole object. Work proceeds bottom-up: build setup → data models → leaf components (writer, buffer copy, validator, parser, S3 source) → the orchestrating `StreamHandler` → CRaC priming → LocalStack integration → SAM infrastructure and deploy/verification scripts → dev log → coverage gate. Each component task is immediately followed by its property and/or unit test tasks, every top-level task ends with a `./gradlew clean test` checkpoint, and the plan closes with a 90% Kover coverage verification.

Language: **Kotlin 2.3.x / Java 25** (per the design and `tech.md`). Package root: `com.example.streaming` (illustrative). Test packages mirror `main/kotlin` under `test/kotlin`.

The five Correctness Properties from the design each become a single `@ParameterizedTest` property-based test, tagged with the exact tag string from the design's Testing Strategy.

## Tasks

- [x] 1. Set up the single-module Gradle project and build configuration
  - [x] 1.1 Create the Gradle build files
    - Create `settings.gradle.kts`, `gradle.properties`, and `build.gradle.kts` for a single Kotlin module.
    - Configure Kotlin 2.3.x with the Java 25 toolchain (`JavaLanguageVersion.of(25)`, `jvmTarget = JVM_25`), `com.gradleup.shadow` for the fat jar, and `org.jetbrains.kotlinx.kover` with a 90% `koverVerify` threshold.
    - Declare dependencies from `tech.md`: `aws-lambda-java-core` 1.4.0, `aws-lambda-java-events` 3.16.1, `aws.sdk.kotlin:s3`, `kotlinx-serialization-json`, `io.github.oshai:kotlin-logging-jvm`, `org.crac`; test: `org.junit.jupiter:junit-jupiter` 6.x (`useJUnitPlatform()`), MockK, `kotlinx-coroutines-test`, TestContainers + LocalStack.
    - Create the `src/main/kotlin`, `src/test/kotlin`, and `src/test/resources/test-data` source roots.
    - _Requirements: 4.3, 5.2, 7.5_
  - [x] 1.2 Checkpoint - run `./gradlew clean test` and confirm the project configures and builds. Ensure all tests pass, ask the user if questions arise.

- [x] 2. Implement the data models
  - [x] 2.1 Create the domain and protocol data models
    - Implement `StreamRequest(fileName: String)`.
    - Implement sealed `ParseResult` (`Parsed`, `ParseError`), sealed `ValidationResult` (`Valid`, `Invalid` + `Reason` enum: `MISSING, TOO_LONG, ILLEGAL_CHARACTER, PATH_SEPARATOR, PARENT_DIR, ABSOLUTE_PATH`), and sealed `HeadResult` (`Exists(size)`, `NotFound`, `Failure(cause)`).
    - Implement `@Serializable ResponseMetadata(statusCode: Int, headers: Map<String, List<String>>)` and `@Serializable ErrorBody(message: String)`.
    - _Requirements: 1.1, 4.2, 4.3_
  - [x] 2.2 Checkpoint - run `./gradlew clean test`. Ensure all tests pass, ask the user if questions arise.

- [x] 3. Implement the Response_Writer and streaming protocol header
  - [x] 3.1 Implement `ResponseWriter`
    - Implement `writeMetadata(output, metadata)`: serialize `ResponseMetadata` to JSON with kotlinx-serialization, write UTF-8 bytes, then write `ByteArray(8)` of zero bytes as the delimiter (segments 1 + 2).
    - Implement `writeError(output, status, message)`: write metadata for the error status plus an `ErrorBody` JSON body and no file body bytes.
    - Define the protocol constants (`DELIMITER_LEN = 8`, content-type prelude marker) and ensure write failures propagate without mutating an already-committed status.
    - _Requirements: 4.1, 4.2, 4.3, 4.5, 4.6_
  - [x] 3.2 Write property test for metadata round-trip
    - **Property 1: Metadata round-trip preserves status and all headers** — `@ParameterizedTest` over 10–20 `ResponseMetadata` cases (empty headers, single/multi value, empty value list, unicode values, varied status codes); assert `decode(encode(m)) == m`.
    - Tag: `Feature: s3-file-streaming-endpoint, Property 1: metadata round-trip preserves statusCode and every header name->value-list entry`.
    - **Validates: Requirements 4.2, 4.4**
  - [x] 3.3 Write unit tests for `ResponseWriter`
    - Assert the delimiter is exactly 8 zero bytes at the metadata/body boundary; assert error responses carry an `ErrorBody` and no file body bytes.
    - _Requirements: 4.1_
  - [x] 3.4 Checkpoint - run `./gradlew clean test`. Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement the Bounded_Buffer streaming copy
  - [x] 4.1 Implement the bounded-buffer copy
    - Implement `const val BUFFER_SIZE = 1_048_576` and `copy(source, sink, flush): Long` reusing a single `ByteArray(BUFFER_SIZE)`, flushing after each written chunk and once more after the final partial chunk; return total bytes copied.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_
  - [x] 4.2 Write property test for byte-identical streaming
    - **Property 2: Streaming output is byte-identical to the source** — `@ParameterizedTest` over 10–20 boundary/random sizes (0, < 1 MB, exactly 1 MB, multiples, non-aligned); assert identical bytes/order/count and at-least-once-per-chunk plus final flush via a counting sink.
    - Tag: `Feature: s3-file-streaming-endpoint, Property 2: streamed bytes are byte-identical to the source`.
    - **Validates: Requirements 5.1, 5.4, 5.5, 5.6**
  - [x] 4.3 Write property test for bounded transfer memory
    - **Property 3: Transfer memory is bounded independent of object size** — `@ParameterizedTest` over 10–20 sizes (small, ~6 MB, ~15 MB and beyond); assert the reused buffer is constant `1_048_576` and peak ≤ `BUFFER_SIZE` + fixed overhead, with no single full-object `String`/`ByteArray` allocation.
    - Tag: `Feature: s3-file-streaming-endpoint, Property 3: transfer memory bounded independent of object size`.
    - **Validates: Requirements 5.2, 5.3**
  - [x] 4.4 Checkpoint - run `./gradlew clean test`. Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement the File_Name_Validator
  - [x] 5.1 Implement `FileNameValidator.validate(fileName: String?)`
    - Pure function, no I/O. Accept only allow-list characters (`A–Z`, `a–z`, `0–9`, `-`, `_`, `.`); reject path separators (`/`, `\`), parent-directory `..`, absolute prefixes (leading `/`, leading `\`, drive-letter `X:`), empty/all-whitespace, and length > 1024; return a specific `Reason` on rejection.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_
  - [x] 5.2 Write unit tests for `FileNameValidator`
    - Cover each rejection `Reason` and representative acceptances as explicit examples (complementing Property 4).
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_
  - [x] 5.3 Checkpoint - run `./gradlew clean test`. Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement the Request_Parser
  - [x] 6.1 Implement `RequestParser.parse(input: InputStream)`
    - Use a lenient `Json` (`ignoreUnknownKeys = true`) to extract the requested file name (1–1024 chars) from the API Gateway proxy event into `StreamRequest`; return `ParseError` when the event cannot be decoded.
    - _Requirements: 1.1, 1.3_
  - [x] 6.2 Write unit tests for `RequestParser`
    - Representative proxy events (path-parameter file name) parse to `StreamRequest`; malformed/empty/truncated input returns `ParseError`.
    - _Requirements: 1.1, 1.3_
  - [x] 6.3 Checkpoint - run `./gradlew clean test`. Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement the S3_Source
  - [x] 7.1 Implement `S3Source`
    - Wrap the Kotlin AWS SDK `S3Client`; bucket name from `BUCKET_NAME` env var (never a literal, never logged).
    - Implement `suspend head(key): HeadResult` issuing `headObject` inside `withTimeout(10.seconds)`, mapping not-found to `NotFound` and any other failure or timeout to `Failure`.
    - Implement `suspend streamBody(key, sink, flush): Long` calling `getObject`, consuming the body `InputStream` inside `.use { }`, copying via the bounded-buffer loop; on read/write failure stop, release the stream, and rethrow.
    - _Requirements: 3.1, 3.2, 3.4, 5.1, 5.7_
  - [x] 7.2 Write unit tests for `S3Source`
    - Use MockK + `kotlinx-coroutines-test`: not-found → `NotFound`, non-missing error and timeout → `Failure`, success → `Exists(size)`; `streamBody` releases the source stream on mid-copy failure.
    - _Requirements: 3.1, 3.2, 3.4, 5.7_
  - [x] 7.3 Checkpoint - run `./gradlew clean test`. Ensure all tests pass, ask the user if questions arise.

- [x] 8. Implement the Stream_Handler orchestration
  - [x] 8.1 Implement `StreamHandler` (`RequestStreamHandler`)
    - Inject `RequestParser`, `FileNameValidator`, `S3Source`, `ResponseWriter` via Kotlin delegated properties (DI by delegation).
    - Drive parse → validate → head → write: map `ParseError` and missing/whitespace/over-length/rejected names to 400 (no S3 request on rejection), `NotFound` to 404, `Failure`/timeout to 502, and `Exists(size)` to 200 — writing metadata (`Content-Length = size`, `Content-Type: application/octet-stream`) before any body, then streaming the body.
    - Guarantee no body bytes on any pre-commit error path; after commit allow only body truncation.
    - _Requirements: 1.2, 1.3, 1.4, 2.7, 3.2, 3.3, 3.4, 4.1, 6.3_
  - [x] 8.2 Write property test for file-name validation and zero S3 access on rejection
    - **Property 4: Validator accepts exactly the safe set and never reaches S3 when rejecting** — `@ParameterizedTest` over 10–20 valid and adversarial strings; assert accept-iff-safe against an independent reference predicate and, via a relaxed `S3Source` mock, zero S3 interactions on every rejection (handler writes 400).
    - Tag: `Feature: s3-file-streaming-endpoint, Property 4: validator accepts iff safe and rejected names issue no S3 request`.
    - **Validates: Requirements 1.2, 1.4, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7**
  - [x] 8.3 Write property test for status-committed-once
    - **Property 5: Status is committed exactly once; failures never rewrite it** — `@ParameterizedTest` over 10–20 (outcome, failure-offset) injection points; assert pre-commit failures write no body and the mapped status (400/404/502), and post-commit failures leave the status byte-prefix unchanged, truncate the body, release the source stream, and propagate.
    - Tag: `Feature: s3-file-streaming-endpoint, Property 5: status committed once, failures never rewrite it`.
    - **Validates: Requirements 1.2, 1.3, 3.1, 3.2, 3.3, 3.4, 4.5, 4.6, 5.7, 6.3**
  - [x] 8.4 Write branch unit tests for `StreamHandler`
    - With mocked collaborators: `NotFound` → 404, `Failure`/timeout → 502, `Exists(size)` → 200 with `Content-Length = size` written before body; verify head-before-metadata ordering.
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  - [x] 8.5 Checkpoint - run `./gradlew clean test`. Ensure all tests pass, ask the user if questions arise.

- [x] 9. Implement the Priming_Hook (CRaC)
  - [x] 9.1 Implement `Priming` (`org.crac.Resource`)
    - Register at class init; in `beforeCheckpoint` exercise, in one pass, S3 client init, one handler invocation against a primed request, and metadata serialization. Do not catch errors — let any failure propagate so snapshot creation fails.
    - _Requirements: 7.2, 7.3_
  - [x] 9.2 Write unit tests for `Priming`
    - Assert `beforeCheckpoint` touches S3 init + one handler invocation + metadata serialization in a single pass; a throwing primed path propagates.
    - _Requirements: 7.2, 7.3_
  - [x] 9.3 Checkpoint - run `./gradlew clean test`. Ensure all tests pass, ask the user if questions arise.

- [x] 10. Implement LocalStack integration tests
  - [x] 10.1 Write the sub-6 MB integration test
    - TestContainers + LocalStack S3, one container shared via `@BeforeAll`/`@AfterAll` with `Wait.forHttp("/_localstack/health").forStatusCode(200)`; clean only object data between tests. Stream a sub-6 MB uploaded object end-to-end through the handler and assert byte-identical received bytes.
    - _Requirements: 5.6_
  - [x] 10.2 Write the ~15 MB Test_Object integration test
    - Upload a ~15 MB object (the `Test_Object`) and stream it through the handler; assert HTTP 200, received total byte count equals stored size, and body byte-identical — proving delivery past the 6 MB limit.
    - _Requirements: 6.1, 6.2_
  - [x] 10.3 Checkpoint - run `./gradlew clean test`. Ensure all tests pass, ask the user if questions arise.

- [x] 11. Author the SAM infrastructure and deploy scripts
  - [x] 11.1 Create `deployment/aws/sam/template.yaml`
    - `Globals` with `Runtime: java25` and `Architectures: [arm64]` (no per-function override).
    - Serverless Function: `AutoPublishAlias: live`, `SnapStart: ApplyOn: PublishedVersions`, `Environment.Variables.JAVA_TOOL_OPTIONS: "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"`, bounded `MemorySize` (128–1024) and `Timeout` (1–300 s), no provisioned concurrency.
    - REST API proxy integration for the GET route with the `Api` event `ResponseTransferMode: RESPONSE_STREAM` (never `STREAM`).
    - Execution role scoped to `s3:GetObject` on the bucket ARN and objects only (no wildcard, no other S3 action).
    - S3 bucket with default SSE, all four Block Public Access flags true, and lifecycle rules expiring current objects and non-current versions within 1–30 days.
    - CloudWatch log group with 30-day retention.
    - _Requirements: 7.1, 7.4, 7.5, 8.1, 8.2, 8.3, 10.1, 10.2, 10.3, 10.6, 11.1, 11.2, 11.3, 11.4, 11.5, 11.6_
  - [x] 11.2 Create `samconfig.toml`, `deployment/aws/sam/build.sh`, and `deployment/aws/sam/deploy.sh`
    - `build.sh` builds the shadow fat jar; `deploy.sh` runs `sam build` + `sam deploy`. HTTPS/TLS enforcement and plain-HTTP rejection are handled at the API Gateway layer (no handler code).
    - _Requirements: 10.4, 10.5_
  - [x] 11.3 Checkpoint - run `sam validate --template-file deployment/aws/sam/template.yaml` and confirm exit code 0. Ensure validation passes, ask the user if questions arise.
    - _Requirements: 8.4, 8.5_

- [x] 12. Author the post-deploy verification script
  - [x] 12.1 Create `scripts/post-deploy-test.sh`
    - Issue a warmup request first and discard it from all timings; confirm a ≥ 15 MB payload is delivered in full and byte-identical; measure time-to-first-byte and confirm it arrives within 50% of total completion time; confirm the ≥ 15 MB Lambda max memory is within 10% of the 6 MB-payload max memory; exit non-zero with an error (never reporting success) on unreachable/non-success endpoint or payload mismatch.
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6_
  - [x] 12.2 Add Test_Object seeding and stack teardown steps
    - In the script (or its documentation) seed the ~15 MB `Test_Object` into the deployed bucket and provide a teardown step that removes the deployed stack with a success indication that no stack resources remain.
    - _Requirements: 11.7_

- [x] 13. Create the developer log
  - [x] 13.1 Create `docs/log.md`
    - Record the `STREAM` vs `RESPONSE_STREAM` deploy-cycle trap and the open item on how the content-type marker is conveyed on the JVM `RequestStreamHandler` path; use descriptive names/placeholders, no runtime secrets.
    - _Requirements: 12.1, 12.2, 12.3, 12.4_

- [x] 14. Final coverage verification
  - [x] 14.1 Run `./gradlew koverHtmlReport koverVerify`
    - Confirm the report generates and the 90% coverage gate passes; address gaps if `koverVerify` fails.
    - _Requirements: (coverage gate per tech.md)_

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation, infrastructure, and script tasks are never optional.
- Each property-based test is a single `@ParameterizedTest` (10–20 diverse cases) tagged with the exact tag string from the design's Testing Strategy.
- Checkpoint sub-tasks run `./gradlew clean test` after each top-level coding task (and `sam validate` for the SAM template) to validate incrementally.
- HTTPS enforcement, plain-HTTP rejection, and all SAM correctness checks are configuration concerns verified by `sam validate` plus template assertions, not property tests.
- Each task references the specific requirement sub-clauses it satisfies for traceability.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "11.1", "13.1"] },
    { "id": 1, "tasks": ["2.1", "11.2", "12.1", "12.2"] },
    { "id": 2, "tasks": ["3.1", "4.1", "5.1", "6.1", "7.1"] },
    { "id": 3, "tasks": ["3.2", "3.3", "4.2", "4.3", "5.2", "6.2", "7.2", "8.1"] },
    { "id": 4, "tasks": ["8.2", "8.3", "8.4", "9.1", "10.1", "10.2"] },
    { "id": 5, "tasks": ["9.2"] },
    { "id": 6, "tasks": ["14.1"] }
  ]
}
```
