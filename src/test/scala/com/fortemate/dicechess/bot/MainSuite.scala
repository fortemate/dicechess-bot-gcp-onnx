package com.fortemate.dicechess.bot

import dicechess.engine.domain.FenParser
import dicechess.engine.search.{OnnxFeatures, TurnGenerator}
import io.circe.parser.parse
import com.fortemate.dicechess.runtime.{Signatures, WebhookHandler}

/** Proves `Main`'s wiring end to end over a real socket: the library's `WebhookHandler` talking to our ONNX-backed
  * `Strategy`. The signed turn carries a clock + a Fischer `timeControl`, so the whole chain (wire →
  * `Clock.incrementMillis()` → `TimeManager` → ONNX expectimax) is exercised. Uses the open synthetic model; a small
  * clock keeps the deadline short so the test stays fast.
  */
class MainSuite extends munit.FunSuite:

  private val Secret         = "test-webhook-secret"
  private val syntheticModel = Strategy.syntheticModelPath()
  private val initialNbk     = FenParser.InitialPosition + " NBK"

  test("end to end over real HTTP: a signed, clocked turn returns a path the engine considers legal"):
    val strategy =
      new Strategy(syntheticModel, OnnxFeatures.extract, candidateLimit = 4, overheadBufferMs = 5, defaultThinkMs = 200)
    val server = Main.start(port = 0, secret = Secret, strategy = strategy)
    try
      val base   = s"http://127.0.0.1:${server.getAddress.getPort}/api/webhook"
      val client = java.net.http.HttpClient.newHttpClient()

      def post(body: String, headers: Map[String, String]): java.net.http.HttpResponse[String] =
        val builder = java.net.http.HttpRequest
          .newBuilder(java.net.URI.create(base))
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        headers.foreach((k, v) => builder.header(k, v))
        client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())

      val handshake = post("""{"type":"verification","nonce":"live-1"}""", Map.empty)
      assertEquals(handshake.statusCode(), 200)
      assertEquals(parse(handshake.body()).toOption.get.hcursor.get[String]("nonce"), Right("live-1"))

      val body =
        s"""{"type":"yourTurn","gameId":"g1","seat":"White","state":{"dfen":"$initialNbk","clocks":{"white":800,"black":800},"timeControl":{"Fischer":{"initialSeconds":300,"incrementSeconds":3}}}}"""
      val ts   = System.currentTimeMillis() / 1000
      val turn = post(
        body,
        Map(
          WebhookHandler.TIMESTAMP_HEADER -> ts.toString,
          WebhookHandler.SIGNATURE_HEADER -> Signatures.sign(Secret, ts, body)
        )
      )
      assertEquals(turn.statusCode(), 200)
      val moves = parse(turn.body()).toOption.get.hcursor.get[List[String]]("moves").toOption.get
      assert(moves.nonEmpty, "the opening roll NBK must have legal moves")
      val state      = FenParser.parse(initialNbk).toOption.get
      val legalPaths = TurnGenerator.generateAllLegalTurnPaths(state).map(_.map(Strategy.toUci))
      assert(legalPaths.contains(moves), s"$moves must be one of the engine's own legal paths")
    finally
      server.stop(0)
      strategy.close()
