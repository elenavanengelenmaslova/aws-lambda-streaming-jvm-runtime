# streaming-s3-example

A complete AWS Lambda deployment that streams files from S3 using the [`aws-lambda-streaming-core`](../README.md) library.

## What It Does

1. Accepts a GET request naming a file (from the API Gateway `/{proxy+}` path parameter)
2. Validates the file name (allow-list, no path traversal) via `FileKeyResolver`
3. Confirms the S3 object exists (`headObject` with 60s timeout) via `S3Source`
4. Streams the object through a fixed 1 MB buffer with per-chunk flush
5. Delivers payloads well past the legacy 6 MB buffered Lambda limit

## Stack

- **Kotlin 2.3.x / Java 25** (Gradle 9, multi-module)
- **AWS SAM** — Lambda + API Gateway REST (RESPONSE_STREAM) + S3
- **SnapStart** + CRaC priming + arm64 + tiered compilation (L1)
- **TestContainers + LocalStack** for integration tests (Colima)

## Quick Start

### Prerequisites

- JDK 25 (temurin)
- AWS CLI + SAM CLI
- Colima running (for integration tests)

### Build and Test

```bash
# Unit + property tests (no LocalStack needed)
./gradlew :streaming-s3-example:test -PexcludeTags=integration

# All tests including integration (requires Colima)
./gradlew :streaming-s3-example:test

# Coverage report
./gradlew :streaming-s3-example:koverHtmlReport :streaming-s3-example:koverVerify

# Validate SAM template
sam validate --template-file deployment/aws/sam/template.yaml
```

### Deploy Manually

```bash
cd deployment/aws/sam
./build.sh    # builds streaming-endpoint.jar via :streaming-s3-example:shadowJar
./deploy.sh   # sam build + sam deploy
```

### Seed a Test Object and Verify

```bash
# Seeds a 12 MB object if not present, then verifies streaming
./scripts/pipeline-streaming-test.sh
```

## CI/CD Pipeline (GitHub Actions)

```
test (build + coverage + SAM validate)
  → deploy (OIDC → build JAR → sam deploy)
    → streaming-tests (seed 12 MB → verify delivery + TTFB)
```

### One-Time Setup

1. **Bootstrap the OIDC role** (run once from your local machine with AWS credentials):

   ```bash
   ./scripts/setup-oidc.sh
   ```

   This creates:
   - `StreamingExampleGitHubActionsRole` — GitHub Actions assumes this via OIDC
   - `StreamingCloudFormationExecutionRole` — CloudFormation assumes this to create stack resources
   - `streaming-sam-artifacts-<account>-<region>` — S3 bucket for deployment artifacts

2. **Add to GitHub repository settings** (Settings → Secrets and variables → Actions):

   | Name | Type | Value |
   |------|------|-------|
   | `AWS_ACCOUNT_ID` | Secret | Your 12-digit AWS account ID |
   | `OIDC_ROLE_NAME` | Variable | `StreamingExampleGitHubActionsRole` |

3. **Trigger the pipeline**: Actions → "CD - Deploy On Demand" → Run workflow

### Pipeline Inputs

| Input | Default | Description |
|-------|---------|-------------|
| `stack-name` | (from samconfig.toml) | CloudFormation stack name override |
| `aws-region` | `eu-west-1` | AWS region |
| `github-actions-role-name` | (from OIDC_ROLE_NAME variable) | OIDC role name override |

## Project Structure

```
streaming-s3-example/
├── src/main/kotlin/nl/vintik/lambda/streaming/
│   ├── FileRequest.kt          # Typed request data class
│   ├── FileKeyResolver.kt      # RequestResolver: parses proxy event + validates file name
│   ├── S3Source.kt             # StreamSource: headObject + getObject via AWS SDK
│   └── Priming.kt              # CRaC warm-up hook for SnapStart
├── src/test/kotlin/
│   ├── FileKeyResolverTest.kt
│   ├── FileNameValidatorTest.kt
│   ├── FileNameValidationZeroS3PropertyTest.kt
│   ├── RequestParserTest.kt
│   ├── S3SourceTest.kt
│   ├── PrimingTest.kt
│   └── *IntegrationTest.kt     # Require LocalStack (tag: integration)
deployment/aws/sam/             # SAM template + config + build/deploy scripts
deployment/aws/oidc/            # OIDC bootstrap CloudFormation template
scripts/                        # setup-oidc.sh, pipeline-streaming-test.sh, post-deploy-test.sh
```

## Try It Yourself — Stream a Large File

After deploying, upload any large file to the S3 source bucket and stream it back through the endpoint.

### 1. Get the bucket name and API key

```bash
BUCKET_NAME=$(aws cloudformation describe-stacks \
  --stack-name s3-file-streaming-endpoint \
  --query "Stacks[0].Outputs[?OutputKey=='SourceBucketName'].OutputValue" \
  --output text)

API_KEY_ID=$(aws cloudformation describe-stacks \
  --stack-name s3-file-streaming-endpoint \
  --query "Stacks[0].Outputs[?OutputKey=='ApiKeyId'].OutputValue" \
  --output text)

API_KEY=$(aws apigateway get-api-key --api-key "$API_KEY_ID" --include-value \
  --query 'value' --output text)

ENDPOINT_URL=$(aws cloudformation describe-stacks \
  --stack-name s3-file-streaming-endpoint \
  --query "Stacks[0].Outputs[?OutputKey=='StreamingEndpointUrl'].OutputValue" \
  --output text)
```

### 2. Upload a large file

```bash
aws s3 cp my-large-file.bin "s3://$BUCKET_NAME/my-large-file.bin"
```

### 3. Stream it back

```bash
curl -o streamed_output.bin \
  -H "x-api-key: $API_KEY" \
  -w '\nHTTP %{http_code} | First byte: %{time_starttransfer}s | Total: %{time_total}s | Size: %{size_download} bytes\n' \
  "${ENDPOINT_URL}my-large-file.bin"
```

The first byte arrives well before the full payload is delivered — proving real streaming, not buffered delivery.

### 4. Verify byte integrity

```bash
cmp my-large-file.bin streamed_output.bin \
  && echo "PASS: byte-identical" \
  || echo "FAIL: content mismatch"
```
