# Requirements Document

## Introduction

This feature adds a second, **Java** "hello world" example that proves the published `aws-lambda-streaming-core` library works from **plain Java** (not just Kotlin) on an AWS Lambda behind Amazon API Gateway. It mirrors the existing Kotlin `streaming-s3-example` one-for-one: a single Java Lambda accepts a GET request carrying a file name, confirms the corresponding object in S3, and streams that object's bytes back to the client through API Gateway response streaming — without ever holding the whole object in memory.

The purpose is verification of **cross-language interoperability**: the same library artifact (`nl.vintik:aws-lambda-streaming-core`) that the Kotlin example consumes is exercised here from Java source. The Java handler drives the library's `ResponseWriter` (protocol encoding), `ResponseMetadata` (+ `fromMultiValue`), and the top-level `copy(...)` bounded-buffer helper directly from Java. Because the example is idiomatic Java, it uses the **AWS SDK for Java v2** (`software.amazon.awssdk:s3`) rather than the coroutine-based Kotlin SDK used by the Kotlin example.

Scope is intentionally small and parallel to the Kotlin example: one Lambda, one S3 source object, one streaming endpoint, a single new Gradle module (`streaming-s3-example-java`), no business logic beyond demonstrating streaming. The example reuses the project's cold-start optimizations (SnapStart, CRaC priming, tiered compilation, arm64/Java 25) and SAM deploy approach. Success is only "proven" against a **deployed** endpoint via a post-deploy verification script that runs the same three checks as the Kotlin example. This document handles NO PII and uses NO AI/Bedrock.

## Glossary

- **Streaming_Core_Library**: The published JVM library `nl.vintik:aws-lambda-streaming-core` (module `:streaming-core`) providing `ResponseWriter`, `ResponseMetadata` (+ `fromMultiValue`), `copy(...)`, `OBSERVED_MAX_PRELUDE_LEN`, and `DELIMITER_LEN`.
- **Java_Module**: The new single Gradle module `streaming-s3-example-java`, containing Java sources only in `src/main/java` and Java tests in `src/test/java`.
- **Streaming_Endpoint**: The HTTP GET endpoint exposed through API Gateway that returns an S3 object as a streamed response body from the Java Lambda.
- **Stream_Handler**: The Java component implementing `com.amazonaws.services.lambda.runtime.RequestStreamHandler` that reads the API Gateway event from an `InputStream` and writes the response to an `OutputStream`, driving the Streaming_Core_Library.
- **Request_Parser**: The Java component that parses the API Gateway proxy event (`InputStream`) into a small domain request object containing the requested file name.
- **File_Name_Validator**: The Java component that validates the requested file name to prevent path traversal and arbitrary S3 object access.
- **Response_Writer**: The Streaming_Core_Library's `ResponseWriter`, invoked from Java, that writes the API Gateway streaming response format — metadata JSON, then an 8-byte null delimiter, then body bytes.
- **S3_Source**: The Java component that confirms existence/size and opens the requested S3 object as an `InputStream` using the AWS SDK for Java v2 (`software.amazon.awssdk:s3`).
- **Bounded_Buffer**: The Streaming_Core_Library's `copy(...)` helper (fixed ~1 MB transfer buffer) used to copy bytes from the S3 object `InputStream` to the Lambda `OutputStream` without materializing the whole object.
- **Priming_Hook**: A CRaC `org.crac.Resource` (written in Java) whose `beforeCheckpoint` exercises the critical path (S3 client init, handler, serialization through the library) so the SnapStart snapshot is warm.
- **SAM_Template**: The AWS SAM `template.yaml` defining the Java Lambda and API Gateway resources for this example.
- **Post_Deploy_Script**: The local/manual verification script that exercises the deployed Java Streaming_Endpoint to prove streaming behavior (seed, warmup, full delivery, first-byte timing, bounded memory, optional teardown).
- **Pipeline_Streaming_Test**: The CI streaming test script run by the pipeline after deploy — seeds a large (>6 MB) object and asserts full byte-identical delivery plus first-byte-before-50% against the deployed Java endpoint.
- **CI_Pipeline**: The GitHub Actions workflows (reusable build, deploy, and streaming-test workflows and the feature/on-demand entry points) that build, cover, validate, deploy, and test the example.
- **OIDC_Role**: The GitHub Actions deployment role (and paired CloudFormation execution role) defined in `deployment/aws/oidc/github-oidc-role.yaml` that the CI_Pipeline assumes to perform the real deploy and streaming test.
- **Real_Deploy**: An actual `sam deploy` of the Java stack to an AWS account (not LocalStack), performed by the CI_Pipeline via the OIDC_Role or locally via the deploy script.
- **Dev_Log**: The running log of hiccups, bugs, gotchas, and fixes maintained in `docs/log.md`.
- **Test_Object**: The approximately 15 MB object stored in S3 used to prove delivery beyond the 6 MB buffered limit.
- **6MB_Limit**: The legacy 6 MB buffered Lambda response payload limit that response streaming bypasses.

## Requirements

### Requirement 1: Consume the streaming-core library from Java

**User Story:** As a maintainer of the library, I want a Java Lambda that drives the library's public API from Java source, so that cross-language (Java) interoperability of the library is proven, not just Kotlin usage.

#### Acceptance Criteria

1. THE Java_Module SHALL depend on the Streaming_Core_Library via `implementation(project(":streaming-core"))` and SHALL contain production sources written in Java under `src/main/java` only (no Kotlin production sources).
2. WHEN the Stream_Handler writes the streaming response, THE Stream_Handler SHALL construct and invoke the Streaming_Core_Library's `ResponseWriter` from Java to write the metadata prelude and the 8-byte delimiter, and SHALL NOT reimplement the wire-format encoding in the Java_Module.
3. WHEN the Stream_Handler builds response metadata, THE Stream_Handler SHALL use the Streaming_Core_Library's `ResponseMetadata` type (constructed directly or via `ResponseMetadata.fromMultiValue`) from Java.
4. WHEN the Stream_Handler streams the S3 object body, THE Stream_Handler SHALL copy bytes using the Streaming_Core_Library's `copy(...)` helper invoked from Java, and SHALL NOT introduce a separate byte-copy loop in the Java_Module.
5. WHERE the Streaming_Core_Library exposes a Kotlin-idiomatic construct that is awkward to call from Java (for example a default-valued or function-typed parameter), THE Java_Module SHALL invoke it through a Java-compatible entry point (a Java-callable overload, a companion accessor, or the generated `*Kt` facade) and the Dev_Log SHALL record any interop friction encountered.

### Requirement 2: Accept and parse the file-streaming request

**User Story:** As a client, I want to issue a GET request that names a file, so that I receive that file's contents from S3.

#### Acceptance Criteria

1. WHEN a GET request arrives at the Streaming_Endpoint, THE Request_Parser SHALL parse the API Gateway proxy event from the input stream into a domain request object containing the requested file name of 1 to 1024 characters.
2. IF the parsed request contains no file name or a file name consisting only of whitespace, THEN THE Stream_Handler SHALL write a response with HTTP status code 400 and an error message indicating that the file name is missing, and SHALL NOT write any file body bytes.
3. IF the API Gateway proxy event cannot be parsed from the input stream, THEN THE Stream_Handler SHALL write a response with HTTP status code 400 and an error message indicating that the request could not be parsed, and SHALL NOT write any file body bytes.
4. IF the requested file name exceeds 1024 characters, THEN THE Stream_Handler SHALL write a response with HTTP status code 400 and an error message indicating that the file name is invalid, and SHALL NOT write any file body bytes.

### Requirement 3: Validate the file name to prevent unauthorized object access

**User Story:** As an operator, I want the requested file name validated, so that clients cannot traverse paths or access arbitrary S3 objects.

#### Acceptance Criteria

1. WHEN a file name is received, THE File_Name_Validator SHALL accept the file name only if every character is an ASCII uppercase letter (A–Z), an ASCII lowercase letter (a–z), an ASCII digit (0–9), a hyphen, an underscore, or a period.
2. IF the file name contains a path separator character (forward slash or backslash), THEN THE File_Name_Validator SHALL reject the file name.
3. IF the file name contains a parent-directory sequence of two consecutive period characters, THEN THE File_Name_Validator SHALL reject the file name.
4. IF the file name contains an absolute-path prefix (a leading forward slash, a leading backslash, or a drive-letter prefix such as a single letter followed by a colon), THEN THE File_Name_Validator SHALL reject the file name.
5. IF the received file name is empty or no file name is present in the request, THEN THE File_Name_Validator SHALL reject the file name.
6. IF the file name exceeds 1024 characters in length, THEN THE File_Name_Validator SHALL reject the file name.
7. IF the File_Name_Validator rejects the file name, THEN THE Stream_Handler SHALL write a response with HTTP status code 400 that indicates the file name was rejected, and SHALL NOT issue any S3 request for the rejected file name.

### Requirement 4: Confirm the S3 object before committing the response status

**User Story:** As a developer, I want object existence and size confirmed before any body is written, so that the committed status code reflects the actual outcome.

#### Acceptance Criteria

1. WHEN a validated file name is received, THE S3_Source SHALL retrieve the existence and size of the corresponding S3 object within 10 seconds before the Response_Writer writes any metadata.
2. IF the requested S3 object does not exist, THEN THE Stream_Handler SHALL write a response with HTTP status code 404 and an error message indicating that the requested object was not found, and SHALL NOT write any body bytes.
3. WHEN the S3 object exists, THE Stream_Handler SHALL, via the Response_Writer, write metadata with HTTP status code 200 and a declared content length equal to the retrieved object size, before writing any body bytes.
4. IF the S3 object existence and size retrieval fails for a reason other than a missing object, or does not complete within 10 seconds, THEN THE Stream_Handler SHALL write a response with HTTP status code 502 and an error message indicating that the retrieval failed, and SHALL NOT write any body bytes.

### Requirement 5: Write the API Gateway streaming response protocol via the library

**User Story:** As a JVM developer, I want the streaming response protocol produced by the shared library, so that the Java example demonstrates the library rather than duplicating the wire format.

#### Acceptance Criteria

1. WHEN writing a successful response, THE Response_Writer SHALL write, in this exact order: a metadata JSON document containing the HTTP status code and response headers, then exactly 8 null bytes as a delimiter, then the body bytes.
2. WHEN building metadata for repeatable or multi-value headers, THE Stream_Handler SHALL use `ResponseMetadata.fromMultiValue` so that repeated headers are collapsed to single values and `Set-Cookie` is routed to the dedicated cookies array.
3. IF writing to the output stream fails after the metadata JSON and the 8 null-byte delimiter have been written, THEN THE failure SHALL propagate and the already-committed HTTP status code SHALL NOT be altered.
4. IF writing to the output stream fails before the metadata JSON and the 8 null-byte delimiter have been fully written, THEN THE failure SHALL propagate and the body bytes SHALL NOT be written.
5. WHEN the handler finishes writing the response, THE Stream_Handler SHALL flush and close the Lambda output stream so that the streaming runtime delivers the complete response and does not truncate the body on fast/warm invocations.

### Requirement 6: Stream the S3 object body with bounded memory

**User Story:** As an operator, I want the object copied through a fixed-size buffer, so that Lambda memory does not grow with the response size.

#### Acceptance Criteria

1. WHEN writing the body, THE S3_Source SHALL copy bytes from the S3 object input stream to the Lambda output stream using the Streaming_Core_Library's `copy(...)` helper, reading each chunk into the Bounded_Buffer before writing it to the output stream.
2. THE Bounded_Buffer SHALL have a fixed size that does not vary with the S3 object size, for all S3 object sizes from 0 bytes up to the maximum supported object size.
3. THE S3_Source SHALL NOT allocate any single in-memory String or byte array holding the complete S3 object body, such that peak buffer memory attributable to body transfer does not exceed the Bounded_Buffer size plus a fixed overhead independent of the S3 object size.
4. WHEN a buffer-sized chunk has been written to the output stream, THE handler SHALL flush the output stream before reading the next chunk, so the client observes progressive delivery with each flushed chunk.
5. FOR ALL S3 objects streamed, the byte sequence written to the client output stream SHALL be byte-identical, in the same order and with the same total byte count, to the byte sequence of the source S3 object body.
6. IF reading from the S3 object input stream or writing to the Lambda output stream fails after streaming has begun, THEN THE S3_Source SHALL stop the copy, release the S3 object input stream, and propagate an error indicating that the transfer failed.

### Requirement 7: Deliver payloads larger than the legacy buffered limit

**User Story:** As an author of the guide, I want a payload larger than 6 MB delivered successfully from the Java Lambda, so that bypassing the buffered limit is proven for Java too.

#### Acceptance Criteria

1. WHEN the Test_Object of approximately 15 MB is requested, THE Streaming_Endpoint SHALL deliver a response with HTTP status code 200 whose total received body byte count equals the Test_Object's stored size in S3.
2. WHEN the Test_Object is requested, THE Streaming_Endpoint SHALL deliver every byte of the source object body without truncation, such that the received body length equals the source object size even though that size exceeds the 6MB_Limit.
3. IF the Test_Object body transfer fails after the response status has been committed, THEN THE Streaming_Endpoint SHALL terminate the response without writing further body bytes, so that the received body byte count is less than the Test_Object's stored size and the delivery is detectable as incomplete.

### Requirement 8: Apply cold-start optimizations to the Java Lambda

**User Story:** As an operator, I want SnapStart and priming configured, so that cold-start latency matches the MockNest/Kotlin baseline.

#### Acceptance Criteria

1. THE SAM_Template SHALL configure the Lambda with an auto-published alias named `live` and SnapStart with apply-on set to published versions, such that every published version produces a SnapStart snapshot resolvable through the `live` alias.
2. WHILE the SnapStart snapshot is being created during the before-checkpoint phase, THE Priming_Hook SHALL exercise, in a single pass, each of the following critical paths: S3 client initialization, one invocation of the handler against a primed request, and serialization of the response metadata through the Streaming_Core_Library.
3. IF any critical path exercised by the Priming_Hook raises an error during the before-checkpoint phase, THEN THE Priming_Hook SHALL propagate the error so that snapshot creation fails and no Lambda version is published, with an error indication identifying the failed priming path.
4. THE SAM_Template SHALL configure, through the global configuration, the Lambda architecture as arm64 and the runtime as Java 25, without per-function overrides of either value.
5. THE SAM_Template SHALL set the Lambda environment variable `JAVA_TOOL_OPTIONS` to the value enabling tiered compilation with the tiered stop-at level set to 1 (`-XX:+TieredCompilation -XX:TieredStopAtLevel=1`).

### Requirement 9: Configure API Gateway for response streaming

**User Story:** As an operator, I want API Gateway configured for response streaming, so that the response is not buffered at the gateway layer.

#### Acceptance Criteria

1. THE SAM_Template SHALL define a REST API with a proxy integration that routes the incoming GET request to the Java Lambda, such that the resource path and the integrated Lambda target are both present in the template.
2. THE SAM_Template SHALL set the `ResponseTransferMode` property on the API event to the exact value `RESPONSE_STREAM`.
3. IF the `ResponseTransferMode` property is set to any value other than `RESPONSE_STREAM` (including the lower-level integration value `STREAM`), THEN THE SAM_Template SHALL be treated as invalid for response streaming and SHALL NOT be committed.
4. WHEN the SAM_Template is changed, THE developer SHALL run SAM template validation and confirm the validation process returns exit code 0 before committing the change.
5. IF SAM template validation returns any exit code other than 0, THEN THE developer SHALL NOT commit the change and SHALL resolve the reported validation error first.

### Requirement 10: Prove streaming behavior against the deployed Java endpoint

**User Story:** As an author of the guide, I want a post-deploy script that measures streaming behavior for the Java endpoint, so that success is proven against a real deployment exactly as for the Kotlin example.

#### Acceptance Criteria

1. WHEN the Post_Deploy_Script runs against the deployed Java Streaming_Endpoint, THE Post_Deploy_Script SHALL confirm that a single payload larger than the 6MB_Limit (an object of at least 15 MB) is delivered in full and is byte-identical to the source object.
2. WHEN the Post_Deploy_Script issues a warmup request to the Streaming_Endpoint before any timing measurement, THE Post_Deploy_Script SHALL discard the warmup result and exclude it from all reported timings.
3. WHEN the Post_Deploy_Script runs against the deployed Streaming_Endpoint after the warmup request, THE Post_Deploy_Script SHALL measure the time to first byte and confirm that the first byte arrives no later than 50% of the total response completion time, proving progressive (non-buffered) delivery.
4. WHEN the Post_Deploy_Script runs against the deployed Streaming_Endpoint, THE Post_Deploy_Script SHALL confirm that the Lambda maximum memory used for the at-least-15 MB payload does not exceed the maximum memory used for a payload at the 6MB_Limit by more than 10%.
5. IF the Streaming_Endpoint is unreachable or returns a non-success response, THEN THE Post_Deploy_Script SHALL terminate with a non-zero exit code and an error message indicating the failure, without reporting success.
6. IF the delivered payload size or content does not match the source object, THEN THE Post_Deploy_Script SHALL terminate with a non-zero exit code and an error message indicating the mismatch.
7. THE Post_Deploy_Script SHALL force HTTP/1.1 for all endpoint requests so that API Gateway chunked-stream termination does not produce a spurious HTTP/2 stream-close failure.

### Requirement 11: Secure the S3 storage and Lambda access

**User Story:** As a security reviewer, I want least-privilege access and encrypted private storage, so that the example follows secure defaults.

#### Acceptance Criteria

1. THE SAM_Template SHALL grant the Lambda execution role permission for the `s3:GetObject` action only, with the resource scoped to the specific source bucket ARN and its objects, and SHALL NOT grant any other S3 action or use a wildcard (`*`) resource.
2. THE SAM_Template SHALL configure the S3 bucket with default server-side encryption enabled so that every object is encrypted at rest.
3. THE SAM_Template SHALL configure the S3 bucket with all four Block Public Access settings (BlockPublicAcls, IgnorePublicAcls, BlockPublicPolicy, RestrictPublicBuckets) set to true.
4. WHEN a client requests the Streaming_Endpoint over HTTPS (TLS), THE Streaming_Endpoint SHALL serve the response over that HTTPS connection.
5. IF a client requests the Streaming_Endpoint over plain HTTP (non-TLS), THEN THE Streaming_Endpoint SHALL reject the request without serving object content and return a response indicating that a secure (HTTPS) connection is required.
6. THE SAM_Template SHALL configure the Lambda CloudWatch log group with a finite retention period between 1 and 30 days inclusive.

### Requirement 12: Control infrastructure cost

**User Story:** As an operator, I want bounded, finite-cost resources, so that the example does not incur standing or runaway charges.

#### Acceptance Criteria

1. THE SAM_Template SHALL set the Lambda memory size to a bounded value between 128 MB and 1024 MB inclusive.
2. THE SAM_Template SHALL set the Lambda timeout to a bounded value between 1 and 300 seconds inclusive, not exceeding the API Gateway regional idle timeout of 300 seconds.
3. THE SAM_Template SHALL NOT configure provisioned concurrency or other always-on capacity.
4. THE SAM_Template SHALL set the CloudWatch log retention to a finite period between 1 and 30 days inclusive and SHALL NOT leave retention set to never-expire.
5. THE SAM_Template SHALL configure the S3 bucket with a lifecycle rule that expires current test objects after a finite period between 1 and 30 days inclusive.
6. THE SAM_Template SHALL configure the S3 bucket with a lifecycle rule that expires non-current object versions after a finite period between 1 and 30 days inclusive.
7. WHERE deployed resources were created for verification, THE Post_Deploy_Script or its documentation SHALL provide a teardown step that removes the deployed stack, with a success indication confirming that no stack resources remain.

### Requirement 13: Match the Kotlin example's layered test strategy

**User Story:** As an author of the guide, I want the Java example tested in the same three layers as the Kotlin example, so that the two examples are directly comparable and the library is verified identically.

#### Acceptance Criteria

1. THE Java_Module SHALL provide unit and property-based tests using JUnit (Jupiter) with `@ParameterizedTest` cases for the universal properties, mirroring the Kotlin example's property coverage: metadata round-trip through the library, byte-identical bounded-buffer streaming, bounded transfer memory, file-name validation biconditional with zero S3 access on rejection, and status-committed-once ordering.
2. THE Java_Module SHALL provide an integration test using TestContainers + LocalStack that streams both a sub-6 MB object and an approximately 15 MB Test_Object end-to-end through the handler against a containerized S3, asserting byte-identical delivery and, for the ~15 MB object, HTTP 200 with received size equal to stored size.
3. THE Java_Module SHALL enforce a code-coverage gate consistent with the Kotlin example's configured threshold via `koverVerify` (or the module's configured coverage tool), and SHALL fail the build if the threshold is not met.
4. WHEN the Java_Module's tests are run, THE integration tests SHALL be independently selectable/excludable by tag so that unit and property tests can run without a container runtime.

### Requirement 14: Capture development knowledge

**User Story:** As an author of the guide, I want every gotcha recorded, so that the running log can feed the final article.

#### Acceptance Criteria

1. WHEN a hiccup, bug, gotcha, or fix (including any Java-from-Kotlin interop friction) is encountered during development, THE developer SHALL append an entry to the Dev_Log that contains a title summarizing the issue, a description of the symptom or trigger, and the resolution or current status, within the same development session in which it is encountered.
2. THE Dev_Log SHALL NOT contain runtime secrets, where runtime secrets include credentials, access tokens, bucket names or resource identifiers used as secrets, and authorization header values.
3. WHERE a development detail must be referenced in the Dev_Log but qualifies as a runtime secret, THE developer SHALL record it by descriptive name or placeholder rather than by its literal value.
4. IF an entry being appended to the Dev_Log is found to contain a runtime secret, THEN THE developer SHALL redact the secret value before the entry is committed, retaining the remaining non-secret content of the entry.

### Requirement 15: Build, validate, and coverage-check the Java example in CI

**User Story:** As a maintainer, I want the Java example built, tested, coverage-checked, and its SAM template validated by the same CI pipeline as the Kotlin example, so that a regression in the Java example or its template fails the build before any deploy.

#### Acceptance Criteria

1. THE CI_Pipeline build stage SHALL run the Java_Module's unit and property tests with the integration-tagged tests excluded, so the build stage requires no container runtime.
2. THE CI_Pipeline build stage SHALL run the Java_Module's coverage verification and SHALL fail the build if the configured coverage threshold is not met.
3. THE CI_Pipeline build stage SHALL validate the Java example's SAM_Template with `sam validate` and SHALL fail the build if validation returns a non-zero exit code.
4. WHERE the CI_Pipeline runs on a change that does not touch the Java_Module, THE CI_Pipeline SHALL still build and validate the Java example (no silent skipping), matching how the Kotlin example is always built.
5. THE Java_Module's build SHALL produce a single deployable fat jar artifact named distinctly from the Kotlin example's artifact, so both can be built in the same repository without overwriting each other.

### Requirement 16: Perform a real OIDC-authenticated deploy of the Java stack in the pipeline

**User Story:** As an operator, I want the pipeline to deploy the Java Lambda to a real AWS account using OIDC, so that the library is proven on the managed Lambda runtime, not just locally.

#### Acceptance Criteria

1. WHEN the CI_Pipeline deploy stage runs, THE deploy stage SHALL obtain AWS credentials by assuming the OIDC_Role via GitHub OIDC (short-lived credentials only) and SHALL NOT use long-lived static access keys.
2. WHEN the deploy stage builds the deployable artifact, THE deploy stage SHALL build the Java_Module's fat jar and deploy the Java stack with `sam deploy`, using the shared SAM artifacts bucket and the CloudFormation execution role rather than ad-hoc resources.
3. THE OIDC_Role and its paired CloudFormation execution role SHALL be scoped to authorize the Java stack's resources — its CloudFormation stack name, its S3 source bucket name pattern, and its Lambda log group — without granting broader wildcard access than the existing Kotlin deployment pattern uses.
4. WHEN the Java stack deploy completes, THE deploy stage SHALL expose the Java Streaming_Endpoint URL and source bucket name (for example as CloudFormation stack outputs) so the subsequent streaming test can resolve them.
5. IF the Java stack deploy fails for any reason other than "no changes to deploy", THEN THE deploy stage SHALL terminate with a non-zero exit code and SHALL NOT run the streaming test stage.
6. THE Real_Deploy of the Java stack SHALL NOT introduce always-on or standing-cost resources (no provisioned concurrency), reusing the managed artifacts bucket, and SHALL remain removable via the teardown step (Req 12.7).

### Requirement 17: Run the pipeline streaming test against the deployed Java endpoint

**User Story:** As an author of the guide, I want the pipeline to prove streaming against the live Java endpoint after every deploy, so that success is verified automatically on the real runtime for Java too.

#### Acceptance Criteria

1. WHEN the CI_Pipeline streaming-test stage runs, THE stage SHALL assume the OIDC_Role and resolve the deployed Java Streaming_Endpoint, source bucket, and API key from the Java stack's outputs.
2. THE Pipeline_Streaming_Test SHALL seed a test object larger than the 6MB_Limit into the deployed Java bucket when it is missing or undersized, and SHALL confirm the object is delivered in full and byte-identical to the source.
3. THE Pipeline_Streaming_Test SHALL measure time-to-first-byte after a discarded warmup request and confirm the first byte arrives no later than 50% of the total response completion time.
4. THE Pipeline_Streaming_Test SHALL force HTTP/1.1 for all endpoint requests so API Gateway chunked-stream termination does not produce a spurious HTTP/2 stream-close failure.
5. IF the deployed Java endpoint is unreachable, returns a non-success status, or the delivered payload does not match the source, THEN THE Pipeline_Streaming_Test SHALL terminate with a non-zero exit code and SHALL NOT report success.
6. WHERE the CI_Pipeline masks secrets, THE Pipeline_Streaming_Test SHALL mask the resolved API key in the pipeline logs and SHALL NOT print it.
7. THE Pipeline_Streaming_Test SHALL be functionally equivalent to the Kotlin example's `scripts/pipeline-streaming-test.sh` — the same stages (resolve config from stack outputs, idempotent seed-if-missing, large-payload full byte-identical delivery check, and progressive TTFB-below-50% check), the same environment-variable configuration surface (stack name, region, endpoint, bucket, object key/size), and the same exit-code semantics — differing only in the values that target the Java stack (stack name, object key, and any Java-specific defaults).
8. THE Java example's pipeline streaming test SHALL run as a distinct pipeline stage that executes after the Java deploy stage and only when that deploy stage succeeds, mirroring the Kotlin example's deploy-then-streaming-test stage ordering.
