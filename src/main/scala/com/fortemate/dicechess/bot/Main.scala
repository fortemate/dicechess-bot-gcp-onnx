package com.fortemate.dicechess.bot

import com.sun.net.httpserver.HttpServer
import com.fortemate.dicechess.runtime.{CustomHandlerServer, TurnContext, WebhookHandler}

import java.util.concurrent.CountDownLatch
import java.util.function.Function as JFunction
import scala.jdk.CollectionConverters.*

/** The Cloud Run entry point. All webhook/HTTP-server plumbing — HMAC verification, the ownership handshake, the JDK
  * `HttpServer` — lives in `dicechess-bot-runtime`; this object wires our engine-backed, clock-aware [[Strategy]] into
  * it and binds the port Cloud Run gives us.
  *
  * Configuration (env vars; Cloud Run service settings in production):
  *   - `DICECHESS_WEBHOOK_SECRET` — required per-bot signing key from webhook registration; an empty value fails
  *     startup.
  *   - `BOT_PROFILE` — `legacy` (default) or the fail-closed production contract `hybrid-star2-v1`.
  *   - `MODEL_PATH` — path to the mounted ONNX value model (e.g. `/models/oracle-3.onnx`). Unset → the bundled
  *     synthetic model (boots + plays legal, signal-free moves).
  *   - `OPENING_BOOK_PATH` — path to an external TSV opening book. Unset → the bundled five-entry sample.
  *   - `ORACLE_FEATURES` — feature extractor the model was trained on: `rich` (default; oracle-3), `material`, or
  *     `kcp`.
  *   - `ORACLE_CANDIDATE_LIMIT` — expectimax candidate width (default: the engine's own).
  *   - `PRE_RANK_WITH_MODEL` — use the leaf model to pre-rank expectimax candidates (`false` by default).
  *   - `TT_ENABLED` — enable the per-Strategy transposition table (`false` by default).
  *   - `TT_CAPACITY` — positive power-of-two TT capacity (default: 262144 entries).
  *   - `RESCORE_MODEL_PATH` — optional KCP model that rescores expectimax root candidates.
  *   - `RESCORE_WEIGHT` — root-rescore blend weight in `(0, 1]` (default `0.5`).
  *   - `MODEL_SHA256`, `RESCORE_MODEL_SHA256`, `OPENING_BOOK_SHA256` — optional expected artifact digests; a mismatch
  *     fails startup. Matching `*_ID` values set human-readable immutable artifact identifiers.
  *   - `BOT_IDENTITY`, `BOT_WRAPPER_VERSION`, `SOURCE_REVISION`, `IMAGE_DIGEST` — non-secret deployment provenance
  *     included in the structured startup event.
  *   - `SEARCH_MODE` — `expectimax` (default) or `one-ply`.
  *   - `TIME_POLICY` — `empirical-v1` (default) or `legacy-linear-v1`.
  *   - `OVERHEAD_BUFFER_MS` (default `300`), `DEFAULT_THINK_MS` (default `2000`, untimed games).
  *
  * One Strategy serializes requests because the v0.5 search and TT are single-writer. Use independent replicas for
  * parallelism. The Fischer increment arrives on the wire (`ctx.clock()`, runtime >= 0.2.0) — nothing to configure.
  */
object Main:

  private val WebhookPath = "/api/webhook"

  def main(args: Array[String]): Unit =
    val _        = args
    val secret   = requireWebhookSecret(sys.env.get("DICECHESS_WEBHOOK_SECRET"))
    val strategy = Strategy.fromEnvironment // builds and warms the ONNX session at startup
    val server   = CustomHandlerServer.start(resolvePort, WebhookPath, new WebhookHandler(secret, adapt(strategy)))
    val stopped  = new CountDownLatch(1)
    val hook     = new Thread(() => stopGracefully(server, strategy, stopped), "dicechess-bot-shutdown")
    Runtime.getRuntime.addShutdownHook(hook)
    println(s"[bot] ONNX custom handler listening on :${server.getAddress.getPort}$WebhookPath")
    stopped.await()

  private[bot] def requireWebhookSecret(value: Option[String]): String =
    value.filter(_.trim.nonEmpty).getOrElse {
      sys.error("DICECHESS_WEBHOOK_SECRET must be set and non-empty")
    }

  private def stopGracefully(server: HttpServer, strategy: Strategy, stopped: CountDownLatch): Unit =
    try server.stop(5)
    finally
      try strategy.close()
      finally stopped.countDown()

  /** Cloud Run injects `PORT` (default 8080); fall back to Azure's var, then 8080. */
  private def resolvePort: Int =
    sys.env
      .get("PORT")
      .orElse(sys.env.get("FUNCTIONS_CUSTOMHANDLER_PORT"))
      .flatMap(_.toIntOption)
      .getOrElse(8080)

  /** Start the server on an explicit port (exposed for the end-to-end test; port 0 = ephemeral). */
  def start(port: Int, secret: String, strategy: Strategy): HttpServer =
    CustomHandlerServer.start(port, WebhookPath, new WebhookHandler(secret, adapt(strategy)))

  /** Monte-Carlo and expectimax both need the clock, so this reads `ctx.clock()` (null for an untimed game): the
    * mover's remaining time and the Fischer increment, both on the wire since runtime 0.2.0. A missing increment
    * coalesces to `0`.
    */
  private def adapt(strategy: Strategy): JFunction[TurnContext, java.util.List[String]] =
    (ctx: TurnContext) =>
      val clock     = Option(ctx.clock())
      val remaining = clock.map(_.remainingMillis())
      val increment = clock.flatMap(c => Option(c.incrementMillis())).fold(0L)(_.longValue)
      strategy.chooseMoves(ctx.dfen(), remaining, increment).asJava
