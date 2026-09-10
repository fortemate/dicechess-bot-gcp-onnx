package com.fortemate.dicechess.bot

import com.fortemate.dicechess.runtime.{Signatures, WebhookHandler}
import dicechess.engine.domain.FenParser
import dicechess.engine.search.{OnnxFeatures, TurnGenerator}
import io.circe.parser.parse

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** Proves `Main`'s wiring end to end over a real socket: the library's `WebhookHandler` talking to our ONNX-backed
  * `Strategy` and policy delegates. Tests HMAC signing, clocks, turn responses, draw decisions, doubling opportunities
  * and responses, safe defaults, and error handling.
  */
class MainSuite extends munit.FunSuite:

  private val Secret         = "test-webhook-secret"
  private val syntheticModel = Strategy.syntheticModelPath()
  private val initialNbk     = FenParser.InitialPosition + " NBK"
  private val noDiceFen      = FenParser.InitialPosition

  private def withServer(strat: Strategy)(testCode: (HttpClient, String) => Unit): Unit =
    val server = Main.start(port = 0, secret = Secret, strategy = strat)
    try
      val base   = s"http://127.0.0.1:${server.getAddress.getPort}/api/webhook"
      val client = HttpClient.newHttpClient()
      testCode(client, base)
    finally
      server.stop(0)
      strat.close()

  private def postSigned(
      client: HttpClient,
      url: String,
      body: String,
      timestamp: Long = System.currentTimeMillis() / 1000,
      secret: String = Secret
  ): HttpResponse[String] =
    val req = HttpRequest
      .newBuilder(URI.create(url))
      .header(WebhookHandler.TIMESTAMP_HEADER, timestamp.toString)
      .header(WebhookHandler.SIGNATURE_HEADER, Signatures.sign(secret, timestamp, body))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    client.send(req, HttpResponse.BodyHandlers.ofString())

  private def postRaw(
      client: HttpClient,
      url: String,
      body: String,
      headers: Map[String, String] = Map.empty
  ): HttpResponse[String] =
    val builder = HttpRequest
      .newBuilder(URI.create(url))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
    headers.foreach((k, v) => builder.header(k, v))
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

  private def createSyntheticStrategy(): Strategy =
    new Strategy(
      syntheticModel,
      OnnxFeatures.extract,
      candidateLimit = 4,
      overheadBufferMs = 5,
      defaultThinkMs = 200
    )

  test("production entry point rejects an absent or empty webhook secret"):
    interceptMessage[RuntimeException]("DICECHESS_WEBHOOK_SECRET must be set and non-empty") {
      Main.requireWebhookSecret(None)
    }
    interceptMessage[RuntimeException]("DICECHESS_WEBHOOK_SECRET must be set and non-empty") {
      Main.requireWebhookSecret(Some("  "))
    }
    assertEquals(Main.requireWebhookSecret(Some(Secret)), Secret)

  test("end to end over real HTTP: a signed, clocked turn returns a path the engine considers legal"):
    withServer(createSyntheticStrategy()) { (client, url) =>
      val handshake = postRaw(client, url, """{"type":"verification","nonce":"live-1"}""")
      assertEquals(handshake.statusCode(), 200)
      assertEquals(parse(handshake.body()).toOption.get.hcursor.get[String]("nonce"), Right("live-1"))

      val clocks = """{"white":800,"black":800},"timeControl":{"Fischer":{"initialSeconds":300,"incrementSeconds":3}}"""
      val body   = TestHelpers.makeEnvelope("yourTurn", "White", initialNbk, dicePending = true, clocks = Some(clocks))
      val turn   = postSigned(client, url, body)
      assertEquals(turn.statusCode(), 200)

      val json      = parse(turn.body()).toOption.get
      val moves     = json.hcursor.get[List[String]]("moves").toOption.get
      val offerDraw = json.hcursor.get[Boolean]("offerDraw").toOption.get
      assert(moves.nonEmpty, "the opening roll NBK must have legal moves")
      assert(!offerDraw, "default synthetic strategy must not offer a draw")

      val state      = FenParser.parse(initialNbk).toOption.get
      val legalPaths = TurnGenerator.generateAllLegalTurnPaths(state).map(_.map(Strategy.toUci))
      assert(legalPaths.contains(moves), s"$moves must be one of the engine's own legal paths")
    }

  test("end to end over real HTTP: turn offers draw when permitted by context and engine policy returns true"):
    val drawStrat = new Strategy(new TestHelpers.ConfigurableSearch(offerDraw = true))
    withServer(drawStrat) { (client, url) =>
      val body = TestHelpers.makeEnvelope("yourTurn", "White", initialNbk, dicePending = true, mayOfferDraw = true)
      val res  = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)
      val json      = parse(res.body()).toOption.get
      val offerDraw = json.hcursor.get[Boolean]("offerDraw").toOption.get
      assert(offerDraw, "turn must offer draw when permitted and engine policy agrees")
    }

  test("end to end over real HTTP: turn does not offer draw when permitted but engine policy returns false"):
    val noDrawStrat = new Strategy(new TestHelpers.ConfigurableSearch(offerDraw = false))
    withServer(noDrawStrat) { (client, url) =>
      val body = TestHelpers.makeEnvelope("yourTurn", "White", initialNbk, dicePending = true, mayOfferDraw = true)
      val res  = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)
      val json      = parse(res.body()).toOption.get
      val offerDraw = json.hcursor.get[Boolean]("offerDraw").toOption.get
      assert(!offerDraw, "turn must not offer draw when engine policy returns false")
    }

  test("end to end over real HTTP: turn does not offer draw when engine policy is true but context does not permit it"):
    val drawStrat = new Strategy(new TestHelpers.ConfigurableSearch(offerDraw = true))
    withServer(drawStrat) { (client, url) =>
      val body = TestHelpers.makeEnvelope("yourTurn", "White", initialNbk, dicePending = true, mayOfferDraw = false)
      val res  = postSigned(client, url, body)
      assertEquals(res.statusCode(), 200)
      val json      = parse(res.body()).toOption.get
      val offerDraw = json.hcursor.get[Boolean]("offerDraw").toOption.get
      assert(!offerDraw, "turn must not offer draw when context does not permit it")
    }

  private def testDecision(
      acceptStrat: Strategy,
      declineStrat: Strategy,
      body: String,
      field: String
  ): Unit =
    for (strat, expected) <- List((acceptStrat, true), (declineStrat, false)) do
      withServer(strat) { (client, url) =>
        val res = postSigned(client, url, body)
        assertEquals(res.statusCode(), 200)
        assertEquals(parse(res.body()).toOption.get.hcursor.get[Boolean](field), Right(expected))
      }

  test("end to end over real HTTP: draw decision accept/decline responses"):
    val acceptStrat  = new Strategy(new TestHelpers.ConfigurableSearch(acceptDraw = true))
    val declineStrat = new Strategy(new TestHelpers.ConfigurableSearch(acceptDraw = false))
    val body         = TestHelpers.makeEnvelope("drawDecision", "White", noDiceFen, drawOfferPending = true)
    testDecision(acceptStrat, declineStrat, body, "acceptDraw")

  test("end to end over real HTTP: double opportunity offer/roll responses"):
    val offerStrat = new Strategy(new TestHelpers.ConfigurableSearch(offerDouble = true))
    val rollStrat  = new Strategy(new TestHelpers.ConfigurableSearch(offerDouble = false))
    val body       = TestHelpers.makeEnvelope(
      "doubleOpportunity",
      "White",
      noDiceFen,
      doublingState = Some(TestHelpers.doublingJson("offer", "White"))
    )
    testDecision(offerStrat, rollStrat, body, "offerDouble")

  test("end to end over real HTTP: double decision accept/decline responses"):
    val acceptStrat  = new Strategy(new TestHelpers.ConfigurableSearch(acceptDouble = true))
    val declineStrat = new Strategy(new TestHelpers.ConfigurableSearch(acceptDouble = false))
    val body         = TestHelpers.makeEnvelope(
      "doubleDecision",
      "Black",
      noDiceFen,
      activeSeat = "Black",
      doublingState =
        Some(TestHelpers.doublingJson("response", "Black", offeredBy = Some("White"), mayOfferDouble = false))
    )
    testDecision(acceptStrat, declineStrat, body, "acceptDouble")

  test("end to end over real HTTP: safe defaults with synthetic ONNX strategy"):
    withServer(createSyntheticStrategy()) { (client, url) =>
      val drawBody = TestHelpers.makeEnvelope("drawDecision", "White", noDiceFen, drawOfferPending = true)
      val drawRes  = postSigned(client, url, drawBody)
      assertEquals(drawRes.statusCode(), 200)
      assertEquals(parse(drawRes.body()).toOption.get.hcursor.get[Boolean]("acceptDraw"), Right(false))

      val oppBody = TestHelpers.makeEnvelope(
        "doubleOpportunity",
        "White",
        noDiceFen,
        doublingState = Some(TestHelpers.doublingJson("offer", "White"))
      )
      val oppRes = postSigned(client, url, oppBody)
      assertEquals(oppRes.statusCode(), 200)
      assertEquals(parse(oppRes.body()).toOption.get.hcursor.get[Boolean]("offerDouble"), Right(false))
    }

  test("end to end over real HTTP: rejects missing or invalid signatures"):
    withServer(createSyntheticStrategy()) { (client, url) =>
      val body = TestHelpers.makeEnvelope("yourTurn", "White", initialNbk, dicePending = true)

      val noSig = postRaw(client, url, body)
      assertEquals(noSig.statusCode(), 401)

      val badSig = postSigned(client, url, body, secret = "wrong-secret")
      assertEquals(badSig.statusCode(), 401)
    }

  test("end to end over real HTTP: rejects expired timestamp"):
    withServer(createSyntheticStrategy()) { (client, url) =>
      val body       = TestHelpers.makeEnvelope("yourTurn", "White", initialNbk, dicePending = true)
      val expiredSec = (System.currentTimeMillis() / 1000) - 400
      val res        = postSigned(client, url, body, timestamp = expiredSec)
      assertEquals(res.statusCode(), 401)
    }

  test("end to end over real HTTP: malformed DFEN in turn and decisions fail closed"):
    withServer(createSyntheticStrategy()) { (client, url) =>
      val turnBody = TestHelpers.makeEnvelope("yourTurn", "White", "invalid-dfen", dicePending = true)
      val turnRes  = postSigned(client, url, turnBody)
      assertEquals(turnRes.statusCode(), 200)
      val turnJson = parse(turnRes.body()).toOption.get
      assertEquals(turnJson.hcursor.get[List[String]]("moves"), Right(Nil))
      assertEquals(turnJson.hcursor.get[Boolean]("offerDraw"), Right(false))

      val drawBody = TestHelpers.makeEnvelope("drawDecision", "White", "invalid-dfen", drawOfferPending = true)
      val drawRes  = postSigned(client, url, drawBody)
      assertEquals(drawRes.statusCode(), 200)
      assertEquals(parse(drawRes.body()).toOption.get.hcursor.get[Boolean]("acceptDraw"), Right(false))

      val oppBody = TestHelpers.makeEnvelope(
        "doubleOpportunity",
        "White",
        "invalid-dfen",
        doublingState = Some(TestHelpers.doublingJson("offer", "White"))
      )
      val oppRes = postSigned(client, url, oppBody)
      assertEquals(oppRes.statusCode(), 200)
      assertEquals(parse(oppRes.body()).toOption.get.hcursor.get[Boolean]("offerDouble"), Right(false))

      val decBody = TestHelpers.makeEnvelope(
        "doubleDecision",
        "Black",
        "invalid-dfen",
        activeSeat = "Black",
        doublingState =
          Some(TestHelpers.doublingJson("response", "Black", offeredBy = Some("White"), mayOfferDouble = false))
      )
      val decRes = postSigned(client, url, decBody)
      assertEquals(decRes.statusCode(), 200)
      assertEquals(parse(decRes.body()).toOption.get.hcursor.get[Boolean]("acceptDouble"), Right(false))
    }

  test("end to end over real HTTP: malformed envelope returns 400"):
    withServer(createSyntheticStrategy()) { (client, url) =>
      val badJson = postRaw(client, url, "not-json")
      assertEquals(badJson.statusCode(), 400)

      val unknownType = postSigned(client, url, """{"type":"unknownKind","gameId":"g1"}""")
      assertEquals(unknownType.statusCode(), 400)
    }
