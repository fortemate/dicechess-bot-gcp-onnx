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
  TimeManager,
  TranspositionTable
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
  * ONNX session is created here and reused. The v0.5 expectimax search and its transposition table are single-writer;
  * calls to [[chooseMoves]] are therefore serialized per Strategy instance. Independent replicas keep independent
  * sessions and tables. Call [[close]] to release the session.
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
    statsSink: RootSearchStats => Unit = Strategy.logSearchStats,
    preRankWithModel: Boolean = false,
    tt: Option[TranspositionTable] = None,
    random: Random = new Random()
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
        preRankWithModel = preRankWithModel,
        statsSink = statsSink,
        tt = tt
      )
    case Strategy.SearchMode.OnePly => new OnnxEvalSearch(modelPath, extractFeatures)
  private val bot: SearchAlgorithm = OpeningBookBot.decorate(onnx, openingBook)

  /** DFEN in, UCI micro-move path out. `Nil` = nothing to play (forced pass or unusable DFEN).
    *
    * Serialized because v0.5 expectimax search and its optional transposition table are single-writer. Container
    * replicas provide safe parallelism without sharing evaluator state. The deadline starts before lock acquisition, so
    * an accidentally concurrent caller cannot wait in the queue and then spend a fresh full clock budget.
    */
  def chooseMoves(dfen: String, remainingMillis: Option[Long], incrementMillis: Long): List[String] =
    val receivedNanos = System.nanoTime()
    this.synchronized {
      FenParser.parse(dfen) match
        case Left(reason) =>
          System.err.println(s"[bot] unusable dfen: $reason")
          Nil
        case Right(state) =>
          val budgetMs = remainingMillis match
            case Some(remaining) if remaining > 0 =>
              timeManager.budgetMs(ClockState(remaining, incrementMillis, state.fullMoveNumber), overheadBufferMs)
            case _ => defaultThinkMs
          val boundedBudgetMs = budgetMs.max(1L).min(Strategy.MaxBudgetMs)
          val deadlineNanos   = receivedNanos + boundedBudgetMs * 1_000_000L
          val scored          = bot match
            case tb: TimeBudgetedSearch => tb.findBestMove(state, deadlineNanos, random)
            case other                  => other.findBestMove(state)
          scored.map(_.moves.map(Strategy.toUci)).getOrElse(Nil)
    }

  def close(): Unit = this.synchronized(onnx.close())

object Strategy:

  /** Operational ceiling for one move; also keeps millisecond-to-nanosecond deadline arithmetic safely bounded. */
  private val MaxBudgetMs = 3_600_000L

  private val MaxCandidateLimit = 256

  private val MaxTtCapacity = 1 << 22

  private val MinimumHybridEngineVersion = (0, 5, 1)

  private[bot] lazy val engineDependencyVersion: String =
    val source = scala.io.Source.fromResource("dicechess-engine-version.txt")(using scala.io.Codec.UTF8)
    try source.mkString.trim
    finally source.close()

  enum SearchMode(val id: String) derives CanEqual:
    case Expectimax extends SearchMode("expectimax")
    case OnePly     extends SearchMode("one-ply")

  object SearchMode:
    def get(id: String): Option[SearchMode] = values.find(_.id.equalsIgnoreCase(id))

    val available: List[SearchMode] = values.toList

  enum SearchProfile(val id: String) derives CanEqual:
    /** Backwards-compatible, independently configurable behavior for local development and existing deployments. */
    case Legacy extends SearchProfile("legacy")

    /** Fail-closed production profile for the rich-leaf + KCP-root Star2 generation. */
    case HybridStar2V1 extends SearchProfile("hybrid-star2-v1")

  object SearchProfile:
    def get(id: String): Option[SearchProfile] = values.find(_.id.equalsIgnoreCase(id))

    val available: List[SearchProfile] = values.toList

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
  private[bot] def logSearchStats(stats: RootSearchStats): Unit =
    println(
      s"""{"event":"root_search_stats","legalTurns":${stats.legalTurns},""" +
        s""""candidatesSelected":${stats.candidatesSelected},"candidatesCompleted":${stats.candidatesCompleted},""" +
        s""""candidatesAbandoned":${stats.candidatesAbandoned},"deadlineTruncated":${stats.deadlineTruncated},""" +
        s""""fellBackToPreRank":${stats.fellBackToPreRank},"cutoffs":${stats.cutoffs},""" +
        s""""rollsSaved":${stats.rollsSaved},"probeCutoffs":${stats.probeCutoffs},"ttProbes":${stats.ttProbes},""" +
        s""""ttHits":${stats.ttHits},"ttCutoffs":${stats.ttCutoffs}}"""
    )

  /** The five-entry public format sample. Production deployments override it with `OPENING_BOOK_PATH`; malformed
    * private data fails startup instead of silently weakening the bot.
    */
  private[bot] lazy val sampleBook: Map[String, String] =
    val tsv = scala.util
      .Using(scala.io.Source.fromResource("opening_book.tsv")(using scala.io.Codec.UTF8))(_.mkString)
      .get
    OpeningBookParser.parse(tsv) match
      case Right(entries) => entries
      case Left(error)    =>
        System.err.println(s"[bot] bundled opening-book sample malformed ($error) — playing bookless")
        Map.empty[String, String]

  private[bot] def loadOpeningBook(path: Option[String]): Map[String, String] = path match
    case None        => sampleBook
    case Some(value) =>
      val tsv = scala.util
        .Using(scala.io.Source.fromFile(value)(using scala.io.Codec.UTF8))(_.mkString)
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
    val profile = resolveSearchProfile(sys.env.getOrElse("BOT_PROFILE", SearchProfile.Legacy.id))
    validateProfile(profile, sys.env, engineDependencyVersion)
    val overheadMs     = envLong("OVERHEAD_BUFFER_MS", 300L)
    val defaultThink   = envLong("DEFAULT_THINK_MS", 2000L)
    val candidateLimit = envInt("ORACLE_CANDIDATE_LIMIT", ExpectimaxConfig().candidateLimit)
    val searchMode     = resolveSearchMode(sys.env.getOrElse("SEARCH_MODE", SearchMode.Expectimax.id))
    val timePolicy     = resolveTimePolicy(sys.env.getOrElse("TIME_POLICY", TimePolicies.default.id))
    val preRankModel   = parseBoolean("PRE_RANK_WITH_MODEL", sys.env.get("PRE_RANK_WITH_MODEL"), default = false)
    val ttEnabled      = parseBoolean("TT_ENABLED", sys.env.get("TT_ENABLED"), default = false)
    val ttCapacity     = parseTtCapacity(sys.env.get("TT_CAPACITY"))
    val timeManager    = TimeManager(timePolicy)
    val bookPath       = sys.env.get("OPENING_BOOK_PATH").filter(_.nonEmpty)
    if searchMode == SearchMode.OnePly && (preRankModel || ttEnabled) then
      sys.error("PRE_RANK_WITH_MODEL and TT_ENABLED are only supported when SEARCH_MODE=expectimax")
    val tt           = Option.when(ttEnabled)(new TranspositionTable(ttCapacity))
    val bookArtifact = bookPath match
      case Some(path) =>
        Some(
          ArtifactProvenance.inspect(
            path,
            sys.env.get("OPENING_BOOK_SHA256"),
            sys.env.get("OPENING_BOOK_ID")
          )
        )
      case None => Some(ArtifactProvenance.inspectResource("opening_book.tsv", "bundled-sample"))
    if bookPath.isEmpty && sys.env.contains("OPENING_BOOK_SHA256") then
      sys.error("OPENING_BOOK_SHA256 requires OPENING_BOOK_PATH")
    val openingBook = loadOpeningBook(bookPath)
    bookPath.foreach(path => println(s"[bot] opening book: $path (${openingBook.size} entries)"))
    sys.env.get("MODEL_PATH").filter(_.nonEmpty) match
      case Some(path) =>
        val features      = sys.env.getOrElse("ORACLE_FEATURES", "rich")
        val modelArtifact = ArtifactProvenance.inspect(path, sys.env.get("MODEL_SHA256"), sys.env.get("MODEL_ID"))
        val rescore       = searchMode match
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
        val rescoreArtifact = sys.env
          .get("RESCORE_MODEL_PATH")
          .filter(_.nonEmpty)
          .map(path =>
            ArtifactProvenance.inspect(path, sys.env.get("RESCORE_MODEL_SHA256"), sys.env.get("RESCORE_MODEL_ID"))
          )
        if rescore.isEmpty && sys.env.contains("RESCORE_MODEL_SHA256") then
          sys.error("RESCORE_MODEL_SHA256 requires RESCORE_MODEL_PATH")
        val rescoreNote = rescore.fold("")(r => s", rescore=${r.modelPath} (weight=${r.weight})")
        println(
          s"[bot] ONNX model: $path (mode=${searchMode.id}, features=$features, " +
            s"timePolicy=${timePolicy.id}, candidateLimit=$candidateLimit$rescoreNote)"
        )
        val strategy = new Strategy(
          path,
          extractorFor(features),
          candidateLimit,
          overheadMs,
          defaultThink,
          rootRescore = rescore,
          searchMode = searchMode,
          timeManager = timeManager,
          openingBook = openingBook,
          preRankWithModel = preRankModel,
          tt = tt
        )
        logStartupProvenance(
          profile,
          modelArtifact,
          rescoreArtifact,
          bookArtifact,
          searchMode,
          features,
          candidateLimit,
          preRankModel,
          ttEnabled,
          ttCapacity,
          rescore.map(_.weight),
          timePolicy.id,
          overheadMs,
          defaultThink
        )
        strategy
      case None =>
        if sys.env.contains("MODEL_SHA256") then sys.error("MODEL_SHA256 requires MODEL_PATH")
        if sys.env.contains("RESCORE_MODEL_PATH") || sys.env.contains("RESCORE_MODEL_SHA256") then
          sys.error("RESCORE_MODEL_PATH requires MODEL_PATH")
        System.err.println(
          s"[bot] MODEL_PATH not set — using the bundled synthetic model " +
            s"(mode=${searchMode.id}, timePolicy=${timePolicy.id}; legal moves, no chess signal)"
        )
        val syntheticPath = syntheticModelPath()
        val modelArtifact = ArtifactProvenance.inspect(syntheticPath, None, Some("bundled-synthetic"))
        val strategy      = new Strategy(
          syntheticPath,
          OnnxFeatures.extract,
          candidateLimit,
          overheadMs,
          defaultThink,
          searchMode = searchMode,
          timeManager = timeManager,
          openingBook = openingBook,
          preRankWithModel = preRankModel,
          tt = tt
        )
        logStartupProvenance(
          profile,
          modelArtifact,
          None,
          bookArtifact,
          searchMode,
          "material",
          candidateLimit,
          preRankModel,
          ttEnabled,
          ttCapacity,
          None,
          timePolicy.id,
          overheadMs,
          defaultThink
        )
        strategy

  private[bot] def resolveSearchMode(id: String): SearchMode =
    SearchMode.get(id).getOrElse {
      val available = SearchMode.available.map(_.id).mkString("|")
      sys.error(s"unknown SEARCH_MODE '$id' (expected $available)")
    }

  private[bot] def resolveSearchProfile(id: String): SearchProfile =
    SearchProfile.get(id).getOrElse {
      val available = SearchProfile.available.map(_.id).mkString("|")
      sys.error(s"unknown BOT_PROFILE '$id' (expected $available)")
    }

  /** The named production profile is an atomic contract, not a bag of defaults. Legacy stays permissive for
    * compatibility, while `hybrid-star2-v1` refuses to boot if a required artifact, immutable identifier, or search
    * setting is missing. Engine 0.5.0 is deliberately rejected because its root-rescore path cannot feed a useful alpha
    * to Star1/Star2 (dicechess-engine#87).
    */
  private[bot] def validateProfile(profile: SearchProfile, env: Map[String, String], engineVersion: String): Unit =
    profile match
      case SearchProfile.Legacy        => ()
      case SearchProfile.HybridStar2V1 =>
        val required = List(
          "BOT_IDENTITY",
          "BOT_WRAPPER_VERSION",
          "SOURCE_REVISION",
          "IMAGE_DIGEST",
          "MODEL_PATH",
          "MODEL_ID",
          "MODEL_SHA256",
          "RESCORE_MODEL_PATH",
          "RESCORE_MODEL_ID",
          "RESCORE_MODEL_SHA256",
          "OPENING_BOOK_PATH",
          "OPENING_BOOK_ID",
          "OPENING_BOOK_SHA256",
          "SEARCH_MODE",
          "ORACLE_FEATURES",
          "ORACLE_CANDIDATE_LIMIT",
          "PRE_RANK_WITH_MODEL",
          "TT_ENABLED",
          "TT_CAPACITY",
          "RESCORE_WEIGHT",
          "TIME_POLICY",
          "OVERHEAD_BUFFER_MS",
          "DEFAULT_THINK_MS"
        )
        val missing = required.filter(name => env.get(name).forall(_.trim.isEmpty))
        if missing.nonEmpty then sys.error(s"BOT_PROFILE=${profile.id} requires non-empty ${missing.mkString(", ")}")
        if !supportsHybridStarPruning(engineVersion) then
          sys.error(
            s"BOT_PROFILE=${profile.id} requires a root-rescore-aware engine >= 0.5.1; found '$engineVersion'"
          )
        def requireValue(name: String, expected: String): Unit =
          val actual = env(name)
          if !actual.equalsIgnoreCase(expected) then
            sys.error(s"BOT_PROFILE=${profile.id} requires $name=$expected; found '$actual'")
        requireValue("SEARCH_MODE", SearchMode.Expectimax.id)
        requireValue("ORACLE_FEATURES", "rich")
        requireValue("PRE_RANK_WITH_MODEL", "true")
        requireValue("TT_ENABLED", "true")
        requireValue("TIME_POLICY", TimePolicies.EmpiricalV1.id)
        val Identity = raw"^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$$".r
        if !Identity.matches(env("BOT_IDENTITY")) then
          sys.error(
            s"BOT_PROFILE=${profile.id} requires BOT_IDENTITY as lowercase team/name slugs of at most 32 characters"
          )
        val Digest = raw"^(?:sha256:)?[0-9a-fA-F]{64}$$".r
        if !Digest.matches(env("IMAGE_DIGEST")) then
          sys.error(s"BOT_PROFILE=${profile.id} requires IMAGE_DIGEST as an immutable SHA-256 digest")
        val Revision = raw"^[0-9a-fA-F]{40}$$".r
        if !Revision.matches(env("SOURCE_REVISION")) then
          sys.error(s"BOT_PROFILE=${profile.id} requires SOURCE_REVISION as a full Git commit SHA")
        val WrapperVersion = raw"^v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$$".r
        if !WrapperVersion.matches(env("BOT_WRAPPER_VERSION")) then
          sys.error(s"BOT_PROFILE=${profile.id} requires BOT_WRAPPER_VERSION in canonical vX.Y.Z form")
        env("RESCORE_WEIGHT").toDoubleOption match
          case Some(weight) if weight == 0.5 => ()
          case _                             => sys.error(s"BOT_PROFILE=${profile.id} requires RESCORE_WEIGHT=0.5")
        val _ = env("ORACLE_CANDIDATE_LIMIT").toIntOption
          .filter(value => value > 0 && value <= MaxCandidateLimit)
          .getOrElse(
            sys.error(
              s"BOT_PROFILE=${profile.id} requires ORACLE_CANDIDATE_LIMIT in [1, $MaxCandidateLimit]"
            )
          )
        val _ = parseTtCapacity(env.get("TT_CAPACITY"))

  private[bot] def supportsHybridStarPruning(version: String): Boolean =
    val SemVer = raw"^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$$".r
    version match
      case SemVer(major, minor, patch) =>
        val parsed = List(major, minor, patch).map(value => Option(value).flatMap(_.toIntOption))
        parsed match
          case List(Some(majorValue), Some(minorValue), Some(patchValue)) =>
            val (requiredMajor, requiredMinor, requiredPatch) = MinimumHybridEngineVersion
            majorValue > requiredMajor ||
            (majorValue == requiredMajor && minorValue > requiredMinor) ||
            (majorValue == requiredMajor && minorValue == requiredMinor && patchValue >= requiredPatch)
          case _ => false
      case _ => false

  private[bot] def resolveTimePolicy(id: String): dicechess.engine.search.TimePolicy =
    TimePolicies.get(id).getOrElse {
      val available = TimePolicies.available.map(_.id).mkString("|")
      sys.error(s"unknown TIME_POLICY '$id' (expected $available)")
    }

  /** Extract the bundled synthetic model to a temp file — ONNX Runtime loads from a filesystem path. */
  private[bot] def syntheticModelPath(): String =
    val in = Option(getClass.getResourceAsStream("/synthetic_test_model.onnx"))
      .getOrElse(sys.error("bundled synthetic ONNX model '/synthetic_test_model.onnx' is missing"))
    val tmp = Files.createTempFile("synthetic_test_model", ".onnx")
    tmp.toFile.deleteOnExit()
    try Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING)
    finally in.close()
    tmp.toString

  private def envLong(name: String, default: Long): Long =
    sys.env.get(name) match
      case None      => default
      case Some(raw) =>
        raw.toLongOption
          .filter(value => value > 0 && value <= MaxBudgetMs)
          .getOrElse(sys.error(s"invalid $name '$raw' (expected an integer in [1, $MaxBudgetMs])"))

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name) match
      case None      => default
      case Some(raw) =>
        raw.toIntOption
          .filter(value => value > 0 && value <= MaxCandidateLimit)
          .getOrElse(sys.error(s"invalid $name '$raw' (expected an integer in [1, $MaxCandidateLimit])"))

  private[bot] def parseBoolean(name: String, value: Option[String], default: Boolean): Boolean = value match
    case None      => default
    case Some(raw) =>
      raw.trim.toLowerCase match
        case "true" | "1"  => true
        case "false" | "0" => false
        case _             => sys.error(s"invalid $name '$raw' (expected true|false|1|0)")

  private[bot] def parseTtCapacity(value: Option[String]): Int =
    val capacity = value match
      case None      => TranspositionTable.DefaultCapacity
      case Some(raw) => raw.toIntOption.getOrElse(sys.error(s"invalid TT_CAPACITY '$raw' (expected an integer)"))
    if capacity <= 0 || capacity > MaxTtCapacity || (capacity & (capacity - 1)) != 0 then
      sys.error(s"invalid TT_CAPACITY '$capacity' (expected a positive power of two up to $MaxTtCapacity)")
    capacity

  private def logStartupProvenance(
      profile: SearchProfile,
      model: ArtifactProvenance,
      rescore: Option[ArtifactProvenance],
      book: Option[ArtifactProvenance],
      searchMode: SearchMode,
      features: String,
      candidateLimit: Int,
      preRankWithModel: Boolean,
      ttEnabled: Boolean,
      ttCapacity: Int,
      rescoreWeight: Option[Double],
      timePolicy: String,
      overheadMs: Long,
      defaultThinkMs: Long
  ): Unit =
    val text             = (value: String) => s"\"${jsonEscape(value)}\""
    val optionalArtifact = (value: Option[ArtifactProvenance]) =>
      value.fold("null")(artifact => s"""{"id":${text(artifact.id)},"sha256":${text(artifact.sha256)}}""")
    val identity = sys.env.getOrElse("BOT_IDENTITY", "unspecified")
    val wrapper  = sys.env.getOrElse("BOT_WRAPPER_VERSION", "dev")
    val revision = sys.env.getOrElse("SOURCE_REVISION", "unknown")
    val image    = sys.env.getOrElse("IMAGE_DIGEST", "unknown")
    println(
      s"""{"event":"bot_startup","identity":${text(identity)},"profile":${text(profile.id)},""" +
        s""""wrapperVersion":${text(wrapper)},"sourceRevision":${text(revision)},""" +
        s""""engineVersion":${text(engineDependencyVersion)},""" +
        s""""imageDigest":${text(image)},"model":{"id":${text(model.id)},"sha256":${text(model.sha256)}},""" +
        s""""rescoreModel":${optionalArtifact(rescore)},"openingBook":${optionalArtifact(book)},""" +
        s""""searchMode":${text(searchMode.id)},"searchDepth":2,"chancePruning":"star1-star2",""" +
        s""""features":${text(features)},"candidateLimit":$candidateLimit,""" +
        s""""preRankWithModel":$preRankWithModel,"ttEnabled":$ttEnabled,"ttCapacity":$ttCapacity,""" +
        s""""rescoreWeight":${rescoreWeight.fold("null")(_.toString)},"timePolicy":${text(timePolicy)},""" +
        s""""overheadBufferMs":$overheadMs,"defaultThinkMs":$defaultThinkMs}"""
    )

  private def jsonEscape(value: String): String =
    value.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case char => char.toString
    }
