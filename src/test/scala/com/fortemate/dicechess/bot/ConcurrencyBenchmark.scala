package com.fortemate.dicechess.bot

import dicechess.engine.domain.FenParser
import dicechess.engine.search.{OnnxFeatures, RootSearchStats}

import java.util.concurrent.{Callable, ConcurrentLinkedQueue, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

object ConcurrencyBenchmark:

  private val syntheticModel = Strategy.syntheticModelPath()

  private val dfens = List(
    FenParser.InitialPosition + " NBK",
    FenParser.InitialPosition + " RPP",
    FenParser.InitialPosition + " QRN",
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1 BKP",
    "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3 RKN"
  )

  def main(args: Array[String]): Unit =
    println("=== Starting ONNX Concurrency Benchmark ===")
    println(s"Available processors (logical cores): ${Runtime.getRuntime.availableProcessors()}")

    val collectedStats = new ConcurrentLinkedQueue[RootSearchStats]()

    val strategy = new Strategy(
      syntheticModel,
      OnnxFeatures.extract,
      candidateLimit = 4,
      overheadBufferMs = 5,
      defaultThinkMs = 100,
      statsSink = (stats: RootSearchStats) => collectedStats.add(stats)
    )

    try
      // Warmup
      for _ <- 1 to 20 do strategy.chooseMoves(dfens.head, Some(100L), 1000L)
      collectedStats.clear()

      val requestCounts = List(1, 2, 4, 8)
      val totalRequests = 60

      println("\n--- 1. CALLER-SERIALIZED (external synchronized lock) ---")
      val lock = new Object()
      for concurrency <- requestCounts do
        collectedStats.clear()
        runScenario(
          strategy,
          concurrency,
          totalRequests,
          collectedStats,
          (s, dfen) =>
            lock.synchronized {
              s.chooseMoves(dfen, Some(100L), 1000L)
            }
        )

      println("\n--- 2. CONCURRENT CALLERS (Strategy serializes ONNX/TT internally) ---")
      for concurrency <- requestCounts do
        collectedStats.clear()
        runScenario(
          strategy,
          concurrency,
          totalRequests,
          collectedStats,
          (s, dfen) => s.chooseMoves(dfen, Some(100L), 1000L)
        )
    finally strategy.close()

  private def runScenario(
      strategy: Strategy,
      concurrency: Int,
      totalRequests: Int,
      statsQueue: ConcurrentLinkedQueue[RootSearchStats],
      invoke: (Strategy, String) => List[String]
  ): Unit =
    val executor = Executors.newFixedThreadPool(concurrency)
    try
      val latencies = new ConcurrentLinkedQueue[Long]()
      val tasks     = (0 until totalRequests).map { i =>
        val dfen = dfens(i % dfens.size)
        new Callable[Unit] {
          def call(): Unit =
            val start      = System.nanoTime()
            val moves      = invoke(strategy, dfen)
            val durationMs = (System.nanoTime() - start) / 1_000_000L
            latencies.add(durationMs)
            assert(moves.nonEmpty)
        }
      }

      val wallClockStart = System.nanoTime()
      val futures        = executor.invokeAll(tasks.asJava, 30, TimeUnit.SECONDS)
      for f <- futures.asScala do
        assert(!f.isCancelled, "task was cancelled due to timeout")
        f.get()
      val totalWallClockMs = (System.nanoTime() - wallClockStart) / 1_000_000L

      val sorted     = latencies.asScala.toList.sorted
      val p50        = sorted((sorted.size * 0.50).toInt)
      val p90        = sorted((sorted.size * 0.90).toInt)
      val p99        = sorted((sorted.size * 0.99).toInt)
      val throughput =
        if totalWallClockMs <= 0 then Double.NaN else (totalRequests.toDouble / totalWallClockMs) * 1000.0

      val statsList    = statsQueue.asScala.toList
      val avgCompleted =
        if statsList.isEmpty then 0.0 else statsList.map(_.candidatesCompleted).sum.toDouble / statsList.size
      val truncPct =
        if statsList.isEmpty then 0.0 else (statsList.count(_.deadlineTruncated).toDouble / statsList.size) * 100.0
      val fallbackPct =
        if statsList.isEmpty then 0.0 else (statsList.count(_.fellBackToPreRank).toDouble / statsList.size) * 100.0

      println(
        f"Concurrency $concurrency%2d: Total ${totalWallClockMs}%5d ms | Throughput: ${throughput}%6.2f req/s | " +
          f"Latency (ms): p50=$p50%3d, p90=$p90%3d, p99=$p99%3d | " +
          f"Search: avgCompleted=$avgCompleted%4.2f, truncated=$truncPct%5.1f%%, fallback=$fallbackPct%5.1f%%"
      )
    finally
      executor.shutdown()
      executor.awaitTermination(10, TimeUnit.SECONDS)
