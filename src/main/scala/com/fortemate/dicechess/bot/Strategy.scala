package com.fortemate.dicechess.bot

import dicechess.engine.domain.{Color, FenParser, GameState, Move}
import dicechess.engine.search.{
  ClockState,
  ExpectimaxConfig,
  KcpFeatures,
  OnnxEvalSearch,
  OnnxExpectimaxSearch,
  OnnxFeatures,
  OpeningBookBot,
  OpeningBookParser,
  RichFeatures,
  RootRescoreModel,
  RootSearchStats,
  SearchAlgorithm,
  TimePolicies,
  TimeBudgetedSearch,
  TimeManager
}

import java.nio.file.{Files, StandardCopyOption}
import scala.util.Random

/** The move-choosing brain: either the engine's ONNX-backed 2-ply expectimax ([[OnnxExpectimaxSearch]]) or its direct
  * one-ply evaluator ([[OnnxEvalSearch]]), decorated with the exported opening book and driven by the game clock.
  *
  * Time management is not reinvented: an engine [[dicechess.engine.search.TimeManager]] maps the clock to a per-turn
  * budget, and the book decorator preserves the time-budget capability, so the deadline reaches the underlying search.
  * The selected policy is injected per deployment.
  *
  * The trained model is a **runtime input**, never in this repo: `modelPath` points at a mounted ONNX value model, and
  * `extractFeatures` must match the features it was trained on (house `oracle-3` = [[RichFeatures]], 9 columns). One
  * ONNX session is created here and reused. `OrtSession.run` in ONNX Runtime is thread-safe, and the underlying search
  * wrappers maintain no shared mutable state, so `chooseMoves` is safe for concurrent execution across multiple
  * threads. Call [[close]] to release the session.
  *
  * `rootRescore`, when set, mounts a *second* ONNX model (a [[KcpFeatures]]-trained one) that rescores only the top
  * root candidates — the affordable way to bring the king/queen capture-probability signal into play: the 9-feature
  * leaf model is effectively blind to a hanging queen (material is unchanged and mobility barely moves when a piece
  * stands en prise), while full leaf-level KCP is ~18x too slow under a chance node. Rescoring K root positions costs K
  * DFS passes per move rather than one pass per expectimax leaf.
  */
final class Strategy(
    modelPath: String,
    extractFeatures: (GameState, Color) => Array[Float],
    candidateLimit: Int,
    overheadBufferMs: Long,
    defaultThinkMs: Long,
    rootRescore: Option[RootRescoreModel] = None,
    searchMode: Strategy.SearchMode = Strategy.SearchMode.Expectimax,
    timeManager: TimeManager = TimeManager.default,
    openingBook: Map[String, String] = Strategy.sampleBook,
    statsSink: RootSearchStats => Unit = Strategy.logSearchStats
) extends AutoCloseable:

  require(
    searchMode == Strategy.SearchMode.Expectimax || rootRescore.isEmpty,
    "root rescoring is only supported in expectimax mode"
  )

  private val onnx: TimeBudgetedSearch & AutoCloseable = searchMode match
    case Strategy.SearchMode.Expectimax =>
      new OnnxExpectimaxSearch(
        modelPath,
        ExpectimaxConfig(candidateLimit),
        extractFeatures,
        rootRescore,
        statsSink = statsSink
      )
    case Strategy.SearchMode.OnePly => new OnnxEvalSearch(modelPath, extractFeatures)
  private val bot: SearchAlgorithm = OpeningBookBot.decorate(onnx, openingBook)

  /** DFEN in, UCI micro-move path out. `Nil` = nothing to play (forced pass or unusable DFEN).
    *
    * Thread-safe: OrtSession.run is re-entrant and thread-safe in Java ONNX Runtime. The underlying search tree
    * generation, tensor allocations, and deduplication logic operate strictly on local state per turn.
    */
  def chooseMoves(dfen: String, remainingMillis: Option[Long], incrementMillis: Long): List[String] =
    FenParser.parse(dfen) match
      case Left(reason) =>
        System.err.println(s"[bot] unusable dfen: $reason")
        Nil
      case Right(state) =>
        val budgetMs = remainingMillis match
          case Some(remaining) if remaining > 0 =>
            timeManager.budgetMs(ClockState(remaining, incrementMillis, state.fullMoveNumber), overheadBufferMs)
          case _ => defaultThinkMs
        val deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L
        val scored        = bot match
          case tb: TimeBudgetedSearch => tb.findBestMove(state, deadlineNanos, new Random())
          case other                  => other.findBestMove(state)
        scored.map(_.moves.map(Strategy.toUci)).getOrElse(Nil)

  def close(): Unit = onnx.close()

object Strategy:

  enum SearchMode(val id: String) derives CanEqual:
    case Expectimax extends SearchMode("expectimax")
    case OnePly     extends SearchMode("one-ply")

  object SearchMode:
    def get(id: String): Option[SearchMode] = values.find(_.id.equalsIgnoreCase(id))

    val available: List[SearchMode] = values.toList

  /** UCI for a search-layer `Move` — the same recipe play-api's `EngineOps` uses. */
  def toUci(move: Move): String =
    move.fromSquare.toNotation + move.toSquare.toNotation +
      move.promotionPieceType.map(_.asNotation).getOrElse("")

  /** One structured JSON line per move, straight to stdout. Structured-log platforms can parse
    * `candidatesCompleted`/`fellBackToPreRank` without client-side parsing — this is the measurement that replaces the
    * inferred "the bot completes ~1-2 of its K candidates under the clock" figure with an observed one.
    *
    * Never fires on an opening-book hit: [[OpeningBookBot]] returns the booked move before the search runs, so there is
    * no root search to report for that turn.
    *
    * Synchronous `println` is deliberate here, not an oversight. The engine calls the sink exactly once per
    * `findBestMove`, and — on the normal path — only *after* the candidate loop has already ended, so it cannot shorten
    * the search or change the fallback decision; it can only add a little latency to the reply. At one ~200-byte line
    * per move, with one turn at a time and moves measured in seconds, the throughput is far too low to fill a 64 KB
    * stdout pipe, and `OVERHEAD_BUFFER_MS` already reserves slack for the round trip. A bounded async sink with a drop
    * policy would be the right answer at high volume, but here it would add a thread and a shutdown/flush path for no
    * measurable gain — and dropping samples is precisely the wrong failure mode for telemetry whose entire purpose is
    * measuring how often the deadline truncates the search.
    */
  private def logSearchStats(stats: RootSearchStats): Unit =
    println(
      s"""{"event":"root_search_stats","legalTurns":${stats.legalTurns},""" +
        s""""candidatesSelected":${stats.candidatesSelected},"candidatesCompleted":${stats.candidatesCompleted},""" +
        s""""candidatesAbandoned":${stats.candidatesAbandoned},"deadlineTruncated":${stats.deadlineTruncated},""" +
        s""""fellBackToPreRank":${stats.fellBackToPreRank}}"""
    )

  /** The five-entry public format sample. Production deployments override it with `OPENING_BOOK_PATH`; malformed
    * private data fails startup instead of silently weakening the bot.
    */
  private[bot] lazy val sampleBook: Map[String, String] =
    val tsv = scala.util.Using(scala.io.Source.fromResource("opening_book.tsv"))(_.mkString).get
    OpeningBookParser.parse(tsv) match
      case Right(entries) => entries
      case Left(error)    =>
        System.err.println(s"[bot] bundled opening-book sample malformed ($error) — playing bookless")
        Map.empty[String, String]

  private[bot] def loadOpeningBook(path: Option[String]): Map[String, String] = path match
    case None        => sampleBook
    case Some(value) =>
      val tsv = scala.util
        .Using(scala.io.Source.fromFile(value))(_.mkString)
        .fold(
          cause => sys.error(s"failed to read opening book at '$value': ${cause.getMessage}"),
          identity
        )
      OpeningBookParser
        .parse(tsv)
        .fold(
          error => sys.error(s"opening book at '$value' is malformed: $error"),
          identity
        )

  /** The feature extractor the model was trained on — must match the ONNX file. */
  private def extractorFor(name: String): (GameState, Color) => Array[Float] = name.toLowerCase match
    case "rich"          => RichFeatures.extract
    case "kcp"           => KcpFeatures.extract
    case "material" | "" => OnnxFeatures.extract
    case other           => sys.error(s"unknown ORACLE_FEATURES '$other' (expected material|rich|kcp)")

  /** Production wiring. `MODEL_PATH` points at the mounted ONNX model (`ORACLE_FEATURES=rich` for the `oracle-3`
    * weights); unset falls back to the bundled synthetic model so the bot still boots and plays legal — if signal-free
    * — moves out of the box.
    *
    * `RESCORE_MODEL_PATH`, when set, mounts the KCP root rescorer (always [[KcpFeatures]], 13 columns — the feature set
    * root rescoring exists for), blended at `RESCORE_WEIGHT` (default 0.5, must be in (0, 1]). It only makes sense
    * alongside a real `MODEL_PATH`, so the synthetic fallback ignores it.
    */
  def fromEnvironment: Strategy =
    val overheadMs     = envLong("OVERHEAD_BUFFER_MS", 300L)
    val defaultThink   = envLong("DEFAULT_THINK_MS", 2000L)
    val candidateLimit = envInt("ORACLE_CANDIDATE_LIMIT", ExpectimaxConfig().candidateLimit)
    val searchMode     = resolveSearchMode(sys.env.getOrElse("SEARCH_MODE", SearchMode.Expectimax.id))
    val timePolicy     = resolveTimePolicy(sys.env.getOrElse("TIME_POLICY", TimePolicies.default.id))
    val timeManager    = TimeManager(timePolicy)
    val bookPath       = sys.env.get("OPENING_BOOK_PATH").filter(_.nonEmpty)
    val openingBook    = loadOpeningBook(bookPath)
    bookPath.foreach(path => println(s"[bot] opening book: $path (${openingBook.size} entries)"))
    sys.env.get("MODEL_PATH").filter(_.nonEmpty) match
      case Some(path) =>
        val features = sys.env.getOrElse("ORACLE_FEATURES", "rich")
        val rescore  = searchMode match
          case SearchMode.OnePly =>
            if sys.env.get("RESCORE_MODEL_PATH").exists(_.nonEmpty) then
              sys.error("RESCORE_MODEL_PATH is only supported when SEARCH_MODE=expectimax")
            None
          case SearchMode.Expectimax =>
            sys.env.get("RESCORE_MODEL_PATH").filter(_.nonEmpty).map { rescorePath =>
              // Fail fast on an unparsable weight: an out-of-range value already crashes at boot (RootRescore's range
              // check), and a typo silently degrading to the 0.5 default would be the worse failure mode of the two.
              val weight = sys.env.get("RESCORE_WEIGHT").fold(0.5) { raw =>
                raw.toDoubleOption
                  .getOrElse(sys.error(s"invalid RESCORE_WEIGHT '$raw' (expected a number in (0, 1])"))
              }
              RootRescoreModel(rescorePath, KcpFeatures.extract, weight)
            }
        val rescoreNote = rescore.fold("")(r => s", rescore=${r.modelPath} (weight=${r.weight})")
        println(
          s"[bot] ONNX model: $path (mode=${searchMode.id}, features=$features, " +
            s"timePolicy=${timePolicy.id}, candidateLimit=$candidateLimit$rescoreNote)"
        )
        new Strategy(
          path,
          extractorFor(features),
          candidateLimit,
          overheadMs,
          defaultThink,
          rootRescore = rescore,
          searchMode = searchMode,
          timeManager = timeManager,
          openingBook = openingBook
        )
      case None =>
        System.err.println(
          s"[bot] MODEL_PATH not set — using the bundled synthetic model " +
            s"(mode=${searchMode.id}, timePolicy=${timePolicy.id}; legal moves, no chess signal)"
        )
        new Strategy(
          syntheticModelPath(),
          OnnxFeatures.extract,
          candidateLimit,
          overheadMs,
          defaultThink,
          searchMode = searchMode,
          timeManager = timeManager,
          openingBook = openingBook
        )

  private[bot] def resolveSearchMode(id: String): SearchMode =
    SearchMode.get(id).getOrElse {
      val available = SearchMode.available.map(_.id).mkString("|")
      sys.error(s"unknown SEARCH_MODE '$id' (expected $available)")
    }

  private[bot] def resolveTimePolicy(id: String): dicechess.engine.search.TimePolicy =
    TimePolicies.get(id).getOrElse {
      val available = TimePolicies.available.map(_.id).mkString("|")
      sys.error(s"unknown TIME_POLICY '$id' (expected $available)")
    }

  /** Extract the bundled synthetic model to a temp file — ONNX Runtime loads from a filesystem path. */
  private def syntheticModelPath(): String =
    val tmp = Files.createTempFile("synthetic_test_model", ".onnx")
    tmp.toFile.deleteOnExit()
    val in = getClass.getResourceAsStream("/synthetic_test_model.onnx")
    try Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING)
    finally in.close()
    tmp.toString

  private def envLong(name: String, default: Long): Long =
    sys.env.get(name).flatMap(_.toLongOption).getOrElse(default)

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.toIntOption).filter(_ > 0).getOrElse(default)
