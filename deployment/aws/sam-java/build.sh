#!/usr/bin/env bash
#
# build.sh - Build the Lambda shadow fat jar for the Java S3 file streaming endpoint.
#
# Produces the fat jar under build/dist/ that the SAM template's
# CodeUri (../../../build/dist/streaming-endpoint-java.jar) packages as the function artifact.
#
# Usage:
#   ./deployment/aws/sam-java/build.sh
#
set -euo pipefail

# Resolve the repository root relative to this script (deployment/aws/sam-java/),
# so the script works regardless of the current working directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${REPO_ROOT}"

echo "==> Building shadow fat jar (clean build) from ${REPO_ROOT}"
# ':streaming-s3-example-java:build' runs the shadow plugin's shadowJar task plus tests;
# the resulting fat jar lands in build/dist/.
./gradlew clean :streaming-s3-example-java:build

echo "==> Fat jar produced in build/dist/:"
ls -1 build/dist/*.jar
