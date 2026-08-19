package com.fortemate.dicechess.bot

import dicechess.engine.domain.FenParser
import dicechess.engine.search.{OnnxFeatures, OpeningBookParser, RootRescoreModel, TimePolicies, TurnGenerator}
import scala.jdk.CollectionConverters.*

/** Proves the wiring — ONNX session + expectimax + book decorator + time-budget deadline — against the open synthetic
  * model (7-feature, no chess signal; the real model is a private artifact). A legal turn must come back; strength is
  * measured on the ladder, not here.
  */
class StrategySuite extends munit.FunSuite:

  private val syntheticModel =
    val tmp = java.nio.file.Files.createTempFile("synthetic_test_model", ".onnx")
    tmp.toFile.deleteOnExit()
    val in = getClass.getResourceAsStream("/synthetic_test_model.onnx")
    try java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    finally in.close()
    tmp.toString
  private val initialNbk = FenParser.InitialPosition + " NBK"

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

  test("concurrent chooseMoves calls across multiple threads return legal and independent results"):
    withStrategy { s =>
      val testDfens = List(
        initialNbk,
        FenParser.InitialPosition + " RPP",
        FenParser.InitialPosition + " QRN",
        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1 BKP"
      )

      val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
      try
        val tasks = for
          i <- 0 until 32
          dfen = testDfens(i % testDfens.size)
        yield new java.util.concurrent.Callable[(String, List[String])] {
          def call(): (String, List[String]) =
            (dfen, s.chooseMoves(dfen, Some(500L), 2000L))
        }

        val futures = executor.invokeAll(tasks.asJava, 10, java.util.concurrent.TimeUnit.SECONDS)
        for future <- futures.asScala do
          assert(!future.isCancelled, "task was cancelled due to timeout")
          val (dfen, moves) = future.get()
          assert(moves.nonEmpty, s"legal moves expected for $dfen")
          assert(legalPaths(dfen).contains(moves), s"$moves must be a legal path for $dfen")
      finally
        executor.shutdown()
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
    }
