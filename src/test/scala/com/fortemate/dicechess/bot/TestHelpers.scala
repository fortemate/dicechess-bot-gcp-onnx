package com.fortemate.dicechess.bot

import com.fortemate.dicechess.runtime.{DoublingDecision, DoublingState}
import dicechess.engine.domain.GameState
import dicechess.engine.search.{ScoredSequence, SearchAlgorithm}

object TestHelpers:

  final class ConfigurableSearch(
      val offerDraw: Boolean = false,
      val acceptDraw: Boolean = false,
      val offerDouble: Boolean = false,
      val acceptDouble: Boolean = false
  ) extends SearchAlgorithm:
    override def findBestMove(state: GameState): Option[ScoredSequence]   = None
    override def shouldOfferDraw(state: GameState): Boolean               = offerDraw
    override def shouldAcceptDraw(state: GameState): Boolean              = acceptDraw
    override def shouldOfferDouble(state: GameState, mult: Int): Boolean  = offerDouble
    override def shouldAcceptDouble(state: GameState, mult: Int): Boolean = acceptDouble

  def doublingState(
      currentStake: Long = 200L,
      cubeValue: Int = 2,
      cubeOwner: String = "White",
      mayOfferDouble: Boolean = true,
      decision: DoublingDecision = new DoublingDecision.Offer("double_1", "White", 200L)
  ): DoublingState =
    new DoublingState(
      DoublingState.CURRENCY_PLAY_CREDIT,
      100L,
      currentStake,
      cubeValue,
      cubeOwner,
      64,
      mayOfferDouble,
      "White",
      decision
    )

  def doublingJson(
      kind: String,
      seat: String,
      offeredBy: Option[String] = None,
      cubeOwner: Option[String] = None,
      mayOfferDouble: Boolean = true
  ): String =
    val ob = offeredBy.map(o => s""", "offeredBy": "$o"""").getOrElse("")
    val co = cubeOwner.map(c => s""""$c"""").getOrElse("null")
    s"""{"currency":"PLAY_CREDIT","initialStake":100,"currentStake":100,"cubeValue":1,"cubeOwner":$co,"maximumMultiplier":64,"mayOfferDouble":$mayOfferDouble,"turnSeat":"White","decision":{"id":"double_1","kind":"$kind","seat":"$seat"$ob,"proposedStake":200}}"""

  def makeEnvelope(
      eventType: String,
      seat: String,
      dfen: String,
      activeSeat: String = "White",
      dicePending: Boolean = false,
      mayOfferDraw: Boolean = false,
      drawOfferPending: Boolean = false,
      doublingState: Option[String] = None,
      clocks: Option[String] = None
  ): String =
    val stateProps = new StringBuilder(
      s""""version":1,"dfen":"$dfen","activeSeat":"$activeSeat","dicePending":$dicePending"""
    )
    if mayOfferDraw then stateProps.append(""","mayOfferDraw":true""")
    if drawOfferPending then stateProps.append(""","drawOffer":{"pending":true}""")
    doublingState.foreach(ds => stateProps.append(s""","doubling":$ds"""))
    clocks.foreach(c => stateProps.append(s""","clocks":$c"""))
    s"""{"type":"$eventType","gameId":"g1","seat":"$seat","state":{${stateProps.toString()}}}"""
