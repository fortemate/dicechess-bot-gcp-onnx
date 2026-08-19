# Dice Chess webhook bot — ONNX expectimax or one-ply (bring your own model)

[![CI](https://github.com/fortemate/dicechess-bot-gcp-onnx/actions/workflows/ci.yaml/badge.svg)](https://github.com/fortemate/dicechess-bot-gcp-onnx/actions/workflows/ci.yaml)
[![Engine](https://img.shields.io/badge/Engine-dicechess--engine-8A2BE2)](https://github.com/fortemate/dicechess-engine)
[![Bot API](https://img.shields.io/badge/Docs-Bot%20API-orange)](https://fortemate.github.io/dicechess-bot-runtime/)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-lightgrey)](./LICENSE)

A Dice Chess webhook bot in **Scala 3** that links the official engine and runs its **ONNX-backed
2-ply expectimax** (`OnnxExpectimaxSearch`) behind an exported **opening book**, on the JVM in a
container suitable for **Google Cloud Run**.

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
| `MODEL_PATH` | *(synthetic)* | Path to the mounted ONNX value model, e.g. `/models/oracle-3.onnx`. |
| `OPENING_BOOK_PATH` | bundled 5-entry sample | Path to a privately mounted TSV opening book, e.g. `/models/opening_book.tsv`. |
| `ORACLE_FEATURES` | `rich` | Feature extractor the model was trained on: `material` (7), `rich` (9), `kcp` (13). |
| `SEARCH_MODE` | `expectimax` | `expectimax` for 2-ply search or `one-ply` for direct model evaluation. |
| `TIME_POLICY` | `empirical-v1` | `empirical-v1` (production Dice Chess data) or `legacy-linear-v1`. |
| `ORACLE_CANDIDATE_LIMIT` | engine default | Expectimax candidate width. |
| `RESCORE_MODEL_PATH` | *(off)* | Expectimax only (`SEARCH_MODE=expectimax`): second ONNX model (always KCP, 13 features) that rescores the root candidates — see below. |
| `RESCORE_WEIGHT` | `0.5` | Expectimax only (`SEARCH_MODE=expectimax`): root-rescore blend weight in `(0, 1]`; `final = (1-w)·search + w·rescore`. |
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

**AGPL-3.0**, because it links the AGPL engine. The trained model and production opening book are
**data**, not derivatives of the code — so keeping them private is compatible with the AGPL code. A
fork without private assets runs the bundled synthetic model and five-entry book sample.

## Layout

| Path | Role |
| --- | --- |
| `src/main/scala/com/fortemate/dicechess/bot/Strategy.scala` | Configured ONNX search wrapped by `OpeningBookBot`, clock → `TimeManager` deadline; thread-safe concurrent ONNX session. |
| `src/main/scala/com/fortemate/dicechess/bot/Main.scala` | Wires `Strategy` into [`dicechess-bot-runtime`](https://github.com/fortemate/dicechess-bot-runtime)'s `WebhookHandler`/`CustomHandlerServer`; binds the platform's `$PORT`. |
| `src/main/resources/opening_book.tsv` | Five public example entries in the engine's TSV format; production uses `OPENING_BOOK_PATH`. |
| `src/main/resources/synthetic_test_model.onnx` | Open, signal-free fallback model — so the bot runs with no `MODEL_PATH`. |
| `Dockerfile` | Runtime-only public image (`eclipse-temurin:25-jre-noble`); trained weights are provided privately at deployment. |

## Local development

Requires JDK 25+ and sbt. The engine and bot runtime are public Maven Central artifacts, so no
package-registry credentials are required. [mise](https://mise.jdx.dev/) installs the pinned local
toolchain and Git hooks:

```bash
mise install
mise run setup
mise run check    # uses the bundled synthetic model — no external model needed
mise run run      # serves on :8080 with the synthetic model
```

## Deploy to Cloud Run

Each release publishes a multi-architecture image to GitHub Container Registry at
`ghcr.io/fortemate/dicechess-bot-gcp-onnx`. Prefer an immutable version tag in production; `latest`
tracks the most recent release.

```bash
REGION=us-central1
IMAGE=ghcr.io/fortemate/dicechess-bot-gcp-onnx:latest
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

# 3. Deploy with the model mounted read-only + selected via env. Concurrent requests can be served
#    by one container (ONNX session inference is thread-safe; --concurrency 8 matches tested capacity).
#    Scale-to-zero avoids idle instances.
gcloud run deploy dicechess-bot-gcp-onnx \
  --image "$IMAGE" --region "$REGION" \
  --service-account "$SERVICE_ACCOUNT_EMAIL" \
  --allow-unauthenticated --cpu 1 --memory 1Gi --min-instances 0 --concurrency 8 \
  --add-volume=name=models,type=cloud-storage,bucket="$BUCKET",readonly=true,mount-options="uid=10001;gid=10001" \
  --add-volume-mount=volume=models,mount-path=/models \
  --set-env-vars MODEL_PATH=/models/oracle-3.onnx,OPENING_BOOK_PATH=/models/opening_book.tsv,ORACLE_FEATURES=rich,RESCORE_MODEL_PATH=/models/kcp_nodice.onnx
```

To build and run a local container image instead:

```bash
mise run assembly
docker build -t dicechess-bot-gcp-onnx:local .
```

Then register the bot (any HTTP client; `curl` shown), using the printed service URL:

```bash
BASE=https://play-api.fortemate.com
URL=https://<the-cloud-run-url>/api/webhook

curl -X POST "$BASE/bot/register" -H "Content-Type: application/json" \
  -d '{"team":"gcp","name":"expectimax-onnx-3"}'                       # token shown once
curl -X POST "$BASE/bot/webhook" -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" -d "{\"url\":\"$URL\"}"          # secret shown once

# Store the displayed webhook secret without putting it in shell history, and grant only the
# bot's service identity access to it.
gcloud secrets create "$SECRET_NAME" --replication-policy=automatic
read -rsp "Webhook secret: " WEBHOOK_SECRET && echo
printf '%s' "$WEBHOOK_SECRET" | gcloud secrets versions add "$SECRET_NAME" --data-file=-
unset WEBHOOK_SECRET
gcloud secrets add-iam-policy-binding "$SECRET_NAME" \
  --member="serviceAccount:$SERVICE_ACCOUNT_EMAIL" \
  --role=roles/secretmanager.secretAccessor

gcloud run services update dicechess-bot-gcp-onnx --region "$REGION" \
  --update-secrets "DICECHESS_WEBHOOK_SECRET=${SECRET_NAME}:latest"
curl -X POST "$BASE/bot/ladder/join" -H "Authorization: Bearer <token>"
```

Full platform reference: <https://fortemate.github.io/dicechess-bot-runtime/>. Review the current
Cloud Run and Cloud Storage pricing before deployment; Google Cloud requires a billing account.
