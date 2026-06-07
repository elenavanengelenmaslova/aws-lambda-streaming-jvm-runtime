#!/usr/bin/env bash
#
# post-deploy-test.sh — Prove S3 response streaming against the DEPLOYED endpoint.
#
# This script verifies the success criteria from Requirement 9 of the
# s3-file-streaming-endpoint spec against a live deployment:
#
#   9.1  A payload >= 15 MB (over the legacy 6 MB buffered limit) is delivered
#        in full and is byte-identical to the source object in S3.
#   9.2  A warmup request is issued first and excluded from all reported timings.
#   9.3  Time-to-first-byte arrives no later than 50% of total completion time
#        (proves progressive, non-buffered delivery).
#   9.4  The Lambda max memory for the >= 15 MB payload is within 10% of the max
#        memory for a 6 MB payload (proves memory does not grow with body size).
#   9.5  An unreachable or non-success endpoint => non-zero exit, never "success".
#   9.6  A payload size/content mismatch => non-zero exit, never "success".
#
# Configuration is taken from environment variables (no secrets are embedded).
# When an env var is not set, the value is resolved from the deployed
# CloudFormation stack outputs (see deployment/aws/sam/template.yaml).
#
#   STACK_NAME       CloudFormation stack name        (default: s3-file-streaming-endpoint)
#   AWS_REGION       AWS region                       (optional; uses CLI default)
#   ENDPOINT_URL     Base HTTPS URL, trailing slash   (default: stack output StreamingEndpointUrl)
#   BUCKET_NAME      Source S3 bucket                 (default: stack output SourceBucketName)
#   FUNCTION_NAME    Lambda function name             (default: stack output FunctionName)
#   LARGE_FILE       >= 15 MB test object key         (default: test-object-15mb.bin)
#   SMALL_FILE       ~6 MB baseline object key        (default: test-object-6mb.bin)
#   LOG_WAIT_SECONDS Seconds to wait for CloudWatch    (default: 30)
#   MIN_LARGE_BYTES  Minimum size for the large object (default: 15728640 = 15 MiB)
#   SMALL_FILE_BYTES Size to seed the baseline object  (default: 6291456 = 6 MiB)
#   SEED             Seed the Test_Object(s) first     (default: true)
#   TEARDOWN         Remove the stack after checks     (default: false)
#
# Seeding (Req 11.7): before any request, the large ~15 MB Test_Object and the
# ~6 MB baseline object are generated from /dev/urandom and uploaded to the
# deployed bucket when missing (or too small). Set SEED=false to skip seeding
# when the objects are already in place.
#
# Teardown (Req 11.7): set TEARDOWN=true to remove the deployed stack as a final
# step. The bucket is emptied first (objects, versions, and delete markers), the
# stack is deleted via `sam delete` (falling back to `aws cloudformation
# delete-stack` + wait), and success is confirmed only once the stack no longer
# exists — i.e. no stack resources remain. Teardown is opt-in so a normal
# verification run never destroys the deployment.
#
# Usage:
#   ./scripts/post-deploy-test.sh                 # seed (if needed) + verify
#   SEED=false ./scripts/post-deploy-test.sh      # verify only, no seeding
#   TEARDOWN=true ./scripts/post-deploy-test.sh   # verify, then remove the stack
#
# Exit codes:
#   0  all checks passed
#   1  a verification check failed, or the endpoint was unreachable/non-success
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration (env vars or placeholders — never secrets)
# ---------------------------------------------------------------------------
STACK_NAME="${STACK_NAME:-s3-file-streaming-endpoint}"
LARGE_FILE="${LARGE_FILE:-test-object-15mb.bin}"
SMALL_FILE="${SMALL_FILE:-test-object-6mb.bin}"
LOG_WAIT_SECONDS="${LOG_WAIT_SECONDS:-30}"
MIN_LARGE_BYTES="${MIN_LARGE_BYTES:-15728640}" # 15 * 1024 * 1024
SMALL_FILE_BYTES="${SMALL_FILE_BYTES:-6291456}" # 6 * 1024 * 1024
SEED="${SEED:-true}"
TEARDOWN="${TEARDOWN:-false}"

# Region flag for AWS CLI calls, only when AWS_REGION is set.
AWS_REGION="${AWS_REGION:-}"
region_args=()
if [[ -n "$AWS_REGION" ]]; then
  region_args=(--region "$AWS_REGION")
fi

# Scratch directory for downloaded bodies and reference copies; always cleaned up.
WORK_DIR="$(mktemp -d)"

# ---------------------------------------------------------------------------
# Output helpers and cleanup
# ---------------------------------------------------------------------------
info()  { printf '[post-deploy] %s\n' "$*"; }
ok()    { printf '[post-deploy] PASS: %s\n' "$*"; }

# fail prints an error to stderr and exits non-zero. Per Req 9.5/9.6 the script
# MUST terminate non-zero and never report success on any failure.
fail() {
  printf '[post-deploy] ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Preflight: required tooling
# ---------------------------------------------------------------------------
require_tools() {
  local missing=()
  for tool in curl aws cmp awk stat head; do
    command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
  done
  if (( ${#missing[@]} > 0 )); then
    fail "missing required tool(s): ${missing[*]}"
  fi
}

# file_size <path> — portable byte size (GNU and BSD stat).
file_size() {
  stat -c%s "$1" 2>/dev/null || stat -f%z "$1"
}

# ---------------------------------------------------------------------------
# Resolve stack outputs (only when the corresponding env var is unset)
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
  FUNCTION_NAME="${FUNCTION_NAME:-$(stack_output FunctionName)}"

  [[ -n "${ENDPOINT_URL:-}" && "$ENDPOINT_URL" != "None" ]] \
    || fail "could not determine ENDPOINT_URL (set ENDPOINT_URL or deploy stack '$STACK_NAME')"
  [[ -n "${BUCKET_NAME:-}" && "$BUCKET_NAME" != "None" ]] \
    || fail "could not determine BUCKET_NAME (set BUCKET_NAME or deploy stack '$STACK_NAME')"
  [[ -n "${FUNCTION_NAME:-}" && "$FUNCTION_NAME" != "None" ]] \
    || fail "could not determine FUNCTION_NAME (set FUNCTION_NAME or deploy stack '$STACK_NAME')"

  # Endpoint must be HTTPS; the streaming endpoint serves over TLS only.
  [[ "$ENDPOINT_URL" == https://* ]] || fail "ENDPOINT_URL must be HTTPS: $ENDPOINT_URL"

  LOG_GROUP="/aws/lambda/${FUNCTION_NAME}"
  info "resolved configuration for stack '$STACK_NAME' (endpoint, bucket, function)"
}

# build_url <file-name> — join the base endpoint and the requested object key.
build_url() {
  printf '%s/%s' "${ENDPOINT_URL%/}" "$1"
}

# ---------------------------------------------------------------------------
# Test_Object seeding (Req 11.7)
#
# Generate the test objects from /dev/urandom and upload them to the deployed
# bucket so the objects exist before any request. Seeding is idempotent: an
# object already present at >= the required size is left untouched. The bucket
# name is never echoed (it is treated as a non-secret resource id, matching the
# rest of this script).
# ---------------------------------------------------------------------------

# s3_object_size <key> — ContentLength of an object, or empty if it is absent.
s3_object_size() {
  aws s3api head-object \
    --bucket "$BUCKET_NAME" --key "$1" "${region_args[@]}" \
    --query 'ContentLength' --output text 2>/dev/null || true
}

# seed_object <key> <bytes> — ensure <key> exists in the bucket at >= <bytes>.
seed_object() {
  local key="$1" bytes="$2" existing tmp

  existing="$(s3_object_size "$key")"
  if [[ -n "$existing" && "$existing" != "None" ]] && (( existing >= bytes )); then
    info "object '$key' already present (${existing} bytes); skipping seed"
    return 0
  fi

  tmp="$WORK_DIR/seed_$(basename "$key")"
  head -c "$bytes" /dev/urandom > "$tmp" \
    || fail "could not generate ${bytes}-byte random content for '$key'"

  aws s3api put-object \
    --bucket "$BUCKET_NAME" --key "$key" --body "$tmp" "${region_args[@]}" \
    >/dev/null 2>&1 \
    || fail "could not seed object '$key' into the source bucket"

  info "seeded object '$key' (${bytes} bytes) into the source bucket"
  rm -f "$tmp"
}

# seed_objects — seed the large (>= 15 MB) Test_Object and the 6 MB baseline.
seed_objects() {
  info "seeding test objects into the source bucket"
  seed_object "$LARGE_FILE" "$MIN_LARGE_BYTES"
  seed_object "$SMALL_FILE" "$SMALL_FILE_BYTES"
  ok "test objects present in the source bucket"
}

# ---------------------------------------------------------------------------
# Stack teardown (Req 11.7) — opt-in via TEARDOWN=true
#
# Removes the deployed stack and confirms no stack resources remain. The bucket
# is emptied first (current objects, all versions, and delete markers) so the
# versioned bucket can be deleted with the stack. `sam delete` is preferred; we
# fall back to `aws cloudformation delete-stack` + a blocking wait.
# ---------------------------------------------------------------------------

# empty_bucket — best-effort purge of objects, versions, and delete markers.
empty_bucket() {
  info "emptying the source bucket (objects, versions, and delete markers)"

  aws s3 rm "s3://$BUCKET_NAME" --recursive "${region_args[@]}" >/dev/null 2>&1 || true

  local key vid
  while read -r key vid; do
    [[ -z "$key" || "$key" == "None" ]] && continue
    aws s3api delete-object \
      --bucket "$BUCKET_NAME" --key "$key" --version-id "$vid" "${region_args[@]}" \
      >/dev/null 2>&1 || true
  done < <(aws s3api list-object-versions \
              --bucket "$BUCKET_NAME" "${region_args[@]}" \
              --query '[Versions[].[Key,VersionId], DeleteMarkers[].[Key,VersionId]][]' \
              --output text 2>/dev/null || true)
}

# verify_stack_gone — succeed only when the stack no longer exists.
verify_stack_gone() {
  local status
  status="$(aws cloudformation describe-stacks \
      --stack-name "$STACK_NAME" "${region_args[@]}" \
      --query 'Stacks[0].StackStatus' --output text 2>/dev/null || true)"

  if [[ -z "$status" || "$status" == "None" || "$status" == "DELETE_COMPLETE" ]]; then
    ok "teardown complete: stack '$STACK_NAME' no longer exists; no stack resources remain"
    return 0
  fi

  fail "teardown incomplete: stack '$STACK_NAME' still present with status $status"
}

# teardown_stack — empty the bucket, delete the stack, confirm it is gone.
teardown_stack() {
  info "TEARDOWN requested: removing stack '$STACK_NAME'"
  empty_bucket

  if command -v sam >/dev/null 2>&1; then
    sam delete --stack-name "$STACK_NAME" "${region_args[@]}" --no-prompts \
      || fail "sam delete failed for stack '$STACK_NAME'"
  else
    info "sam not found; deleting via aws cloudformation"
    aws cloudformation delete-stack --stack-name "$STACK_NAME" "${region_args[@]}" \
      || fail "could not initiate deletion of stack '$STACK_NAME'"
    aws cloudformation wait stack-delete-complete \
      --stack-name "$STACK_NAME" "${region_args[@]}" \
      || fail "stack '$STACK_NAME' did not reach DELETE_COMPLETE"
  fi

  verify_stack_gone
}

# ---------------------------------------------------------------------------
# HTTP request with timing capture
#
# Performs a GET, saving the body to <body_file>. Echoes a single line:
#   "<http_code> <time_starttransfer> <time_total> <size_download>"
# A connection-level failure (unreachable) is a hard error (Req 9.5).
# ---------------------------------------------------------------------------
http_get_timed() {
  local url="$1" body_file="$2" label="$3"
  local metrics rc=0

  metrics="$(curl -sS \
      -o "$body_file" \
      -w '%{http_code} %{time_starttransfer} %{time_total} %{size_download}' \
      "$url")" || rc=$?

  if (( rc != 0 )); then
    fail "endpoint unreachable for $label (curl exit code $rc)"
  fi

  # Verify a 2xx success response; anything else is a non-success endpoint (Req 9.5).
  local http_code
  http_code="$(awk '{print $1}' <<<"$metrics")"
  if [[ ! "$http_code" =~ ^2[0-9][0-9]$ ]]; then
    fail "non-success HTTP status $http_code for $label"
  fi

  printf '%s\n' "$metrics"
}

# ---------------------------------------------------------------------------
# CloudWatch: max memory used (MB) for the most recent invocation
#
# Reads the latest Lambda REPORT line emitted at/after <since_ms> and extracts
# "Max Memory Used: N MB". Requests are issued sequentially with a settle wait,
# so the most recent REPORT corresponds to the request just made.
# ---------------------------------------------------------------------------
latest_max_memory_mb() {
  local since_ms="$1"
  local attempts=0 max_attempts mem=""

  # Poll roughly once per second up to LOG_WAIT_SECONDS for logs to propagate.
  max_attempts="$LOG_WAIT_SECONDS"
  while (( attempts < max_attempts )); do
    mem="$(aws logs filter-log-events \
        --log-group-name "$LOG_GROUP" "${region_args[@]}" \
        --start-time "$since_ms" \
        --filter-pattern 'REPORT' \
        --query 'events[*].message' --output text 2>/dev/null \
      | grep -oE 'Max Memory Used: [0-9]+ MB' \
      | tail -n1 \
      | grep -oE '[0-9]+' || true)"
    if [[ -n "$mem" ]]; then
      printf '%s\n' "$mem"
      return 0
    fi
    attempts=$(( attempts + 1 ))
    sleep 1
  done

  fail "could not read Max Memory Used from CloudWatch within ${LOG_WAIT_SECONDS}s"
}

# now_ms — current epoch time in milliseconds (CloudWatch filter start-time).
now_ms() {
  printf '%s000\n' "$(date +%s)"
}

# ---------------------------------------------------------------------------
# Measured request: download a file, capture timings, and read Lambda memory.
# Sets globals: M_TTFB, M_TOTAL, M_SIZE, M_MEMORY for the caller to consume.
# ---------------------------------------------------------------------------
measure_request() {
  local file="$1" body_file="$2" label="$3"
  local url start_ms metrics

  url="$(build_url "$file")"
  start_ms="$(now_ms)"
  info "requesting $label"
  metrics="$(http_get_timed "$url" "$body_file" "$label")"

  M_TTFB="$(awk '{print $2}' <<<"$metrics")"
  M_TOTAL="$(awk '{print $3}' <<<"$metrics")"
  M_SIZE="$(awk '{print $4}' <<<"$metrics")"
  info "$label: ttfb=${M_TTFB}s total=${M_TOTAL}s bytes=${M_SIZE}"

  M_MEMORY="$(latest_max_memory_mb "$start_ms")"
  info "$label: lambda max memory used = ${M_MEMORY} MB"
}

# ---------------------------------------------------------------------------
# Verifications (Req 9.1, 9.3, 9.4, 9.6)
# ---------------------------------------------------------------------------

# verify_full_and_identical — byte-for-byte compare the streamed body against
# the source object in S3, and confirm the size threshold (Req 9.1, 9.6).
verify_full_and_identical() {
  local file="$1" body_file="$2"
  local ref_file body_bytes ref_bytes

  ref_file="$WORK_DIR/ref.bin"
  aws s3api get-object \
    --bucket "$BUCKET_NAME" --key "$file" "${region_args[@]}" \
    "$ref_file" >/dev/null 2>&1 \
    || fail "could not fetch source object for comparison from the bucket"

  body_bytes="$(file_size "$body_file")"
  ref_bytes="$(file_size "$ref_file")"

  # Size threshold: the large object must exceed the legacy 6 MB limit (Req 9.1).
  if (( ref_bytes < MIN_LARGE_BYTES )); then
    fail "source object is ${ref_bytes} bytes, below required ${MIN_LARGE_BYTES} (>= 15 MB)"
  fi

  # Delivered in full: received byte count equals the stored size (Req 9.1, 9.6).
  if (( body_bytes != ref_bytes )); then
    fail "payload size mismatch: received ${body_bytes} bytes, source ${ref_bytes} bytes"
  fi

  # Byte-identical content (Req 9.1, 9.6).
  cmp -s "$ref_file" "$body_file" \
    || fail "payload content mismatch (streamed body differs from source object)"

  ok "delivered ${body_bytes} bytes in full, byte-identical to the source object"
}

# verify_first_byte_timing — first byte must arrive within 50% of total (Req 9.3).
verify_first_byte_timing() {
  local ttfb="$1" total="$2"
  if awk -v t="$ttfb" -v tot="$total" 'BEGIN { exit !(tot > 0 && t <= 0.5 * tot) }'; then
    ok "time-to-first-byte ${ttfb}s is within 50% of total ${total}s (progressive delivery)"
  else
    fail "time-to-first-byte ${ttfb}s exceeds 50% of total ${total}s (delivery appears buffered)"
  fi
}

# verify_memory_bounded — large-payload memory must be within 10% of the 6 MB
# baseline, proving memory does not grow with response size (Req 9.4).
verify_memory_bounded() {
  local large_mem="$1" small_mem="$2"
  if awk -v big="$large_mem" -v small="$small_mem" 'BEGIN { exit !(small > 0 && big <= small * 1.10) }'; then
    ok "large-payload memory ${large_mem} MB is within 10% of 6 MB-payload memory ${small_mem} MB"
  else
    fail "large-payload memory ${large_mem} MB exceeds 110% of 6 MB-payload memory ${small_mem} MB (memory grows with body)"
  fi
}

# ---------------------------------------------------------------------------
# Main verification flow
#
# Test_Object seeding (task 12.2 / Req 11.7) runs immediately after
# resolve_config so the objects exist before any request. Stack teardown
# (Req 11.7) is the final, opt-in step (TEARDOWN=true), kept out of the default
# path so a normal verification run never destroys the deployment.
# ---------------------------------------------------------------------------
run_verification() {
  require_tools
  resolve_config

  # --- Seed the Test_Object(s) before any request (Req 11.7); opt-out via SEED=false.
  if [[ "$SEED" == "true" ]]; then
    seed_objects
  else
    info "SEED=false: skipping Test_Object seeding"
  fi

  local large_body small_body

  # --- Warmup: issued first, result discarded, excluded from all timings (Req 9.2).
  info "warmup request (discarded from all timings)"
  http_get_timed "$(build_url "$LARGE_FILE")" "$WORK_DIR/warmup.bin" "warmup" >/dev/null
  ok "warmup completed and discarded"

  # --- Measured large (>= 15 MB) payload.
  large_body="$WORK_DIR/large.bin"
  measure_request "$LARGE_FILE" "$large_body" "large payload (>= 15 MB)"
  local large_ttfb="$M_TTFB" large_total="$M_TOTAL" large_mem="$M_MEMORY"

  # --- Measured 6 MB baseline payload (for the memory comparison).
  small_body="$WORK_DIR/small.bin"
  measure_request "$SMALL_FILE" "$small_body" "baseline payload (6 MB)"
  local small_mem="$M_MEMORY"

  # --- Checks.
  verify_full_and_identical "$LARGE_FILE" "$large_body"   # Req 9.1, 9.6
  verify_first_byte_timing "$large_ttfb" "$large_total"   # Req 9.3
  verify_memory_bounded "$large_mem" "$small_mem"         # Req 9.4

  info "all post-deploy streaming checks passed"

  # --- Teardown is opt-in (Req 11.7); a default run never destroys the stack.
  if [[ "$TEARDOWN" == "true" ]]; then
    teardown_stack
  else
    info "TEARDOWN not set: leaving stack '$STACK_NAME' in place (run with TEARDOWN=true to remove it)"
  fi
}

run_verification
