#!/usr/bin/env bash

set -euo pipefail

readonly repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
readonly validator="${repository_root}/scripts/validate-published-image.sh"
readonly fixtures="${repository_root}/tests/fixtures/published-image"
readonly source_revision="0123456789abcdef0123456789abcdef01234567"
readonly image_digest="sha256:9999999999999999999999999999999999999999999999999999999999999999"
readonly expected_image="oci://ghcr.io/fortemate/dicechess-bot-gcp-onnx@${image_digest}"
readonly test_tmp=$(mktemp -d "${TMPDIR:-/tmp}/published-image-validator.XXXXXX")
readonly output_log="${test_tmp}/output.log"
readonly gh_args_log="${test_tmp}/gh-args.log"

trap 'rm -rf "$test_tmp"' EXIT

export PATH="${repository_root}/tests/stubs:${PATH}"
export VALIDATOR_FIXTURE_DIR="$fixtures"
export GH_ARGS_LOG="$gh_args_log"

tests_run=0

reset_overrides() {
  unset VALIDATOR_INDEX_FIXTURE
  unset VALIDATOR_AMD64_CONFIG_FIXTURE
  unset VALIDATOR_AMD64_ATTESTATION_FIXTURE
  unset VALIDATOR_PROVENANCE_FIXTURE
  unset GH_STUB_EXIT
  unset GH_STUB_TAG_MODE
  unset GH_STUB_TAG_REVISION
  : >"$gh_args_log"
  : >"$output_log"
}

run_validator() {
  "$validator" \
    --release-tag v0.3.0 \
    --source-revision "$source_revision" \
    --image-digest "$image_digest"
}

pass() {
  tests_run=$((tests_run + 1))
  printf 'ok %d - %s\n' "$tests_run" "$1"
}

expect_success() {
  local description=$1
  if ! run_validator >"$output_log" 2>&1; then
    cat "$output_log" >&2
    printf 'not ok - %s\n' "$description" >&2
    exit 1
  fi
  pass "$description"
}

expect_failure() {
  local description=$1
  local expected_message=$2
  if run_validator >"$output_log" 2>&1; then
    cat "$output_log" >&2
    printf 'not ok - %s unexpectedly succeeded\n' "$description" >&2
    exit 1
  fi
  if ! grep -Fq -- "$expected_message" "$output_log"; then
    cat "$output_log" >&2
    printf 'not ok - %s did not report expected failure: %s\n' "$description" "$expected_message" >&2
    exit 1
  fi
  if [[ -s "$gh_args_log" ]]; then
    printf 'not ok - %s reached signature verification after a structural failure\n' "$description" >&2
    exit 1
  fi
  pass "$description"
}

reset_overrides
export VALIDATOR_PROVENANCE_FIXTURE=provenance-current.json
expect_success "accepts the current BuildKit SLSA build type"

for expected_arg in \
  "attestation" \
  "verify" \
  "$expected_image" \
  "--repo" \
  "fortemate/dicechess-bot-gcp-onnx" \
  "--source-digest" \
  "$source_revision" \
  "--source-ref" \
  "refs/tags/v0.3.0" \
  "--signer-workflow" \
  "fortemate/dicechess-bot-gcp-onnx/.github/workflows/deploy.yaml" \
  "--signer-digest" \
  "--predicate-type" \
  "https://slsa.dev/provenance/v1" \
  "--deny-self-hosted-runners"; do
  grep -Fxq -- "$expected_arg" "$gh_args_log" || {
    printf 'not ok - exact gh attestation policy argument missing: %s\n' "$expected_arg" >&2
    exit 1
  }
done
pass "pins the GitHub attestation source, ref, signer workflow, and signer digest"

reset_overrides
export GH_STUB_TAG_MODE=annotated
expect_success "dereferences an annotated live release tag"

reset_overrides
export VALIDATOR_PROVENANCE_FIXTURE=provenance-legacy.json
expect_success "accepts the documented legacy BuildKit SLSA build type"

reset_overrides
export VALIDATOR_PROVENANCE_FIXTURE=provenance-unknown.json
expect_failure \
  "rejects an unknown BuildKit SLSA build type" \
  "index-level BuildKit provenance failed platform, build type, or dependency validation"

reset_overrides
export VALIDATOR_PROVENANCE_FIXTURE=provenance-missing-dependencies.json
expect_failure \
  "rejects BuildKit provenance without resolved dependencies" \
  "index-level BuildKit provenance failed platform, build type, or dependency validation"

reset_overrides
export VALIDATOR_INDEX_FIXTURE=index-missing-arm64.json
expect_failure \
  "rejects an index without both target platforms" \
  "published image index failed OCI, platform, or annotation validation"

reset_overrides
export VALIDATOR_AMD64_CONFIG_FIXTURE=config-root.json
expect_failure \
  "rejects a root published image config" \
  "linux/amd64 image config failed non-root or provenance validation"

reset_overrides
export VALIDATOR_AMD64_ATTESTATION_FIXTURE=attestation-missing-spdx.json
expect_failure \
  "rejects an attestation manifest without SPDX" \
  "linux/amd64 attestation manifest failed OCI, SLSA, or SPDX validation"

reset_overrides
export GH_STUB_TAG_REVISION=1111111111111111111111111111111111111111
expect_failure \
  "rejects a release tag that moved to another source revision" \
  "live release tag v0.3.0 resolves to 1111111111111111111111111111111111111111"

reset_overrides
if "$validator" \
  --release-tag 0.3.0 \
  --source-revision "$source_revision" \
  --image-digest "$image_digest" >"$output_log" 2>&1; then
  printf 'not ok - accepts a release tag without the required v prefix\n' >&2
  exit 1
fi
grep -Fq -- "release tag must be vX.Y.Z" "$output_log" || {
  cat "$output_log" >&2
  exit 1
}
pass "rejects malformed release-tag input before external access"

reset_overrides
if "$validator" \
  --release-tag v0.3.0 \
  --source-revision "$source_revision" \
  --image-digest "${image_digest}:mutable" >"$output_log" 2>&1; then
  printf 'not ok - accepts a malformed image digest\n' >&2
  exit 1
fi
grep -Fq -- "image digest must be an immutable sha256 digest" "$output_log" || {
  cat "$output_log" >&2
  exit 1
}
pass "rejects malformed digest input before external access"

printf '1..%d\n' "$tests_run"
