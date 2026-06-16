#!/usr/bin/env bash
#
# build.sh - Build the Lambda shadow fat jar for the S3 file streaming endpoint.
#
# Produces the fat jar under build/libs/ that the SAM template's
# CodeUri (../../../build/libs/) packages as the function artifact.
#
# Usage:
#   ./deployment/aws/sam/build.sh
#
set -euo pipefail

# Resolve the repository root relative to this script (deployment/aws/sam/),
# so the script works regardless of the current working directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${REPO_ROOT}"

echo "==> Building shadow fat jar (clean build) from ${REPO_ROOT}"
# 'build' runs the shadow plugin's shadowJar task plus the test/verification
# tasks; the resulting fat jar lands in build/libs/.
./gradlew clean build

echo "==> Fat jar(s) produced in build/libs/:"
ls -1 build/libs/*.jar
