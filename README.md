# Dice Chess webhook bot — ONNX expectimax or one-ply (bring your own model)

[![CI](https://github.com/fortemate/dicechess-bot-gcp-onnx/actions/workflows/ci.yaml/badge.svg)](https://github.com/fortemate/dicechess-bot-gcp-onnx/actions/workflows/ci.yaml)
[![Engine](https://img.shields.io/badge/Engine-dicechess--engine-8A2BE2)](https://github.com/fortemate/dicechess-engine)
[![Bot API](https://img.shields.io/badge/Docs-Bot%20API-orange)](https://fortemate.github.io/dicechess-bot-runtime/)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-lightgrey)](./LICENSE)

A Dice Chess webhook bot in **Scala 3** that links the official engine and runs its **ONNX-backed
2-ply expectimax** (`OnnxExpectimaxSearch`) behind an exported **opening book**, on the JVM in a
container suitable for **Google Cloud Run**.

The named `hybrid-star2-v1` profile combines model pre-ranking, Star1/Star2 chance-node pruning, a
bounded transposition table, and a KCP root rescorer. It is deliberately fail-closed: engine 0.5.0 is
rejected because root rescoring prevents useful Star cutoffs in that release
([engine#87](https://github.com/fortemate/dicechess-engine/issues/87)). The wrapper now pins engine
0.6.0, which resolves the issue ([engine#89](https://github.com/fortemate/dicechess-engine/pull/89)),
making the profile runnable. One search instance is single-writer; safe throughput scaling comes from
independent container replicas, each with its own ONNX sessions and table.

The same image can instead run the model as a direct one-ply evaluator (`OnnxEvalSearch`) and can be
hosted by any container platform with a public HTTPS endpoint, including Northflank. Search mode and
time allocation policy are selected independently per deployment, so two bot identities can compare
algorithms without maintaining two codebases.

**Bring your own model and opening book.** The trained value model and production book are
**runtime inputs, not in this repo**: point `MODEL_PATH` at an ONNX model and `OPENING_BOOK_PATH` at
a TSV book mounted read-only from private storage. Without them, the bundled **synthetic** model and
five-entry book sample keep the bot runnable out of the box. The open part is the search and
deployment wiring; trained weights and the production book are supplied privately — never in git
or a public image.

## Why a JVM container (not the edge)

The ONNX Runtime is a JVM/JNI native library and `OnnxExpectimaxSearch` is available only in the
engine's JVM artifact — **not** in its Scala.js/Wasm builds. This bot therefore needs a JVM
container such as Cloud Run. The ONNX Runtime Java package bundles the native library inside the
jar, so it loads with no extra setup.

## Time management

Delegated to the engine's [`TimeManager`](https://github.com/fortemate/dicechess-engine) via the
wire clock (`ctx.clock()`); the book decorator preserves the deadline. Expectimax is fixed 2-ply,
so the budget **widens the candidate set** (and prevents flagging) rather than deepening.

| Env var | Default | Meaning |
| --- | --- | --- |
| `DICECHESS_WEBHOOK_SECRET` | *(required)* | Per-bot webhook signing secret. Empty or absent values fail startup. |
| `BOT_PROFILE` | `legacy` | `legacy` preserves independent env configuration; `hybrid-star2-v1` atomically requires the documented production search, artifacts, identity, and immutable provenance. |
| `MODEL_PATH` | *(synthetic)* | Path to the mounted ONNX value model, e.g. `/models/oracle-3.onnx`. |
| `OPENING_BOOK_PATH` | bundled 5-entry sample | Path to a privately mounted TSV opening book, e.g. `/models/opening_book.tsv`. |
| `ORACLE_FEATURES` | `rich` | Feature extractor the model was trained on: `material` (7), `rich` (9), `kcp` (13). |
| `SEARCH_MODE` | `expectimax` | `expectimax` for 2-ply search or `one-ply` for direct model evaluation. |
| `TIME_POLICY` | `empirical-v1` | `empirical-v1` (production Dice Chess data) or `legacy-linear-v1`. |
| `ORACLE_CANDIDATE_LIMIT` | engine default | Expectimax candidate width. |
| `PRE_RANK_WITH_MODEL` | `false` | Expectimax only: reuse the leaf model to pre-rank root candidates. |
| `TT_ENABLED` | `false` | Expectimax only: enable one transposition table owned by this Strategy. |
| `TT_CAPACITY` | `262144` | Positive power-of-two table capacity, capped at 4,194,304; size for the container memory limit. |
| `RESCORE_MODEL_PATH` | *(off)* | Expectimax only (`SEARCH_MODE=expectimax`): second ONNX model (always KCP, 13 features) that rescores the root candidates — see below. |
| `RESCORE_WEIGHT` | `0.5` | Expectimax only (`SEARCH_MODE=expectimax`): root-rescore blend weight in `(0, 1]`; `final = (1-w)·search + w·rescore`. |
| `MODEL_SHA256` | *(unchecked)* | Expected SHA-256 for `MODEL_PATH`; mismatch fails startup. |
| `RESCORE_MODEL_SHA256` | *(unchecked)* | Expected SHA-256 for `RESCORE_MODEL_PATH`; mismatch fails startup. |
| `OPENING_BOOK_SHA256` | *(unchecked)* | Expected SHA-256 for `OPENING_BOOK_PATH`; mismatch fails startup. |
| `MODEL_ID`, `RESCORE_MODEL_ID`, `OPENING_BOOK_ID` | filename | Stable artifact identifiers included in startup telemetry. |
| `BOT_IDENTITY`, `BOT_WRAPPER_VERSION`, `SOURCE_REVISION`, `IMAGE_DIGEST` | deployment values | Non-secret identity and immutable build provenance included in startup telemetry. |
| `OVERHEAD_BUFFER_MS` | `300` | Slack for the play-api↔bot round-trip + one uninterruptible inference. |
| `DEFAULT_THINK_MS` | `2000` | Per-turn deadline for an untimed game. |

## KCP root rescoring — why

The 9-feature leaf model is effectively blind to a hanging piece: its inputs (material counts,
mobility, a binary king-attacked flag) barely move when the queen stands en prise, so the search
happily walks into pawn attacks whenever the punishment lies past its 2-ply horizon. The 13-feature KCP model *does*
see it (`queen_capture_danger` is a top-gain feature), but its 216-outcome dice DFS is ~18x too slow
for the thousands of leaves under a chance node. Rescoring blends the KCP model's opinion into just
the top `ORACLE_CANDIDATE_LIMIT` root candidates — a handful of DFS passes per move. Mount the KCP
model next to the main one and set `RESCORE_MODEL_PATH` to
enable it; a candidate that loses the king outright on some roll is never rescued by the rescorer
(engine guarantee).

## Licensing

**AGPL-3.0-only**, because it links the AGPL engine. The trained model and production opening book are
**data**, not derivatives of the code — so keeping them private is compatible with the AGPL code. A
fork without private assets runs the bundled synthetic model and five-entry book sample.

## Layout

| Path | Role |
| --- | --- |
| `src/main/scala/com/fortemate/dicechess/bot/Strategy.scala` | Configured ONNX search wrapped by `OpeningBookBot`, clock → `TimeManager` deadline; serialized single-writer search with optional TT. |
| `src/main/scala/com/fortemate/dicechess/bot/ArtifactProvenance.scala` | Streams SHA-256 verification for mounted models/books and fails closed on a mismatch. |
| `src/main/scala/com/fortemate/dicechess/bot/Main.scala` | Wires `Strategy` into [`dicechess-bot-runtime`](https://github.com/fortemate/dicechess-bot-runtime)'s `WebhookHandler`/`CustomHandlerServer`; binds the platform's `$PORT`. |
| `src/main/resources/opening_book.tsv` | Five public example entries in the engine's TSV format; production uses `OPENING_BOOK_PATH`. |
| `src/main/resources/synthetic_test_model.onnx` | Open, signal-free fallback model — so the bot runs with no `MODEL_PATH`. |
| `Dockerfile` | Multi-stage, non-root public image on `eclipse-temurin:25-jre-noble`; trained weights are provided privately at deployment. |

## Local development

Requires JDK 25+ and sbt. The engine and bot runtime are public Maven Central artifacts, so no
package-registry credentials are required. [mise](https://mise.jdx.dev/) installs the pinned local
toolchain and Git hooks:

```bash
mise install
mise run setup
mise run check    # uses the bundled synthetic model — no external model needed
DICECHESS_WEBHOOK_SECRET=dev-secret mise run run  # serves on :8080 with the synthetic model
```

## Deploy to Cloud Run

Each release publishes a multi-architecture image to GitHub Container Registry at
`ghcr.io/fortemate/dicechess-bot-gcp-onnx`. Pin the published image by digest in production;
`latest` is useful only for discovery and tracks the most recent release.

### Release and verification recovery

If the CD workflow fails at the post-publish validation step after an immutable multi-arch image index has already been pushed to GHCR, **do not rebuild, push, retag, or recreate the release tag**. A published image artifact exists in GHCR independently of the final status of a workflow run.

To safely verify an already-published immutable image digest without altering repository state:
1. Run the **Ops: Verify Published Image** workflow (`.github/workflows/verify-image.yaml`) via `workflow_dispatch`.
2. Provide the target image digest (e.g. `ghcr.io/fortemate/dicechess-bot-gcp-onnx@sha256:ebf479d1be91cd2401c32547ce3d83dc459dbf30b18ae0a3c36d7685ed92765d`), expected git commit SHA (`a168ca26e076c9286b9ca37bf83f538201c5d578`), and release ref (`refs/tags/v0.3.0`).
3. This verify-only path checks the index structure, platform manifests, attestation manifests, Buildx SLSA provenance contract, and GitHub cryptographic attestations without modifying the container registry or git tags.

```bash
REGION=us-central1
IMAGE=ghcr.io/fortemate/dicechess-bot-gcp-onnx@sha256:<published-multi-arch-index-digest>
BOT_IDENTITY=dexus/atlas-1
MODEL_SHA256=<64-hex-rich-model-digest>
RESCORE_MODEL_SHA256=<64-hex-kcp-model-digest>
OPENING_BOOK_SHA256=<64-hex-opening-book-digest>
PROJECT_ID=$(gcloud config get-value project)
BUCKET=your-private-bucket
SERVICE_ACCOUNT_NAME=dicechess-bot-gcp-onnx
SERVICE_ACCOUNT_EMAIL="${SERVICE_ACCOUNT_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
SECRET_NAME=dicechess-webhook-secret

# 1. Create a dedicated service identity and grant it read-only access to the model bucket.
gcloud iam service-accounts create "$SERVICE_ACCOUNT_NAME" \
  --display-name="Dice Chess ONNX bot"
gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" \
  --member="serviceAccount:$SERVICE_ACCOUNT_EMAIL" \
  --role=roles/storage.objectViewer

# 2. Upload the private runtime assets. The optional second model is the KCP root rescorer.
gcloud storage cp oracle-3.onnx "gs://$BUCKET/oracle-3.onnx"
gcloud storage cp kcp_nodice.onnx "gs://$BUCKET/kcp_nodice.onnx"
gcloud storage cp opening_book.tsv "gs://$BUCKET/opening_book.tsv"

# 3. Create a non-empty bootstrap secret before the first deployment. The verification handshake is
#    unsigned, but the process itself always fails closed without a signing key. Replace this secret
#    with the server-issued value before joining the ladder.
openssl rand -hex 32 | gcloud secrets create "$SECRET_NAME" \
  --replication-policy=automatic --data-file=-
gcloud secrets add-iam-policy-binding "$SECRET_NAME" \
  --member="serviceAccount:$SERVICE_ACCOUNT_EMAIL" \
  --role=roles/secretmanager.secretAccessor

# 4. Deploy with the model mounted read-only + selected via env. One Strategy serializes search, so
#    keep per-instance concurrency at 1 and scale with independent replicas. Scale-to-zero avoids idle instances.
#    Verify all three local files with sha256sum before using their expected values below. The wrapper
#    pins engine 0.6.0, which includes the root-rescore-aware Star pruning fix (engine#89).
gcloud run deploy dicechess-bot-gcp-onnx \
  --image "$IMAGE" --region "$REGION" \
  --service-account "$SERVICE_ACCOUNT_EMAIL" \
  --allow-unauthenticated --cpu 1 --memory 1Gi --min-instances 0 --concurrency 1 \
  --add-volume=name=models,type=cloud-storage,bucket="$BUCKET",readonly=true,mount-options="uid=10001;gid=10001" \
  --add-volume-mount=volume=models,mount-path=/models \
  --set-secrets "DICECHESS_WEBHOOK_SECRET=${SECRET_NAME}:latest" \
  --set-env-vars BOT_PROFILE=hybrid-star2-v1,BOT_IDENTITY="$BOT_IDENTITY",IMAGE_DIGEST="${IMAGE#*@}",MODEL_PATH=/models/oracle-3.onnx,MODEL_ID=oracle-3,MODEL_SHA256="$MODEL_SHA256",OPENING_BOOK_PATH=/models/opening_book.tsv,OPENING_BOOK_ID=opening-book,OPENING_BOOK_SHA256="$OPENING_BOOK_SHA256",SEARCH_MODE=expectimax,ORACLE_FEATURES=rich,ORACLE_CANDIDATE_LIMIT=8,PRE_RANK_WITH_MODEL=true,TT_ENABLED=true,TT_CAPACITY=262144,RESCORE_MODEL_PATH=/models/kcp_nodice.onnx,RESCORE_MODEL_ID=kcp-nodice,RESCORE_MODEL_SHA256="$RESCORE_MODEL_SHA256",RESCORE_WEIGHT=0.5,TIME_POLICY=empirical-v1,OVERHEAD_BUFFER_MS=1000,DEFAULT_THINK_MS=2000
```

To build a local container image instead (the multi-stage build runs sbt itself):

```bash
docker build -t dicechess-bot-gcp-onnx:local .
```

Then register the bot (any HTTP client; `curl` shown), using the printed service URL:

```bash
set -euo pipefail
set +x # never echo one-time credentials, even if tracing was enabled earlier
BASE=https://api.fortemate.com
URL=https://<the-cloud-run-url>/api/webhook
BOT_TEAM=${BOT_IDENTITY%%/*}
BOT_NAME=${BOT_IDENTITY#*/}

REGISTER_RESPONSE=$(curl --fail-with-body --silent --show-error \
  --request POST "$BASE/bot/register" --header "Content-Type: application/json" \
  --data "$(jq -cn --arg team "$BOT_TEAM" --arg name "$BOT_NAME" '{team: $team, name: $name}')")
BOT_TOKEN=$(printf '%s' "$REGISTER_RESPONSE" | jq -er '.token | select(type == "string" and length > 0)')
unset REGISTER_RESPONSE

# Supplying the bearer header through a file descriptor keeps the one-time token out of process arguments.
WEBHOOK_RESPONSE=$(curl --fail-with-body --silent --show-error \
  --request POST "$BASE/bot/webhook" \
  --header @<(printf 'Authorization: Bearer %s\n' "$BOT_TOKEN") \
  --header "Content-Type: application/json" \
  --data "$(jq -cn --arg url "$URL" '{url: $url}')")
WEBHOOK_SECRET=$(printf '%s' "$WEBHOOK_RESPONSE" | jq -er '.secret | select(type == "string" and length > 0)')
unset WEBHOOK_RESPONSE

# Store the one-time webhook secret without printing it or putting it in process arguments. Adding
# a new version replaces the bootstrap value without changing the secret binding.
printf '%s' "$WEBHOOK_SECRET" | gcloud secrets versions add "$SECRET_NAME" --data-file=-
unset WEBHOOK_SECRET

gcloud run services update dicechess-bot-gcp-onnx --region "$REGION" \
  --update-secrets "DICECHESS_WEBHOOK_SECRET=${SECRET_NAME}:latest"
curl --fail-with-body --silent --show-error --request POST "$BASE/bot/ladder/join" \
  --header @<(printf 'Authorization: Bearer %s\n' "$BOT_TOKEN")
unset BOT_TOKEN
```

Full platform reference: <https://fortemate.github.io/dicechess-bot-runtime/>. Review the current
Cloud Run and Cloud Storage pricing before deployment; Google Cloud requires a billing account.
