package com.fortemate.dicechess.bot

import dicechess.engine.domain.FenParser
import dicechess.engine.search.{
  OnnxFeatures,
  OpeningBookParser,
  RootRescoreModel,
  RootSearchStats,
  TimePolicies,
  TranspositionTable,
  TurnGenerator
}
import io.circe.Json
import io.circe.parser.parse
import scala.jdk.CollectionConverters.*

/** Proves the wiring — ONNX session + expectimax + book decorator + time-budget deadline — against the open synthetic
  * model (7-feature, no chess signal; the real model is a private artifact). A legal turn must come back; strength is
  * measured on the ladder, not here.
  */
class StrategySuite extends munit.FunSuite:

  private val syntheticModel = Strategy.syntheticModelPath()
  private val initialNbk     = FenParser.InitialPosition + " NBK"
  private val fixedCorpus    = List(
    initialNbk,
    FenParser.InitialPosition + " RPP",
    FenParser.InitialPosition + " QRN",
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1 BKP"
  )

  private val hybridProfileEnv = Map(
    "BOT_IDENTITY"           -> "dexus/atlas-1",
    "BOT_WRAPPER_VERSION"    -> "v0.2.0",
    "SOURCE_REVISION"        -> ("a" * 40),
    "IMAGE_DIGEST"           -> s"sha256:${"b" * 64}",
    "MODEL_PATH"             -> "/models/oracle-3.onnx",
    "MODEL_ID"               -> "oracle-3",
    "MODEL_SHA256"           -> ("c" * 64),
    "RESCORE_MODEL_PATH"     -> "/models/kcp.onnx",
    "RESCORE_MODEL_ID"       -> "kcp-20260809",
    "RESCORE_MODEL_SHA256"   -> ("d" * 64),
    "OPENING_BOOK_PATH"      -> "/models/opening-book.tsv",
    "OPENING_BOOK_ID"        -> "book-20260825",
    "OPENING_BOOK_SHA256"    -> ("e" * 64),
    "SEARCH_MODE"            -> "expectimax",
    "ORACLE_FEATURES"        -> "rich",
    "ORACLE_CANDIDATE_LIMIT" -> "8",
    "PRE_RANK_WITH_MODEL"    -> "true",
    "TT_ENABLED"             -> "true",
    "TT_CAPACITY"            -> "262144",
    "RESCORE_WEIGHT"         -> "0.5",
    "TIME_POLICY"            -> "empirical-v1",
    "OVERHEAD_BUFFER_MS"     -> "1000",
    "DEFAULT_THINK_MS"       -> "2000"
  )

  private def startupProvenance(
      searchMode: Strategy.SearchMode,
      modelId: String = "bundled-synthetic"
  ): Strategy.StartupProvenance =
    Strategy.StartupProvenance(
      profile = Strategy.SearchProfile.Legacy,
      model = ArtifactProvenance(modelId, "f" * 64),
      rescore = None,
      book = Some(ArtifactProvenance("bundled-sample", "e" * 64)),
      searchMode = searchMode,
      features = "material",
      candidateLimit = 4,
      preRankWithModel = false,
      ttEnabled = false,
      ttCapacity = 1024,
      rescoreWeight = None,
      timePolicy = TimePolicies.default.id,
      overheadMs = 300,
      defaultThinkMs = 2000
    )

  private def parseStartup(provenance: Strategy.StartupProvenance): Json =
    parse(Strategy.startupProvenanceJson(provenance)).fold(
      error => fail(s"startup provenance must be valid JSON: ${error.message}"),
      identity
    )

  private def withStrategy[A](f: Strategy => A): A =
    val s =
      new Strategy(syntheticModel, OnnxFeatures.extract, candidateLimit = 4, overheadBufferMs = 5, defaultThinkMs = 200)
    try f(s)
    finally s.close()

  private def legalPaths(dfen: String): Set[List[String]] =
    TurnGenerator.generateAllLegalTurnPaths(FenParser.parse(dfen).toOption.get).map(_.map(Strategy.toUci)).toSet

  test("returns one of the engine's own legal turn paths under a tight deadline"):
    withStrategy { s =>
      val moves = s.chooseMoves(initialNbk, Some(800L), 3000L)
      assert(moves.nonEmpty, "the opening roll NBK must have legal moves")
      assert(legalPaths(initialNbk).contains(moves), s"$moves must be a legal path")
    }

  test("plays a legal move with no clock (unlimited control)"):
    withStrategy { s =>
      val moves = s.chooseMoves(initialNbk, None, 0L)
      assert(moves.nonEmpty)
      assert(legalPaths(initialNbk).contains(moves))
    }

  test("an unusable dfen yields no moves (the server auto-passes)"):
    withStrategy(s => assertEquals(s.chooseMoves("not-a-fen", Some(1000L), 0L), Nil))

  test("a root rescorer wired through the second ONNX session still yields a legal turn"):
    // The synthetic model doubles as the rescorer (with its own 7-feature extractor) — this proves the
    // two-session wiring, not the KCP signal; the real rescorer is a private KcpFeatures model.
    val rescore = Some(RootRescoreModel(syntheticModel, OnnxFeatures.extract, weight = 0.5))
    val s       = new Strategy(
      syntheticModel,
      OnnxFeatures.extract,
      candidateLimit = 4,
      overheadBufferMs = 5,
      defaultThinkMs = 200,
      rootRescore = rescore
    )
    try
      val moves = s.chooseMoves(initialNbk, Some(800L), 3000L)
      assert(moves.nonEmpty)
      assert(legalPaths(initialNbk).contains(moves), s"$moves must be a legal path")
    finally s.close()

  test("one-ply mode returns a legal turn through the same webhook strategy"):
    val s = new Strategy(
      syntheticModel,
      OnnxFeatures.extract,
      candidateLimit = 4,
      overheadBufferMs = 5,
      defaultThinkMs = 200,
      searchMode = Strategy.SearchMode.OnePly
    )
    try
      val moves = s.chooseMoves(initialNbk, Some(800L), 3000L)
      assert(moves.nonEmpty)
      assert(legalPaths(initialNbk).contains(moves), s"$moves must be a legal path")
    finally s.close()

  test("startup provenance reports truthful search depth and chance pruning for each mode"):
    val expectimax = parseStartup(startupProvenance(Strategy.SearchMode.Expectimax)).hcursor
    assertEquals(expectimax.get[String]("searchMode").toOption, Some("expectimax"))
    assertEquals(expectimax.get[Int]("searchDepth").toOption, Some(2))
    assertEquals(expectimax.get[String]("chancePruning").toOption, Some("star1-star2"))

    val onePly = parseStartup(startupProvenance(Strategy.SearchMode.OnePly)).hcursor
    assertEquals(onePly.get[String]("searchMode").toOption, Some("one-ply"))
    assertEquals(onePly.get[Int]("searchDepth").toOption, Some(1))
    assertEquals(onePly.get[String]("chancePruning").toOption, Some("none"))

  test("startup provenance escapes every JSON control character and round-trips its value"):
    val controls = (0 to 0x1f).map(_.toChar).mkString
    val modelId  = s"model-$controls-end"
    val startup  = parseStartup(startupProvenance(Strategy.SearchMode.Expectimax, modelId))
    assertEquals(startup.hcursor.downField("model").get[String]("id").toOption, Some(modelId))

  test("resolves stable search mode and time policy ids"):
    assertEquals(Strategy.resolveSearchMode("ONE-PLY"), Strategy.SearchMode.OnePly)
    assertEquals(Strategy.resolveTimePolicy("legacy-linear-v1"), TimePolicies.LegacyLinear)

  test("rejects unknown search modes and time policies"):
    interceptMessage[RuntimeException]("unknown SEARCH_MODE 'deep' (expected expectimax|one-ply)") {
      Strategy.resolveSearchMode("deep")
    }
    interceptMessage[RuntimeException](
      "unknown TIME_POLICY 'future-v1' (expected empirical-v1|legacy-linear-v1)"
    ) {
      Strategy.resolveTimePolicy("future-v1")
    }

  test("resolves profiles and reads the engine version from generated build metadata"):
    assertEquals(Strategy.resolveSearchProfile("HYBRID-STAR2-V1"), Strategy.SearchProfile.HybridStar2V1)
    assert(
      raw"^\d+\.\d+\.\d+(?:[-+].*)?$$".r.matches(Strategy.engineDependencyVersion),
      s"unexpected engine version '${Strategy.engineDependencyVersion}'"
    )
    interceptMessage[RuntimeException]("unknown BOT_PROFILE 'future' (expected legacy|hybrid-star2-v1)") {
      Strategy.resolveSearchProfile("future")
    }

  test("hybrid-star2-v1 is atomic and rejects the known-bad engine release"):
    val blocked = intercept[RuntimeException] {
      Strategy.validateProfile(Strategy.SearchProfile.HybridStar2V1, hybridProfileEnv, "0.5.0")
    }
    assert(blocked.getMessage.contains("requires a root-rescore-aware engine >= 0.5.1"))

    Strategy.validateProfile(Strategy.SearchProfile.HybridStar2V1, hybridProfileEnv, "0.5.1")
    Strategy.validateProfile(Strategy.SearchProfile.HybridStar2V1, hybridProfileEnv, "0.6.0")
    assert(!Strategy.supportsHybridStarPruning("0.5.0"))
    assert(Strategy.supportsHybridStarPruning("0.5.1-SNAPSHOT"))

    val missing = intercept[RuntimeException] {
      Strategy.validateProfile(
        Strategy.SearchProfile.HybridStar2V1,
        hybridProfileEnv.removed("OPENING_BOOK_SHA256"),
        "0.5.1"
      )
    }
    assert(missing.getMessage.contains("requires non-empty OPENING_BOOK_SHA256"))

    val mutableImage = intercept[RuntimeException] {
      Strategy.validateProfile(
        Strategy.SearchProfile.HybridStar2V1,
        hybridProfileEnv.updated("IMAGE_DIGEST", "latest"),
        "0.5.1"
      )
    }
    assert(mutableImage.getMessage.contains("requires IMAGE_DIGEST as an immutable SHA-256 digest"))

  test("hybrid-star2-v1 accepts only exact lowercase slug identities"):
    Strategy.validateProfile(Strategy.SearchProfile.HybridStar2V1, hybridProfileEnv, "0.5.1")
    Strategy.validateProfile(
      Strategy.SearchProfile.HybridStar2V1,
      hybridProfileEnv.updated("BOT_IDENTITY", s"${"a" * 32}/${"9" * 32}"),
      "0.5.1"
    )

    List(
      "Dexus/atlas-1",
      "dexus/Atlas-1",
      "dexus/atlas_1",
      "-dexus/atlas-1",
      "dexus/-atlas",
      "dexus/atlas/1",
      s"${"a" * 33}/atlas-1",
      s"dexus/${"a" * 33}"
    ).foreach { identity =>
      val error = intercept[RuntimeException] {
        Strategy.validateProfile(
          Strategy.SearchProfile.HybridStar2V1,
          hybridProfileEnv.updated("BOT_IDENTITY", identity),
          "0.5.1"
        )
      }
      assert(
        error.getMessage.contains("requires BOT_IDENTITY as lowercase team/name slugs of at most 32 characters"),
        s"unexpected validation error for '$identity': ${error.getMessage}"
      )
    }

  test("hybrid-star2-v1 accepts only canonical vX.Y.Z wrapper versions"):
    List("v0.0.0", "v1.2.3", "v10.20.30").foreach { version =>
      Strategy.validateProfile(
        Strategy.SearchProfile.HybridStar2V1,
        hybridProfileEnv.updated("BOT_WRAPPER_VERSION", version),
        "0.5.1"
      )
    }

    List("0.2.0", "v1.2", "v1.2.3-rc1", "v01.2.3", "latest", "V1.2.3").foreach { version =>
      val error = intercept[RuntimeException] {
        Strategy.validateProfile(
          Strategy.SearchProfile.HybridStar2V1,
          hybridProfileEnv.updated("BOT_WRAPPER_VERSION", version),
          "0.5.1"
        )
      }
      assert(
        error.getMessage.contains("requires BOT_WRAPPER_VERSION in canonical vX.Y.Z form"),
        s"unexpected validation error for '$version': ${error.getMessage}"
      )
    }

  test("strictly parses model pre-ranking and TT configuration"):
    assert(Strategy.parseBoolean("TT_ENABLED", Some("true"), default = false))
    assert(Strategy.parseBoolean("PRE_RANK_WITH_MODEL", Some("1"), default = false))
    assert(!Strategy.parseBoolean("TT_ENABLED", Some("FALSE"), default = true))
    assertEquals(Strategy.parseTtCapacity(None), TranspositionTable.DefaultCapacity)
    assertEquals(Strategy.parseTtCapacity(Some("1024")), 1024)

    interceptMessage[RuntimeException]("invalid TT_ENABLED 'yes' (expected true|false|1|0)") {
      Strategy.parseBoolean("TT_ENABLED", Some("yes"), default = false)
    }
    interceptMessage[RuntimeException](
      "invalid TT_CAPACITY '1000' (expected a positive power of two up to 4194304)"
    ) {
      Strategy.parseTtCapacity(Some("1000"))
    }
    interceptMessage[RuntimeException](
      "invalid TT_CAPACITY '8388608' (expected a positive power of two up to 4194304)"
    ) {
      Strategy.parseTtCapacity(Some("8388608"))
    }

  test("model pre-ranking and a per-strategy TT produce observable hit telemetry"):
    val seen = scala.collection.mutable.ListBuffer.empty[RootSearchStats]
    val s    = new Strategy(
      syntheticModel,
      OnnxFeatures.extract,
      candidateLimit = 1,
      overheadBufferMs = 5,
      defaultThinkMs = 3000,
      openingBook = Map.empty,
      statsSink = stats => seen += stats,
      preRankWithModel = true,
      tt = Some(new TranspositionTable(1024))
    )
    try
      val first  = s.chooseMoves(initialNbk, None, 0L)
      val second = s.chooseMoves(initialNbk, None, 0L)
      assert(legalPaths(initialNbk).contains(first))
      assert(legalPaths(initialNbk).contains(second))
      assertEquals(seen.size, 2)
      assert(seen.last.ttProbes > 0)
      assert(seen.last.ttHits > 0, s"expected a repeated-position TT hit, got ${seen.last}")
    finally s.close()

  test("fixed corpus exercises deterministic pre-rank and TT on/off profiles"):
    def run(preRank: Boolean, ttEnabled: Boolean): (List[List[String]], List[RootSearchStats]) =
      val seen     = scala.collection.mutable.ListBuffer.empty[RootSearchStats]
      val strategy = new Strategy(
        syntheticModel,
        OnnxFeatures.extract,
        candidateLimit = 2,
        overheadBufferMs = 5,
        defaultThinkMs = 10000,
        openingBook = Map.empty,
        statsSink = stats => seen += stats,
        preRankWithModel = preRank,
        tt = Option.when(ttEnabled)(new TranspositionTable(1024)),
        random = new scala.util.Random(42L)
      )
      try
        val dfens = fixedCorpus ++ fixedCorpus
        val moves = dfens.map(dfen => strategy.chooseMoves(dfen, None, 0L))
        dfens.zip(moves).foreach { (dfen, path) =>
          assert(path.nonEmpty, s"expected a move for $dfen")
          assert(legalPaths(dfen).contains(path), s"$path must be legal for $dfen")
        }
        (moves, seen.toList)
      finally strategy.close()

    for
      preRank   <- List(false, true)
      ttEnabled <- List(false, true)
    do
      val (firstMoves, firstStats)   = run(preRank, ttEnabled)
      val (secondMoves, secondStats) = run(preRank, ttEnabled)
      assertEquals(secondMoves, firstMoves, s"preRank=$preRank tt=$ttEnabled must be seed-deterministic")
      assertEquals(firstStats.size, fixedCorpus.size * 2)
      assertEquals(secondStats.size, fixedCorpus.size * 2)
      if ttEnabled then
        assert(firstStats.exists(_.ttHits > 0), s"preRank=$preRank must observe a repeated-position TT hit")
      else assert(firstStats.forall(stats => stats.ttProbes == 0 && stats.ttHits == 0))

  test("bundled opening book is a five-entry TSV format sample"):
    val source = scala.io.Source.fromResource("opening_book.tsv")
    try
      val parsed = OpeningBookParser.parse(source.mkString)
      assertEquals(parsed.map(_.size), Right(5))
    finally source.close()

  test("loads a private opening book from an external path"):
    val source   = scala.io.Source.fromResource("opening_book.tsv")
    val contents = try source.mkString
    finally source.close()
    val path = java.nio.file.Files.createTempFile("opening_book", ".tsv")
    try
      java.nio.file.Files.writeString(path, contents)
      assertEquals(Strategy.loadOpeningBook(Some(path.toString)).size, 5)
    finally java.nio.file.Files.deleteIfExists(path)

  test("concurrent callers are serialized per strategy and still return legal results"):
    withStrategy { s =>
      val testDfens = fixedCorpus

      val legalByDfen = testDfens.map(dfen => dfen -> legalPaths(dfen)).toMap
      val executor    = java.util.concurrent.Executors.newFixedThreadPool(4)
      try
        val tasks = for
          i <- 0 until 8
          dfen = testDfens(i % testDfens.size)
        yield new java.util.concurrent.Callable[(String, List[String])] {
          def call(): (String, List[String]) =
            (dfen, s.chooseMoves(dfen, Some(100L), 2000L))
        }

        val futures = executor.invokeAll(tasks.asJava, 20, java.util.concurrent.TimeUnit.SECONDS)
        for future <- futures.asScala do
          assert(!future.isCancelled, "task was cancelled due to timeout")
          val (dfen, moves) = future.get()
          assert(moves.nonEmpty, s"legal moves expected for $dfen")
          assert(legalByDfen(dfen).contains(moves), s"$moves must be a legal path for $dfen")
      finally
        executor.shutdown()
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
    }
