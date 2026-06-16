# AWS Lambda Streaming JVM Runtime

A minimal example proving **HTTP response streaming** from a **Kotlin/JVM AWS Lambda** behind Amazon API Gateway — streaming a 12+ MB S3 object to the client without ever buffering the whole body in memory.

AWS's streaming helpers (`awslambda.HttpResponseStream.from()`) are Node.js-only. On the JVM the API Gateway streaming response protocol (metadata JSON → 8 null bytes → body) is implemented by hand using a `RequestStreamHandler`.

## What It Does

1. Accepts a GET request naming a file
2. Validates the file name (allow-list, no path traversal)
3. Confirms the S3 object exists (headObject with 10s timeout)
4. Streams the object through a fixed 1 MB buffer with per-chunk flush
5. Delivers payloads well past the legacy 6 MB buffered Lambda limit

## Stack

- **Kotlin 2.3.x / Java 25** (Gradle 9, single module)
- **AWS SAM** — Lambda + API Gateway REST (RESPONSE_STREAM) + S3
- **SnapStart** + CRaC priming + arm64 + tiered compilation (L1)
- **TestContainers + LocalStack** for integration tests (Colima, not Docker Desktop)
- **91%+ code coverage** (Kover, 90% gate)

## Quick Start

### Prerequisites

- JDK 25 (temurin)
- AWS CLI + SAM CLI
- Colima running (for integration tests)

### Build and Test

```bash
./gradlew clean test                    # unit + property + integration tests
./gradlew koverHtmlReport koverVerify   # coverage report + 90% gate
sam validate --template-file deployment/aws/sam/template.yaml
```

### Deploy Manually

```bash
cd deployment/aws/sam
./build.sh    # shadow fat JAR
./deploy.sh   # sam build + sam deploy
```

### Seed a Test Object and Verify

```bash
# The script seeds a 12 MB object if not present, then verifies streaming
./scripts/pipeline-streaming-test.sh
```

## CI/CD Pipeline (GitHub Actions)

The pipeline builds, deploys, and tests streaming end-to-end:

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

   The script detects and reuses an existing GitHub OIDC provider if you have one from another project.

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
.
├── src/main/kotlin/          # Handler, parser, validator, S3 source, writer, priming
├── src/test/kotlin/          # Unit, property, and integration tests
├── deployment/aws/sam/       # SAM template + config + build/deploy scripts
├── deployment/aws/oidc/      # OIDC bootstrap CloudFormation template
├── scripts/
│   ├── setup-oidc.sh         # One-time OIDC role setup
│   ├── pipeline-streaming-test.sh  # CI streaming validation
│   └── post-deploy-test.sh   # Full post-deploy verification (memory + timing)
├── .github/workflows/        # GitHub Actions CI/CD
└── docs/
    ├── article.md            # The published guide
    └── log.md                # Dev gotchas and fixes
```

## Try It Yourself — Stream a Large File

After deploying, you can upload any large file to the S3 source bucket and stream it back through the endpoint. Here's a full walkthrough using a ~21 MB NASA Black Marble GeoTIFF as an example.

### 1. Get the bucket name and API key

```bash
# Bucket name (from stack outputs)
BUCKET_NAME=$(aws cloudformation describe-stacks \
  --stack-name s3-file-streaming-endpoint \
  --query "Stacks[0].Outputs[?OutputKey=='SourceBucketName'].OutputValue" \
  --output text)

# API key ID
API_KEY_ID=$(aws cloudformation describe-stacks \
  --stack-name s3-file-streaming-endpoint \
  --query "Stacks[0].Outputs[?OutputKey=='ApiKeyId'].OutputValue" \
  --output text)

# API key value
API_KEY=$(aws apigateway get-api-key --api-key "$API_KEY_ID" --include-value \
  --query 'value' --output text)

# Endpoint URL
ENDPOINT_URL=$(aws cloudformation describe-stacks \
  --stack-name s3-file-streaming-endpoint \
  --query "Stacks[0].Outputs[?OutputKey=='StreamingEndpointUrl'].OutputValue" \
  --output text)
```

### 2. Download a large file (e.g. NASA Black Marble)

Grab a GeoTIFF from NASA's [Black Marble](https://ladsweb.modaps.eosdis.nasa.gov/missions-and-measurements/products/VNP46A2/) dataset (or any large file you want to test with):

```bash
# Example: ~21 MB Africa night lights (replace with your own file if you prefer)
curl -L -o BlackMarble_2016_1200m_africa_s.tif \
  "https://eoimages.gsfc.nasa.gov/images/imagerecords/144000/144898/BlackMarble_2016_1200m_africa_s.tif"
```

### 3. Upload it to the source bucket

```bash
aws s3 cp BlackMarble_2016_1200m_africa_s.tif \
  "s3://$BUCKET_NAME/BlackMarble_2016_1200m_africa_s.tif"
```

### 4. Stream it back via the endpoint

```bash
curl -o streamed_output.tif \
  -H "x-api-key: $API_KEY" \
  -w '\nHTTP %{http_code} | First byte: %{time_starttransfer}s | Total: %{time_total}s | Size: %{size_download} bytes\n' \
  "${ENDPOINT_URL}BlackMarble_2016_1200m_africa_s.tif"
```

Expected output (times vary):

```
HTTP 200 | First byte: 3.2s | Total: 8.1s | Size: 21702219 bytes
```

The first byte arrives well before the full 21 MB is delivered — proving real streaming, not buffered delivery.

### 5. Verify byte integrity (optional)

```bash
cmp BlackMarble_2016_1200m_africa_s.tif streamed_output.tif \
  && echo "PASS: byte-identical" \
  || echo "FAIL: content mismatch"
```

## Testing with Postman

After deploy, hit the streaming endpoint:

```
GET https://<api-id>.execute-api.<region>.amazonaws.com/prod/<filename>
```

Add the `x-api-key` header with your API key value. You should see the full body streamed back. Check response size and time-to-first-byte in Postman's timing breakdown.

## Key Design Decisions

- **`RequestStreamHandler`** over `RequestHandler` — only way to get raw `OutputStream` access for the streaming protocol
- **Validate before commit** — status code is locked once metadata + 8 null bytes are written
- **headObject before streaming** — confirms size for Content-Length before committing 200
- **Fixed 1 MB buffer** — memory independent of object size
- **API key auth** — the endpoint is protected with an API Gateway API key (`x-api-key` header). Basic auth appropriate for a demo; a production service would use IAM auth or a custom authorizer
- **OIDC (not access keys)** — short-lived credentials in CI, no secrets to rotate

## License

See LICENSE file.
