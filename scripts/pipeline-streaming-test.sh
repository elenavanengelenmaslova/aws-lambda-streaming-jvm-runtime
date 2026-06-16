#!/usr/bin/env bash
#
# pipeline-streaming-test.sh — CI/CD pipeline test for S3 response streaming.
#
# Seeds a 12 MB test object into the deployed bucket (if not already present),
# then validates streaming behavior against the live endpoint:
#
#   1. Large payload delivery   — 12 MB body received in full, byte count matches
#   2. Progressive delivery     — TTFB < 50% of total time (not buffered)
#
# Configuration via environment variables (resolved from CloudFormation outputs
# when not explicitly set):
#
#   STACK_NAME       CloudFormation stack name        (default: s3-file-streaming-endpoint)
#   AWS_REGION       AWS region                       (optional; uses CLI default)
#   ENDPOINT_URL     Base HTTPS URL, trailing slash   (default: stack output)
#   BUCKET_NAME      Source S3 bucket                 (default: stack output)
#   TEST_OBJECT_KEY  Object key to stream             (default: streaming-test-12mb.bin)
#   TEST_OBJECT_SIZE Size in bytes                    (default: 12582912 = 12 MiB)
#
# Exit codes:
#   0  all checks passed
#   1  a check failed or the endpoint was unreachable
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
STACK_NAME="${STACK_NAME:-s3-file-streaming-endpoint}"
TEST_OBJECT_KEY="${TEST_OBJECT_KEY:-streaming-test-12mb.bin}"
TEST_OBJECT_SIZE="${TEST_OBJECT_SIZE:-12582912}"  # 12 * 1024 * 1024

AWS_REGION="${AWS_REGION:-}"
region_args=()
if [[ -n "$AWS_REGION" ]]; then
  region_args=(--region "$AWS_REGION")
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
info()  { printf '[streaming] %s\n' "$*"; }
pass()  { printf '[streaming] ✓ %s\n' "$*"; }
fail()  { printf '[streaming] ERROR: %s\n' "$*" >&2; exit 1; }

require_tools() {
  local missing=()
  for tool in curl aws head cmp stat python3; do
    command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
  done
  if (( ${#missing[@]} > 0 )); then
    fail "missing required tool(s): ${missing[*]}"
  fi
}

file_size() {
  stat -c%s "$1" 2>/dev/null || stat -f%z "$1"
}

# ---------------------------------------------------------------------------
# Resolve config from CloudFormation stack outputs
# ---------------------------------------------------------------------------
stack_output() {
  aws cloudformation describe-stacks \
    --stack-name "$STACK_NAME" "${region_args[@]}" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" \
    --output text 2>/dev/null
}

resolve_config() {
  ENDPOINT_URL="${ENDPOINT_URL:-$(stack_output StreamingEndpointUrl)}"
  BUCKET_NAME="${BUCKET_NAME:-$(stack_output SourceBucketName)}"

  [[ -n "${ENDPOINT_URL:-}" && "$ENDPOINT_URL" != "None" ]] \
    || fail "could not determine ENDPOINT_URL (set it or deploy stack '$STACK_NAME')"
  [[ -n "${BUCKET_NAME:-}" && "$BUCKET_NAME" != "None" ]] \
    || fail "could not determine BUCKET_NAME (set it or deploy stack '$STACK_NAME')"
  [[ "$ENDPOINT_URL" == https://* ]] \
    || fail "ENDPOINT_URL must be HTTPS: $ENDPOINT_URL"

  # Resolve API key for authenticated requests
  if [[ -z "${API_KEY:-}" ]]; then
    local api_key_id
    api_key_id=$(stack_output ApiKeyId)
    if [[ -n "$api_key_id" && "$api_key_id" != "None" ]]; then
      API_KEY=$(aws apigateway get-api-key \
        --api-key "$api_key_id" --include-value "${region_args[@]}" \
        --query 'value' --output text 2>/dev/null || true)
    fi
  fi
  [[ -n "${API_KEY:-}" ]] || fail "could not resolve API_KEY (set API_KEY env var or deploy stack with API key)"

  # Mask the API key in GitHub Actions logs so it is never printed even with debug logging.
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::add-mask::${API_KEY}"
  fi

  info "stack=$STACK_NAME endpoint resolved, bucket resolved, API key resolved"
}

build_url() {
  printf '%s/%s' "${ENDPOINT_URL%/}" "$1"
}

# ---------------------------------------------------------------------------
# Seed: check if the 12 MB object exists, upload if missing or undersized
# ---------------------------------------------------------------------------
seed_if_missing() {
  info "Checking for test object '$TEST_OBJECT_KEY' in bucket..."

  local existing_size
  existing_size=$(aws s3api head-object \
    --bucket "$BUCKET_NAME" --key "$TEST_OBJECT_KEY" "${region_args[@]}" \
    --query 'ContentLength' --output text 2>/dev/null || echo "0")

  if [[ "$existing_size" == "None" ]]; then
    existing_size=0
  fi

  if (( existing_size >= TEST_OBJECT_SIZE )); then
    pass "test object already present (${existing_size} bytes >= ${TEST_OBJECT_SIZE})"
    return 0
  fi

  info "Seeding ${TEST_OBJECT_SIZE}-byte test object..."
  local tmp_file="$WORK_DIR/seed.bin"
  head -c "$TEST_OBJECT_SIZE" /dev/urandom > "$tmp_file" \
    || fail "could not generate ${TEST_OBJECT_SIZE}-byte random content"

  aws s3api put-object \
    --bucket "$BUCKET_NAME" --key "$TEST_OBJECT_KEY" --body "$tmp_file" \
    "${region_args[@]}" >/dev/null 2>&1 \
    || fail "could not upload test object to bucket"

  pass "seeded '${TEST_OBJECT_KEY}' (${TEST_OBJECT_SIZE} bytes)"
}

# ---------------------------------------------------------------------------
# Test 1: Large payload delivery (12 MB > 6 MB limit)
# Validates: streaming bypasses the old 6 MB buffered limit
# ---------------------------------------------------------------------------
test_streaming_large_payload() {
  info "Testing large payload (12 MB) streaming delivery..."

  local url body_file metrics http_code received_bytes
  url="$(build_url "$TEST_OBJECT_KEY")"
  body_file="$WORK_DIR/large.bin"

  metrics=$(curl --silent --show-error --max-time 120 --http1.1 \
    --header "x-api-key: $API_KEY" \
    --output "$body_file" \
    --write-out '%{http_code} %{size_download}' \
    "$url") || fail "endpoint unreachable"

  http_code=$(echo "$metrics" | awk '{print $1}')
  received_bytes=$(echo "$metrics" | awk '{print $2}')

  if [[ "$http_code" != "200" ]]; then
    fail "expected HTTP 200, got $http_code"
  fi

  info "  received $received_bytes bytes (expected $TEST_OBJECT_SIZE)"

  if (( received_bytes != TEST_OBJECT_SIZE )); then
    fail "byte count mismatch — expected $TEST_OBJECT_SIZE, got $received_bytes"
  fi

  # Byte-identical check against S3 source
  local ref_file="$WORK_DIR/ref.bin"
  aws s3api get-object \
    --bucket "$BUCKET_NAME" --key "$TEST_OBJECT_KEY" "${region_args[@]}" \
    "$ref_file" >/dev/null 2>&1 \
    || fail "could not download source object for comparison"

  cmp -s "$ref_file" "$body_file" \
    || fail "payload content mismatch (streamed body differs from S3 source)"

  pass "12 MB payload delivered in full, byte-identical to source"
}

# ---------------------------------------------------------------------------
# Test 2: Progressive delivery (TTFB < 50% of total time)
# Validates: response is streamed progressively, not buffered
# ---------------------------------------------------------------------------
test_streaming_progressive_delivery() {
  info "Testing progressive delivery (TTFB vs total time)..."

  local url timing_output
  url="$(build_url "$TEST_OBJECT_KEY")"

  # Warmup request (discard — avoids cold-start skewing TTFB)
  curl --silent --max-time 120 --http1.1 --header "x-api-key: $API_KEY" --output /dev/null "$url" || true
  info "  warmup complete (discarded)"

  timing_output=$(curl --silent --show-error --max-time 120 --http1.1 --no-buffer \
    --header "x-api-key: $API_KEY" \
    --output /dev/null \
    --write-out 'ttfb=%{time_starttransfer}\ntotal=%{time_total}\n' \
    "$url") || fail "endpoint unreachable during timing test"

  local ttfb_seconds total_seconds
  ttfb_seconds=$(echo "$timing_output" | grep "^ttfb=" | cut -d= -f2)
  total_seconds=$(echo "$timing_output" | grep "^total=" | cut -d= -f2)

  local ttfb_ms total_ms
  ttfb_ms=$(python3 -c "print(int(float('$ttfb_seconds') * 1000))")
  total_ms=$(python3 -c "print(int(float('$total_seconds') * 1000))")

  info "  TTFB: ${ttfb_ms}ms"
  info "  Total: ${total_ms}ms"

  local ttfb_ratio
  ttfb_ratio=$(python3 -c "print(f'{float($ttfb_ms) / max(float($total_ms), 1) * 100:.1f}')")
  info "  TTFB/Total ratio: ${ttfb_ratio}%"

  if python3 -c "exit(0 if float('$ttfb_ms') < float('$total_ms') * 0.5 else 1)"; then
    pass "STREAMING CONFIRMED: TTFB (${ttfb_ms}ms) < 50% of total (${total_ms}ms)"
  else
    fail "TTFB (${ttfb_ms}ms) >= 50% of total (${total_ms}ms) — response appears BUFFERED, not streamed"
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  info "============================================="
  info "S3 Streaming Response — Pipeline Validation"
  info "============================================="

  require_tools
  resolve_config
  seed_if_missing

  test_streaming_large_payload
  test_streaming_progressive_delivery

  info "============================================="
  info "All streaming pipeline tests passed ✓"
  info "============================================="
}

main
