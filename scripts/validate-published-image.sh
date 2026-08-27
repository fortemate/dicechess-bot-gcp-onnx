#!/usr/bin/env bash

set -euo pipefail

readonly EXPECTED_REPOSITORY="fortemate/dicechess-bot-gcp-onnx"
readonly EXPECTED_SOURCE="https://github.com/${EXPECTED_REPOSITORY}"
readonly EXPECTED_LICENSE="AGPL-3.0-only"
readonly SIGNER_WORKFLOW="${EXPECTED_REPOSITORY}/.github/workflows/deploy.yaml"
readonly SLSA_PREDICATE_TYPE="https://slsa.dev/provenance/v1"
readonly SPDX_PREDICATE_TYPE="https://spdx.dev/Document"

usage() {
  cat <<'EOF'
Usage:
  scripts/validate-published-image.sh \
    --release-tag vX.Y.Z \
    --source-revision <40-character-lowercase-git-sha> \
    --image-digest sha256:<64-lowercase-hex-characters>

Validates the immutable GHCR image published by this repository. The command is
read-only: it does not build, push, or retag an image.
EOF
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

release_tag=""
source_revision=""
image_digest=""

while (($# > 0)); do
  case "$1" in
    --release-tag)
      (($# >= 2)) || fail "--release-tag requires a value"
      release_tag=$2
      shift 2
      ;;
    --source-revision)
      (($# >= 2)) || fail "--source-revision requires a value"
      source_revision=$2
      shift 2
      ;;
    --image-digest)
      (($# >= 2)) || fail "--image-digest requires a value"
      image_digest=$2
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      fail "unknown argument: $1"
      ;;
  esac
done

[[ -n "$release_tag" ]] || fail "--release-tag is required"
[[ -n "$source_revision" ]] || fail "--source-revision is required"
[[ -n "$image_digest" ]] || fail "--image-digest is required"
[[ "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "release tag must be vX.Y.Z"
[[ "$source_revision" =~ ^[0-9a-f]{40}$ ]] || fail "source revision must be a full lowercase 40-character Git SHA"
[[ "$image_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "image digest must be an immutable sha256 digest"

require_command docker
require_command gh
require_command jq

readonly image_repository="ghcr.io/${EXPECTED_REPOSITORY}"
readonly image_ref="${image_repository}@${image_digest}"
readonly expected_version="${release_tag#v}"
readonly expected_source_ref="refs/tags/${release_tag}"

resolve_tag_commit() {
  local object_json
  local object_type
  local object_sha
  local depth=0

  if ! object_json=$(gh api "repos/${EXPECTED_REPOSITORY}/git/ref/tags/${release_tag}"); then
    fail "could not resolve live release tag ${release_tag}"
  fi

  while ((depth < 5)); do
    if ! object_type=$(jq --exit-status --raw-output '.object.type' <<<"$object_json") ||
      ! object_sha=$(jq --exit-status --raw-output '.object.sha' <<<"$object_json"); then
      fail "GitHub returned an invalid object for release tag ${release_tag}"
    fi
    [[ "$object_sha" =~ ^[0-9a-f]{40}$ ]] || fail "GitHub returned an invalid SHA for release tag ${release_tag}"

    case "$object_type" in
      commit)
        printf '%s\n' "$object_sha"
        return 0
        ;;
      tag)
        if ! object_json=$(gh api "repos/${EXPECTED_REPOSITORY}/git/tags/${object_sha}"); then
          fail "could not dereference annotated release tag ${release_tag}"
        fi
        ;;
      *)
        fail "release tag ${release_tag} points to unsupported Git object type ${object_type}"
        ;;
    esac
    depth=$((depth + 1))
  done

  fail "release tag ${release_tag} exceeds the annotated-tag dereference limit"
}

live_tag_revision=$(resolve_tag_commit)
[[ "$live_tag_revision" == "$source_revision" ]] ||
  fail "live release tag ${release_tag} resolves to ${live_tag_revision}, not ${source_revision}"

printf 'Validating immutable image %s\n' "$image_ref"

if ! index_json=$(docker buildx imagetools inspect --raw "$image_ref"); then
  fail "could not read the published OCI image index"
fi

if ! jq --exit-status \
  --arg source "$EXPECTED_SOURCE" \
  --arg revision "$source_revision" \
  --arg version "$expected_version" \
  --arg license "$EXPECTED_LICENSE" '
  .mediaType == "application/vnd.oci.image.index.v1+json" and
  (.manifests | type == "array") and
  ([.manifests[] | select(.platform.os != "unknown")] | length) == 2 and
  ([.manifests[] | select(.platform.os == "linux" and .platform.architecture == "amd64")] | length) == 1 and
  ([.manifests[] | select(.platform.os == "linux" and .platform.architecture == "arm64")] | length) == 1 and
  all(.manifests[]; .digest | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
  .annotations["org.opencontainers.image.source"] == $source and
  .annotations["org.opencontainers.image.licenses"] == $license and
  .annotations["org.opencontainers.image.revision"] == $revision and
  .annotations["org.opencontainers.image.version"] == $version
' <<<"$index_json" >/dev/null; then
  fail "published image index failed OCI, platform, or annotation validation"
fi

for architecture in amd64 arm64; do
  platform="linux/${architecture}"
  if ! manifest_digest=$(jq --exit-status --raw-output --arg architecture "$architecture" '
    [
      .manifests[] |
      select(.platform.os == "linux" and .platform.architecture == $architecture)
    ] |
    if length == 1 then .[0].digest else empty end
  ' <<<"$index_json"); then
    fail "could not resolve exactly one ${platform} manifest"
  fi

  if ! manifest_json=$(docker buildx imagetools inspect --raw "${image_repository}@${manifest_digest}"); then
    fail "could not read the published ${platform} manifest"
  fi
  if ! jq --exit-status \
    --arg source "$EXPECTED_SOURCE" \
    --arg revision "$source_revision" \
    --arg version "$expected_version" \
    --arg license "$EXPECTED_LICENSE" '
    .mediaType == "application/vnd.oci.image.manifest.v1+json" and
    .config.mediaType == "application/vnd.oci.image.config.v1+json" and
    (.config.digest | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
    (.layers | type == "array" and length > 0) and
    all(.layers[]; .digest | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
    .annotations["org.opencontainers.image.source"] == $source and
    .annotations["org.opencontainers.image.licenses"] == $license and
    .annotations["org.opencontainers.image.revision"] == $revision and
    .annotations["org.opencontainers.image.version"] == $version
  ' <<<"$manifest_json" >/dev/null; then
    fail "${platform} manifest failed OCI or annotation validation"
  fi

  if ! config_json=$(docker buildx imagetools inspect \
    "${image_repository}@${manifest_digest}" --format '{{json .Image.Config}}'); then
    fail "could not read the published ${platform} image config"
  fi
  if ! jq --exit-status \
    --arg release_tag "$release_tag" \
    --arg source "$EXPECTED_SOURCE" \
    --arg revision "$source_revision" \
    --arg version "$expected_version" \
    --arg license "$EXPECTED_LICENSE" '
    .User == "app" and
    (.Env | type == "array") and
    (.Env | index("BOT_WRAPPER_VERSION=" + $release_tag)) != null and
    (.Env | index("SOURCE_REVISION=" + $revision)) != null and
    .Labels["org.opencontainers.image.source"] == $source and
    .Labels["org.opencontainers.image.licenses"] == $license and
    .Labels["org.opencontainers.image.revision"] == $revision and
    .Labels["org.opencontainers.image.version"] == $version
  ' <<<"$config_json" >/dev/null; then
    fail "${platform} image config failed non-root or provenance validation"
  fi

  if ! attestation_digest=$(jq --exit-status --raw-output --arg manifest_digest "$manifest_digest" '
    [
      .manifests[] |
      select(
        .platform.os == "unknown" and
        .platform.architecture == "unknown" and
        .annotations["vnd.docker.reference.type"] == "attestation-manifest" and
        .annotations["vnd.docker.reference.digest"] == $manifest_digest
      )
    ] |
    if length == 1 then .[0].digest else empty end
  ' <<<"$index_json"); then
    fail "could not resolve exactly one ${platform} attestation manifest"
  fi

  if ! attestation_json=$(docker buildx imagetools inspect --raw "${image_repository}@${attestation_digest}"); then
    fail "could not read the ${platform} attestation manifest"
  fi
  if ! jq --exit-status \
    --arg manifest_digest "$manifest_digest" \
    --arg slsa "$SLSA_PREDICATE_TYPE" \
    --arg spdx "$SPDX_PREDICATE_TYPE" '
    .mediaType == "application/vnd.oci.image.manifest.v1+json" and
    .artifactType == "application/vnd.docker.attestation.manifest.v1+json" and
    .config.mediaType == "application/vnd.oci.empty.v1+json" and
    .subject.mediaType == "application/vnd.oci.image.manifest.v1+json" and
    .subject.digest == $manifest_digest and
    ([
      .layers[] |
      select(
        .mediaType == "application/vnd.in-toto+json" and
        .annotations["in-toto.io/predicate-type"] == $slsa
      )
    ] | length) == 1 and
    ([
      .layers[] |
      select(
        .mediaType == "application/vnd.in-toto+json" and
        .annotations["in-toto.io/predicate-type"] == $spdx
      )
    ] | length) == 1
  ' <<<"$attestation_json" >/dev/null; then
    fail "${platform} attestation manifest failed OCI, SLSA, or SPDX validation"
  fi
done

# Buildx exposes per-platform BuildKit statements as a map only when
# `.Provenance` is formatted from the multi-platform index. Inspecting
# `.Provenance.SLSA` on a child manifest returns null with current Buildx.
if ! provenance_json=$(docker buildx imagetools inspect "$image_ref" --format '{{json .Provenance}}'); then
  fail "could not read index-level BuildKit provenance"
fi

if ! jq --exit-status '
  (keys | sort) == ["linux/amd64", "linux/arm64"] and
  all(
    .["linux/amd64"], .["linux/arm64"];
    .SLSA.buildDefinition.buildType as $build_type |
    (
      $build_type == "https://github.com/moby/buildkit/blob/master/docs/attestations/slsa-definitions.md" or
      $build_type == "https://mobyproject.org/buildkit@v1"
    ) and
    (.SLSA.buildDefinition.resolvedDependencies | type == "array" and length > 0) and
    all(
      .SLSA.buildDefinition.resolvedDependencies[];
      (.uri | type == "string" and length > 0) and
      (.digest.sha256 | type == "string" and test("^[0-9a-f]{64}$"))
    )
  )
' <<<"$provenance_json" >/dev/null; then
  fail "index-level BuildKit provenance failed platform, build type, or dependency validation"
fi

gh attestation verify "oci://${image_ref}" \
  --repo "$EXPECTED_REPOSITORY" \
  --source-digest "$source_revision" \
  --source-ref "$expected_source_ref" \
  --signer-workflow "$SIGNER_WORKFLOW" \
  --signer-digest "$source_revision" \
  --predicate-type "$SLSA_PREDICATE_TYPE" \
  --deny-self-hosted-runners

printf 'Published image validation succeeded for %s (%s).\n' "$release_tag" "$image_digest"
