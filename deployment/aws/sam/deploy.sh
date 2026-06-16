#!/usr/bin/env bash
#
# deploy.sh - Build and deploy the S3 file streaming endpoint with AWS SAM.
#
# Steps:
#   1. Build the shadow fat jar (delegates to build.sh).
#   2. sam build  - assemble the deployment artifact from template.yaml.
#   3. sam deploy - create/update the CloudFormation stack.
#
# Configuration (stack name, region, capabilities) comes from samconfig.toml
# in this directory. Extra args are forwarded to `sam deploy`, e.g.:
#   ./deployment/aws/sam/deploy.sh --guided
#   ./deployment/aws/sam/deploy.sh --region us-east-1
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

echo "==> sam build"
sam build --template-file template.yaml

echo "==> sam deploy"
# --config-file pins samconfig.toml in this directory regardless of CWD.
sam deploy --config-file samconfig.toml "$@"
