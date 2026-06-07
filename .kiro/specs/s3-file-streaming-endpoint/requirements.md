# Requirements Document

## Introduction

This feature is a minimal, runnable "hello world" example that proves HTTP response streaming works from a Kotlin/JVM AWS Lambda behind Amazon API Gateway. A single Lambda accepts a GET request carrying a file name, opens the corresponding object in S3, and streams that object's bytes back to the client through API Gateway response streaming — without ever holding the whole object in memory.

The example deliberately uses a large test object (~15 MB) to prove the implementation gets past the old 6 MB buffered Lambda response limit and any API Gateway buffering, and that the body is delivered progressively with bounded memory. AWS's streaming helpers are Node.js-only, so on the JVM the API Gateway streaming response protocol (metadata JSON, an 8 null-byte delimiter, then body bytes) is implemented by hand using a `RequestStreamHandler`.

Scope is intentionally small: one Lambda, one S3 source object, one streaming endpoint, a single Gradle module, no business logic beyond demonstrating streaming. The example must reuse the project's coding standards, cold-start optimizations (SnapStart, CRaC priming, tiered compilation, arm64/Java 25), and SAM deploy approach. Success is only "proven" against a deployed endpoint via a post-deploy verification script. This document handles NO PII and uses NO AI/Bedrock.

## Glossary

- **Streaming_Endpoint**: The HTTP GET endpoint exposed through API Gateway that returns an S3 object as a streamed response body.
- **Stream_Handler**: The Kotlin component implementing `RequestStreamHandler` that reads the API Gateway event from an `InputStream` and writes the response to an `OutputStream`.
- **Request_Parser**: The component that parses the API Gateway proxy event (`InputStream`) into a small domain request object containing the requested file name.
- **File_Name_Validator**: The component that validates the requested file name to prevent path traversal and arbitrary S3 object access.
- **Response_Writer**: The component that writes the API Gateway streaming response format — metadata JSON, then an 8-byte null delimiter (`ByteArray(8)`), then body bytes.
- **S3_Source**: The component that opens the requested S3 object as an `InputStream` using the Kotlin AWS SDK (`aws.sdk.kotlin:s3`).
- **Bounded_Buffer**: A fixed-size transfer buffer (approximately 1 MB) used to copy bytes from the S3 object `InputStream` to the Lambda `OutputStream` without materializing the whole object.
- **Priming_Hook**: A CRaC `org.crac.Resource` whose `beforeCheckpoint` exercises the critical path (S3 client init, handler, serialization) so the SnapStart snapshot is warm.
- **SAM_Template**: The AWS SAM `template.yaml` defining the Lambda and API Gateway resources under `deployment/aws/sam/`.
- **Post_Deploy_Script**: The verification script (`scripts/post-deploy-test.sh`) that exercises the deployed endpoint to prove streaming behavior.
- **Dev_Log**: The running log of hiccups, bugs, gotchas, and fixes maintained in `docs/log.md`.
- **Test_Object**: The approximately 15 MB object stored in S3 used to prove delivery beyond the 6 MB buffered limit.
- **6MB_Limit**: The legacy 6 MB buffered Lambda response payload limit that response streaming bypasses.

## Requirements

### Requirement 1: Accept and parse the file-streaming request

**User Story:** As a client, I want to issue a GET request that names a file, so that I receive that file's contents from S3.

#### Acceptance Criteria

1. WHEN a GET request arrives at the Streaming_Endpoint, THE Request_Parser SHALL parse the API Gateway proxy event from the input stream into a domain request object containing the requested file name of 1 to 1024 characters.
2. IF the parsed request contains no file name or a file name consisting only of whitespace, THEN THE Stream_Handler SHALL write a response with HTTP status code 400 and an error message indicating that the file name is missing, and SHALL NOT write any file body bytes.
3. IF the API Gateway proxy event cannot be parsed from the input stream, THEN THE Stream_Handler SHALL write a response with HTTP status code 400 and an error message indicating that the request could not be parsed, and SHALL NOT write any file body bytes.
4. IF the requested file name exceeds 1024 characters, THEN THE Stream_Handler SHALL write a response with HTTP status code 400 and an error message indicating that the file name is invalid, and SHALL NOT write any file body bytes.

### Requirement 2: Validate the file name to prevent unauthorized object access

**User Story:** As an operator, I want the requested file name validated, so that clients cannot traverse paths or access arbitrary S3 objects.

#### Acceptance Criteria

1. WHEN a file name is received, THE File_Name_Validator SHALL accept the file name only if every character is an ASCII uppercase letter (A–Z), an ASCII lowercase letter (a–z), an ASCII digit (0–9), a hyphen, an underscore, or a period.
2. IF the file name contains a path separator character (forward slash or backslash), THEN THE File_Name_Validator SHALL reject the file name.
3. IF the file name contains a parent-directory sequence of two consecutive period characters, THEN THE File_Name_Validator SHALL reject the file name.
4. IF the file name contains an absolute-path prefix (a leading forward slash, a leading backslash, or a drive-letter prefix such as a single letter followed by a colon), THEN THE File_Name_Validator SHALL reject the file name.
5. IF the received file name is empty or no file name is present in the request, THEN THE File_Name_Validator SHALL reject the file name.
6. IF the file name exceeds 1024 characters in length, THEN THE File_Name_Validator SHALL reject the file name.
7. IF the File_Name_Validator rejects the file name, THEN THE Stream_Handler SHALL write a response with HTTP status code 400 that indicates the file name was rejected, and SHALL NOT issue any S3 request for the rejected file name.

### Requirement 3: Confirm the S3 object before committing the response status

**User Story:** As a developer, I want object existence and size confirmed before any body is written, so that the committed status code reflects the actual outcome.

#### Acceptance Criteria

1. WHEN a validated file name is received, THE S3_Source SHALL retrieve the existence and size of the corresponding S3 object within 10 seconds before the Response_Writer writes any metadata.
2. IF the requested S3 object does not exist, THEN THE Stream_Handler SHALL write a response with HTTP status code 404 and an error message indicating that the requested object was not found, and SHALL NOT write any body bytes.
3. WHEN the S3 object exists, THE Response_Writer SHALL write metadata with HTTP status code 200, with the declared content length equal to the retrieved object size, before writing any body bytes.
4. IF the S3 object existence and size retrieval fails for a reason other than a missing object, or does not complete within 10 seconds, THEN THE Stream_Handler SHALL write a response with HTTP status code 502 and an error message indicating that the retrieval failed, and SHALL NOT write any body bytes.

### Requirement 4: Write the API Gateway streaming response protocol

**User Story:** As a JVM developer, I want the streaming response protocol implemented explicitly, so that the response is delivered through API Gateway response streaming without a Node.js helper.

#### Acceptance Criteria

1. WHEN writing a response, THE Response_Writer SHALL write, in this exact order: a metadata JSON document containing the HTTP status code and response headers, then exactly 8 null bytes (a `ByteArray(8)` of zero-valued bytes) as a delimiter, then the body bytes.
2. THE Response_Writer SHALL represent repeatable response headers as a mapping from header name to a list of header values, where the list contains one entry per header value and an empty list represents a header with no values.
3. WHEN serializing response metadata, THE Response_Writer SHALL use kotlinx-serialization-json.
4. WHEN metadata JSON previously written by the Response_Writer is decoded, THE Response_Writer SHALL produce a metadata object equal to the original metadata object, preserving the HTTP status code and every header name-to-value-list entry (round-trip property).
5. IF writing to the output stream fails after the metadata JSON and the 8 null-byte delimiter have been written, THEN THE Response_Writer SHALL propagate an error indicating the write failure and SHALL NOT alter the already-committed HTTP status code.
6. IF writing to the output stream fails before the metadata JSON and the 8 null-byte delimiter have been fully written, THEN THE Response_Writer SHALL propagate an error indicating the write failure and SHALL NOT write the body bytes.

### Requirement 5: Stream the S3 object body with bounded memory

**User Story:** As an operator, I want the object copied through a fixed-size buffer, so that Lambda memory does not grow with the response size.

#### Acceptance Criteria

1. WHEN writing the body, THE S3_Source SHALL copy bytes from the S3 object input stream to the Lambda output stream in chunks, where each chunk is read into the Bounded_Buffer before being written to the output stream.
2. THE Bounded_Buffer SHALL have a fixed size of 1,048,576 bytes (1 MB) that does not vary with the S3 object size, for all S3 object sizes from 0 bytes up to the maximum supported object size.
3. THE S3_Source SHALL NOT allocate any single in-memory String or byte array holding the complete S3 object body, such that peak buffer memory attributable to body transfer does not exceed the Bounded_Buffer size of 1,048,576 bytes plus a fixed overhead independent of the S3 object size.
4. WHEN a buffer-sized chunk has been written to the output stream, THE Response_Writer SHALL flush the output stream before reading the next chunk, so the client observes progressive delivery with each flushed chunk.
5. WHEN the final partial chunk (fewer than 1,048,576 bytes) has been written to the output stream, THE Response_Writer SHALL flush the output stream.
6. FOR ALL S3 objects streamed, the byte sequence written to the client output stream SHALL be byte-identical, in the same order and with the same total byte count, to the byte sequence of the source S3 object body.
7. IF reading from the S3 object input stream or writing to the Lambda output stream fails after streaming has begun, THEN THE S3_Source SHALL stop the copy, release the S3 object input stream, and propagate an error indicating that the transfer failed.

### Requirement 6: Deliver payloads larger than the legacy buffered limit

**User Story:** As an author of the guide, I want a payload larger than 6 MB delivered successfully, so that bypassing the buffered limit is proven.

#### Acceptance Criteria

1. WHEN the Test_Object of approximately 15 MB is requested, THE Streaming_Endpoint SHALL deliver a response with HTTP status code 200 whose total received body byte count equals the Test_Object's stored size in S3.
2. WHEN the Test_Object is requested, THE Streaming_Endpoint SHALL deliver every byte of the source object body without truncation, such that the received body length equals the source object size even though that size exceeds the 6MB_Limit.
3. IF the Test_Object body transfer fails after the response status has been committed, THEN THE Streaming_Endpoint SHALL terminate the response without writing further body bytes, so that the received body byte count is less than the Test_Object's stored size and the delivery is detectable as incomplete.

### Requirement 7: Apply cold-start optimizations to the Lambda

**User Story:** As an operator, I want SnapStart and priming configured, so that cold-start latency matches the MockNest baseline.

#### Acceptance Criteria

1. THE SAM_Template SHALL configure the Lambda with an auto-published alias named `live` and SnapStart with apply-on set to published versions, such that every published version produces a SnapStart snapshot resolvable through the `live` alias.
2. WHILE the SnapStart snapshot is being created during the before-checkpoint phase, THE Priming_Hook SHALL exercise, in a single pass, each of the following critical paths: S3 client initialization, one invocation of the handler against a primed request, and serialization of the response metadata.
3. IF any critical path exercised by the Priming_Hook raises an error during the before-checkpoint phase, THEN THE Priming_Hook SHALL propagate the error so that snapshot creation fails and no Lambda version is published, with an error indication identifying the failed priming path.
4. THE SAM_Template SHALL configure, through the global configuration, the Lambda architecture as arm64 and the runtime as Java 25, without per-function overrides of either value.
5. THE SAM_Template SHALL set the Lambda environment variable `JAVA_TOOL_OPTIONS` to the value enabling tiered compilation with the tiered stop-at level set to 1 (`-XX:+TieredCompilation -XX:TieredStopAtLevel=1`).

### Requirement 8: Configure API Gateway for response streaming

**User Story:** As an operator, I want API Gateway configured for response streaming, so that the response is not buffered at the gateway layer.

#### Acceptance Criteria

1. THE SAM_Template SHALL define a REST API with a proxy integration that routes the incoming GET request to the Lambda, such that the resource path and the integrated Lambda target are both present in the template.
2. THE SAM_Template SHALL set the `ResponseTransferMode` property on the API event to the exact value `RESPONSE_STREAM`.
3. IF the `ResponseTransferMode` property is set to any value other than `RESPONSE_STREAM` (including the lower-level integration value `STREAM`), THEN THE SAM_Template SHALL be treated as invalid for response streaming and SHALL NOT be committed.
4. WHEN the SAM_Template is changed, THE developer SHALL run SAM template validation and confirm the validation process returns exit code 0 before committing the change.
5. IF SAM template validation returns any exit code other than 0, THEN THE developer SHALL NOT commit the change and SHALL resolve the reported validation error first.

### Requirement 9: Prove streaming behavior against the deployed endpoint

**User Story:** As an author of the guide, I want a post-deploy script that measures streaming behavior, so that success is proven against a real deployment.

#### Acceptance Criteria

1. WHEN the Post_Deploy_Script runs against the deployed Streaming_Endpoint, THE Post_Deploy_Script SHALL confirm that a single payload larger than the 6MB_Limit (an object of at least 15 MB) is delivered in full and is byte-identical to the source object.
2. WHEN the Post_Deploy_Script issues a warmup request to the Streaming_Endpoint before any timing measurement, THE Post_Deploy_Script SHALL discard the warmup result and exclude it from all reported timings.
3. WHEN the Post_Deploy_Script runs against the deployed Streaming_Endpoint after the warmup request, THE Post_Deploy_Script SHALL measure the time to first byte and confirm that the first byte arrives no later than 50% of the total response completion time, proving progressive (non-buffered) delivery.
4. WHEN the Post_Deploy_Script runs against the deployed Streaming_Endpoint, THE Post_Deploy_Script SHALL confirm that the Lambda maximum memory used for the at-least-15 MB payload does not exceed the maximum memory used for a payload at the 6MB_Limit by more than 10%.
5. IF the Streaming_Endpoint is unreachable or returns a non-success response, THEN THE Post_Deploy_Script SHALL terminate with a non-zero exit code and an error message indicating the failure, without reporting success.
6. IF the delivered payload size or content does not match the source object, THEN THE Post_Deploy_Script SHALL terminate with a non-zero exit code and an error message indicating the mismatch.

### Requirement 10: Secure the S3 storage and Lambda access

**User Story:** As a security reviewer, I want least-privilege access and encrypted private storage, so that the example follows secure defaults.

#### Acceptance Criteria

1. THE SAM_Template SHALL grant the Lambda execution role permission for the `s3:GetObject` action only, with the resource scoped to the specific source bucket ARN and its objects, and SHALL NOT grant any other S3 action or use a wildcard (`*`) resource.
2. THE SAM_Template SHALL configure the S3 bucket with default server-side encryption enabled so that every object is encrypted at rest.
3. THE SAM_Template SHALL configure the S3 bucket with all four Block Public Access settings (BlockPublicAcls, IgnorePublicAcls, BlockPublicPolicy, RestrictPublicBuckets) set to true.
4. WHEN a client requests the Streaming_Endpoint over HTTPS (TLS), THE Streaming_Endpoint SHALL serve the response over that HTTPS connection.
5. IF a client requests the Streaming_Endpoint over plain HTTP (non-TLS), THEN THE Streaming_Endpoint SHALL reject the request without serving object content and return a response indicating that a secure (HTTPS) connection is required.
6. THE SAM_Template SHALL configure the Lambda CloudWatch log group with a finite retention period of 30 days.

### Requirement 11: Control infrastructure cost

**User Story:** As an operator, I want bounded, finite-cost resources, so that the example does not incur standing or runaway charges.

#### Acceptance Criteria

1. THE SAM_Template SHALL set the Lambda memory size to a bounded value between 128 MB and 1024 MB inclusive.
2. THE SAM_Template SHALL set the Lambda timeout to a bounded value between 1 and 300 seconds inclusive, not exceeding the API Gateway regional idle timeout of 300 seconds.
3. THE SAM_Template SHALL NOT configure provisioned concurrency or other always-on capacity.
4. THE SAM_Template SHALL set the CloudWatch log retention to a finite period between 1 and 30 days inclusive and SHALL NOT leave retention set to never-expire.
5. THE SAM_Template SHALL configure the S3 bucket with a lifecycle rule that expires current test objects after a finite period between 1 and 30 days inclusive.
6. THE SAM_Template SHALL configure the S3 bucket with a lifecycle rule that expires non-current object versions after a finite period between 1 and 30 days inclusive.
7. WHERE deployed resources were created for verification, THE Post_Deploy_Script or its documentation SHALL provide a teardown step that removes the deployed stack, with a success indication confirming that no stack resources remain.

### Requirement 12: Capture development knowledge

**User Story:** As an author of the guide, I want every gotcha recorded, so that the running log can feed the final article.

#### Acceptance Criteria

1. WHEN a hiccup, bug, gotcha, or fix is encountered during development, THE developer SHALL append an entry to the Dev_Log that contains a title summarizing the issue, a description of the symptom or trigger, and the resolution or current status, within the same development session in which it is encountered.
2. THE Dev_Log SHALL NOT contain runtime secrets, where runtime secrets include credentials, access tokens, bucket names or resource identifiers used as secrets, and authorization header values.
3. WHERE a development detail must be referenced in the Dev_Log but qualifies as a runtime secret, THE developer SHALL record it by descriptive name or placeholder rather than by its literal value.
4. IF an entry being appended to the Dev_Log is found to contain a runtime secret, THEN THE developer SHALL redact the secret value before the entry is committed, retaining the remaining non-secret content of the entry.
