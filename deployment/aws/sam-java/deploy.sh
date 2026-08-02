#!/usr/bin/env bash
#
# deploy.sh - Build and deploy the Java S3 file streaming endpoint with AWS SAM.
#
# Steps:
#   1. Build the shadow fat jar (delegates to build.sh).
#   2. sam deploy - package the prebuilt fat jar (CodeUri) and create/update the stack.
#
# NOTE: we deliberately do NOT run `sam build`. The template's CodeUri already points
# at a prebuilt fat jar (build/dist/streaming-endpoint-java.jar). Recent SAM CLI versions
# register a build workflow for the `java25` runtime that expects a Gradle/Maven manifest
# in the CodeUri path; pointed at a bare .jar it fails with "Unable to find a supported
# build workflow for runtime 'java25'". `sam deploy` packages the jar directly (implicit
# `sam package`), which is exactly what the CI pipeline (workflow-deploy-aws.yml) does.
# See docs/log.md ("sam build fails on a prebuilt java25 fat jar").
#
# Configuration (stack name, region, capabilities) comes from samconfig.toml
# in this directory. Extra args are forwarded to `sam deploy`, e.g.:
#   ./deployment/aws/sam-java/deploy.sh --guided
#   ./deployment/aws/sam-java/deploy.sh --region us-east-1
#
# Note: the REST API (execute-api) endpoint is served over HTTPS/TLS only;
# plain-HTTP requests are rejected at the API Gateway layer, so no handler
# code is required to enforce TLS.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Building the Lambda fat jar"
"${SCRIPT_DIR}/build.sh"

cd "${SCRIPT_DIR}"

echo "==> sam deploy (packages the prebuilt fat jar; no sam build)"
# --config-file pins samconfig.toml in this directory regardless of CWD.
# --template-file points at the source template (not .aws-sam/build) so sam deploy
# packages the CodeUri jar directly. resolve_s3=true (samconfig) supplies the artifacts bucket.
sam deploy --config-file samconfig.toml --template-file template.yaml "$@"
