/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LiveConnection
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.models.SingleCollectorLiveConnection
import com.google.adk.kt.processors.LlmRequestProcessor
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.telemetry.Span
import com.google.adk.kt.telemetry.SpanBuilder
import com.google.adk.kt.telemetry.Telemetry
import com.google.adk.kt.telemetry.TelemetryAttributes
import com.google.adk.kt.telemetry.TelemetryConfig
import com.google.adk.kt.telemetry.Tracer
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.DummyTracer
import com.google.adk.kt.testing.TRANSFER_TO_AGENT_RESPONSE_PART
import com.google.adk.kt.testing.connectionDrop
import com.google.adk.kt.testing.goAway
import com.google.adk.kt.testing.handleUpdate
import com.google.adk.kt.testing.inputTranscription
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.outputTranscription
import com.google.adk.kt.testing.simplifyEvents
import com.google.adk.kt.testing.transferToAgentCallPart
import com.google.adk.kt.testing.turnComplete
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.TransferToAgentTool.Companion.TRANSFER_TO_AGENT_TOOL_NAME
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.InteractionStatus
import com.google.adk.kt.types.LiveServerGoAway
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.types.Transcription
import com.google.adk.kt.types.TurnCompleteReason
import com.google.adk.kt.types.UsageMetadata
import com.google.adk.kt.types.VoiceActivity
import com.google.adk.kt.types.VoiceActivityType
import com.google.genai.kotlin.GenAiApiException
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LlmAgentTurnTest {

  /**
   * Root `MissionControl` transfers to sub-agent `HeartOfGold`, which calls a tool and then
   * answers. Asserts the exact 5-event sequence (transfer call → transfer response → tool call →
   * tool response → final text) and that the tool runs exactly once. Regression guard for the
   * `tracedFlow` fix: the old `channelFlow + send` design could let the sub-agent's second turn see
   * stale conversation history and re-emit the tool call.
   */
  @Test
  fun runAsync_rootAgentDelegatesToSubAgentThatInvokesTool_emitsOrderedEventSequence() = runTest {
    var toolCallCount = 0
    val returnRandomNumberTool =
      DummyTool(
        name = "return_random_number",
        description = "Returns a random number.",
        onRun = { _, _ ->
          toolCallCount++
          mapOf("number" to 42)
        },
      )
    // Tool call on first turn; final text once a function response is in the conversation.
    val subModel =
      DummyModel("sub-model") { request ->
        flow {
          val lastContent = request.contents.lastOrNull()
          val isFunctionResponse = lastContent?.parts?.any { it.functionResponse != null } == true
          if (!isFunctionResponse) {
            emit(
              modelFunctionCallResponse(
                name = "return_random_number",
                args = emptyMap(),
                id = "call_1",
              )
            )
          } else {
            emit(LlmResponse(content = modelMessage("The answer is 42.")))
          }
        }
      }
    // Root agent model: unconditionally delegates to the HeartOfGold sub-agent.
    val rootModel =
      DummyModel("root-model") {
        flow { emit(modelTransferToAgentResponse("HeartOfGold", id = "transfer_call_1")) }
      }
    val heartOfGoldAgent =
      LlmAgent(
        name = "HeartOfGold",
        description = "Sub-agent that knows about random numbers.",
        model = subModel,
        tools = listOf(returnRandomNumberTool),
      )
    val missionControlAgent =
      LlmAgent(
        name = "MissionControl",
        description = "Root agent that transfers to HeartOfGold.",
        subAgents = listOf(heartOfGoldAgent),
        model = rootModel,
      )
    val runner = InMemoryRunner(agent = missionControlAgent)
    val userMessage = userMessage("What's the answer?")

    val flowEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage)
        .toList()
        .filter { it.author != Role.USER }

    assertEquals("tool should have been invoked exactly once", 1, toolCallCount)
    assertEquals(
      listOf(
        "MissionControl" to transferToAgentCallPart("HeartOfGold"),
        "MissionControl" to TRANSFER_TO_AGENT_RESPONSE_PART,
        "HeartOfGold" to Part(functionCall = FunctionCall("return_random_number")),
        "HeartOfGold" to
          Part(
            functionResponse =
              FunctionResponse("return_random_number", response = mapOf("number" to 42))
          ),
        "HeartOfGold" to "The answer is 42.",
      ),
      simplifyEvents(flowEvents),
    )
  }

  /**
   * `errorCode` and `customMetadata` exist on both [LlmResponse] and `Event`, so finalizing the
   * model-response event must carry them over.
   */
  @Test
  fun runAsync_withErrorCodeAndCustomMetadata_propagatesToFinalEvent() = runBlocking {
    val model =
      DummyModel("metadata-model") {
        flow {
          emit(
            LlmResponse(
              content = modelMessage("Answered."),
              errorCode = "SAFETY",
              customMetadata = mapOf("trace_id" to "abc123", "attempt" to 2),
            )
          )
        }
      }
    val agent = LlmAgent(name = "MetadataAgent", description = "Echoes metadata.", model = model)
    val runner = InMemoryRunner(agent = agent)

    val modelEvent =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("Hello?"))
        .toList()
        .single { it.author == agent.name }

    assertEquals("SAFETY", modelEvent.errorCode)
    assertEquals(mapOf("trace_id" to "abc123", "attempt" to 2), modelEvent.customMetadata)
  }

  @Test
  fun runAsync_responseCarryingOnlyGroundingMetadata_stillBecomesAnEvent() = runBlocking {
    // ADK Python and Java keep a grounding-only response as an event too.
    val grounding = GroundingMetadata(webSearchQueries = listOf("kotlin adk"))
    val model =
      DummyModel("grounding-model") {
        flow {
          emit(LlmResponse(groundingMetadata = grounding))
          emit(LlmResponse(content = modelMessage("Grounded.")))
        }
      }
    val agent = LlmAgent(name = "Searcher", description = "Searches.", model = model)
    val runner = InMemoryRunner(agent = agent)

    val modelEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("Hi?"))
        .toList()
        .filter { it.author == agent.name }

    assertEquals(grounding, modelEvents.first().groundingMetadata)
    val key = SessionKey(runner.appName, "user1", "session1")
    val saved = checkNotNull(runner.sessionService.getSession(key)).events
    assertTrue(saved.any { it.groundingMetadata == grounding })
  }

  @Test
  fun runAsync_responseCarryingOnlyErrorCode_stillBecomesAnEvent() = runBlocking {
    // ADK Python and Java keep an error-code-only response as an event too.
    val model =
      DummyModel("error-model") {
        flow {
          emit(LlmResponse(errorCode = "E1"))
          emit(LlmResponse(content = modelMessage("Done.")))
        }
      }
    val agent = LlmAgent(name = "Erring", description = "Errs.", model = model)
    val runner = InMemoryRunner(agent = agent)

    val modelEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("Hi?"))
        .toList()
        .filter { it.author == agent.name }

    assertEquals("E1", modelEvents.first().errorCode)
    val key = SessionKey(runner.appName, "user1", "session1")
    val saved = checkNotNull(runner.sessionService.getSession(key)).events
    assertTrue(saved.any { it.errorCode == "E1" })
  }

  @Test
  fun runAsync_responseCarryingOnlyAnEmptyErrorCode_isDropped() = runBlocking {
    // ADK Python drops it too: an empty error code is falsy there.
    val model =
      DummyModel("error-model") {
        flow {
          emit(LlmResponse(errorCode = ""))
          emit(LlmResponse(content = modelMessage("Done.")))
        }
      }
    val agent = LlmAgent(name = "Erring", description = "Errs.", model = model)
    val runner = InMemoryRunner(agent = agent)

    val modelEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("Hi?"))
        .toList()
        .filter { it.author == agent.name }

    assertEquals(modelMessage("Done."), modelEvents.single().content)
  }

  @Test
  fun runAsync_responseCarryingOnlyALiveSignal_isDropped() = runBlocking {
    // ADK Python's turn-based check keeps none of these live-signal-only chunks.
    val model =
      DummyModel("transcribing-model") {
        flow {
          emit(outputTranscription("hi", finished = true))
          emit(inputTranscription("hey", finished = true))
          emit(
            LlmResponse(
              voiceActivity = VoiceActivity(voiceActivityType = VoiceActivityType.ACTIVITY_START)
            )
          )
          emit(handleUpdate("h"))
          emit(turnComplete())
          emit(LlmResponse(content = modelMessage("Hello.")))
        }
      }
    val agent = LlmAgent(name = "Scribe", description = "Transcribes.", model = model)
    val runner = InMemoryRunner(agent = agent)

    val modelEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("Hi?"))
        .toList()
        .filter { it.author == agent.name }

    assertEquals(listOf(modelMessage("Hello.")), modelEvents.map { it.content })
    assertTrue(
      modelEvents.none {
        it.outputTranscription != null ||
          it.inputTranscription != null ||
          it.voiceActivity != null ||
          it.liveSessionResumptionUpdate != null ||
          it.turnComplete
      }
    )
  }

  @Test
  fun runAsync_usageMetadataOnlyResponse_isDropped() = runBlocking {
    // A streamed turn-based reply can end with a usage-only chunk, which must not add an event.
    val model =
      DummyModel("usage-model") {
        flow {
          emit(LlmResponse(usageMetadata = UsageMetadata(totalTokenCount = 7)))
          emit(LlmResponse(content = modelMessage("Done.")))
        }
      }
    val agent = LlmAgent(name = "Counter", description = "Counts.", model = model)
    val runner = InMemoryRunner(agent = agent)

    val modelEvents =
      runner
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage("Hi?"))
        .toList()
        .filter { it.author == agent.name }

    assertEquals(emptyList<UsageMetadata>(), modelEvents.mapNotNull { it.usageMetadata })
  }

  @Test
  fun runLive_toolCall_answersTheModelThroughTheQueue() = runBlocking {
    val connection = RecordingLiveConnection(toolCallTurn())
    val agent = toolAgent(connection)

    // The queue stays open: the run ends when the connection does.
    val events = agent.runLive(liveContextFor(agent)).toList()

    val answered = connection.sentContent.flatMap { it.parts }.mapNotNull { it.functionResponse }
    assertEquals(listOf("my_function"), answered.map { it.name })
    assertEquals(listOf("c1"), answered.map { it.id })
    assertEquals(
      listOf("my_function"),
      events.flatMap { it.functionResponses() }.mapNotNull { it.name },
    )
  }

  @Test
  fun runLive_toolRunsOnlyAfterTheCallerHasHandledTheCallEvent() = runBlocking {
    var callHandled = false
    var handledWhenToolRan: Boolean? = null
    val tool =
      DummyTool(
        "my_function",
        onRun = { _, _ ->
          handledWhenToolRan = callHandled
          mapOf("response" to "done")
        },
      )
    val agent =
      LlmAgent(
        name = "LiveAgent",
        model = RecordingLiveModel(RecordingLiveConnection(toolCallTurn())),
        tools = listOf(tool),
      )

    // Stores the call slowly, as a runner writing to a remote session would.
    agent.runLive(liveContextFor(agent)).collect { event ->
      if (event.functionCalls().isNotEmpty()) {
        delay(100)
        callHandled = true
      }
    }

    assertEquals(true, handledWhenToolRan)
  }

  @Test
  fun runLive_toolAnswerAfterTheCallerClosed_isDroppedWithoutFailingTheRun() = runBlocking {
    // The call arrives only once the connection is closed, which rejects any later write.
    val connection = RecordingLiveConnection(toolCallTurn(), servesAfterClose = true)
    val agent = toolAgent(connection)
    val queue = LiveRequestQueue()
    queue.close()

    val events = agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(emptyList<Content>(), connection.sentContent)
    assertEquals(
      listOf("my_function"),
      events.flatMap { it.functionResponses() }.mapNotNull { it.name },
    )
  }

  @Test
  fun runLive_toolRequiringConfirmation_answersWithTheGateErrorAndRequestsNone() = runBlocking {
    var executions = 0
    val gated =
      object : FunctionTool("my_function", "Needs approval.", requiresConfirmation = true) {
        override fun declaration() = null

        override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
          executions++
          return mapOf("response" to "done")
        }
      }
    val agent =
      LlmAgent(
        name = "LiveAgent",
        model = RecordingLiveModel(RecordingLiveConnection(toolCallTurn())),
        tools = listOf(gated),
      )

    val events = agent.runLive(liveContextFor(agent)).toList()

    // As in ADK Python, a live run can't take an approval, so it asks for none.
    assertTrue(
      events
        .flatMap { it.functionCalls() }
        .none { it.name == FunctionCall.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME }
    )
    assertEquals(0, executions)
    assertEquals(
      "This tool call requires confirmation, please approve or reject.",
      events.flatMap { it.functionResponses() }.single().response["error"],
    )
  }

  @Test
  fun runLive_toolResponse_carriesTheCallsLiveSessionId() = runBlocking {
    val connection = RecordingLiveConnection(toolCallTurn(liveSessionId = "live-1"))
    val agent = toolAgent(connection)

    val events = agent.runLive(liveContextFor(agent)).toList()

    assertEquals("live-1", events.single { it.functionResponses().isNotEmpty() }.liveSessionId)
  }

  @Test
  fun runLive_usageMetadataOnlyResponse_becomesAnEvent() = runBlocking {
    // Only a live run keeps a usage-only response; the turn-based test above checks the other case.
    val usage = UsageMetadata(totalTokenCount = 7)
    val connection =
      RecordingLiveConnection(listOf(LlmResponse(usageMetadata = usage), turnComplete()))
    val agent = liveAgent(connection)

    val events = agent.runLive(liveContextFor(agent)).toList()

    assertEquals(listOf(usage), events.mapNotNull { it.usageMetadata })
  }

  @Test
  fun runLive_outputTranscriptionOnlyResponse_becomesAnEvent() = runBlocking {
    // The live counterpart of the turn-based drop test above: a live run keeps the signal.
    val spoken = Transcription(text = "hello there", finished = true)
    val connection =
      RecordingLiveConnection(listOf(LlmResponse(outputTranscription = spoken), turnComplete()))
    val agent = liveAgent(connection)

    val events = agent.runLive(liveContextFor(agent)).toList()

    assertEquals(listOf(spoken), events.mapNotNull { it.outputTranscription })
  }

  @Test
  fun runLive_voiceActivityAndResumptionUpdate_becomeEvents() = runBlocking {
    val activity = VoiceActivity(voiceActivityType = VoiceActivityType.ACTIVITY_START)
    val update = LiveServerSessionResumptionUpdate(newHandle = "h-1", resumable = true)
    val connection =
      RecordingLiveConnection(
        listOf(
          LlmResponse(voiceActivity = activity),
          LlmResponse(liveSessionResumptionUpdate = update),
          turnComplete(),
        )
      )
    val agent = liveAgent(connection)
    // The stored handle makes a clean close reconnect now, so the caller closes to end the run.
    val queue = LiveRequestQueue()
    queue.close()

    val events = agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(listOf(activity), events.mapNotNull { it.voiceActivity })
    assertEquals(listOf(update), events.mapNotNull { it.liveSessionResumptionUpdate })
  }

  @Test
  fun runLive_inputTranscription_isAttributedToTheUser() = runBlocking {
    // Transcribed input is the user's words; the agent as author would misattribute them.
    val heard = Transcription(text = "is the room free", finished = false)
    val connection =
      RecordingLiveConnection(listOf(LlmResponse(inputTranscription = heard), turnComplete()))
    val agent = liveAgent(connection)

    val events = agent.runLive(liveContextFor(agent)).toList()

    assertEquals(Role.USER, events.single { it.inputTranscription != null }.author)
  }

  @Test
  fun runLive_serverContentMarkedUser_isAttributedToTheUser() = runBlocking {
    // User-role content is the user's too; the transcription test above does not cover it.
    val spoken = Content(Role.USER, listOf(Part(text = "book it")))
    val connection = RecordingLiveConnection(listOf(LlmResponse(content = spoken), turnComplete()))
    val agent = liveAgent(connection)

    val events = agent.runLive(liveContextFor(agent)).toList()

    assertEquals(Role.USER, events.single { it.content == spoken }.author)
  }

  @Test
  fun runLive_sessionHistory_seedsTheConnection() = runBlocking {
    val connection = RecordingLiveConnection(listOf(turnComplete()))
    val agent = liveAgent(connection)

    agent.runLive(liveContextFor(agent, session = sessionWithEarlierMessage())).toList()

    assertEquals(
      listOf("earlier"),
      connection.sentHistory.flatMap { it.parts }.mapNotNull { it.text },
    )
  }

  @Test
  fun runLive_callersResumptionHandle_skipsTheHistory() = runBlocking {
    // The server already holds a resumed session's history, as ADK Python assumes.
    val connection = RecordingLiveConnection(listOf(turnComplete()))
    val agent = liveAgent(connection)
    val runConfig = RunConfig(sessionResumption = SessionResumptionConfig(handle = "h-1"))
    // The caller handle makes a clean close reconnect now, so the caller closes to end the run.
    val queue = LiveRequestQueue()
    queue.close()

    agent
      .runLive(
        liveContextFor(agent, queue, session = sessionWithEarlierMessage(), runConfig = runConfig)
      )
      .toList()

    assertEquals(0, connection.sendHistoryCalls)
  }

  @Test
  fun runLive_emptyHistory_doesNotSeedTheConnection() = runBlocking {
    val connection = RecordingLiveConnection(listOf(turnComplete()))
    val agent = liveAgent(connection)

    agent.runLive(liveContextFor(agent)).toList()

    assertEquals(0, connection.sendHistoryCalls)
  }

  @Test
  fun runLive_seedingHistory_recordsASendDataSpan() = runBlocking {
    val tracer = DummyTracer()
    Telemetry.setTracerForTest(tracer)
    TelemetryConfig.captureMessageContent = false
    try {
      val connection = RecordingLiveConnection(listOf(turnComplete()))
      val agent = liveAgent(connection)
      val context = liveContextFor(agent, session = sessionWithEarlierMessage())

      agent.runLive(context).toList()

      val span = tracer.recordedSpans.single { it.name == "send_data" }
      assertEquals(
        context.invocationId,
        span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_INVOCATION_ID],
      )
      val eventId = span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID]
      assertNotNull(eventId)
      assertNotEquals(context.invocationId, eventId)
      // Content capture is off, so the span carries the placeholder instead of the history.
      assertEquals("{}", span.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_DATA])
    } finally {
      Telemetry.resetTracer()
    }
  }

  @Test
  fun runLive_seedingHistory_sendsTheHistoryInsideTheSendDataSpan() = runBlocking {
    val connection = RecordingLiveConnection(listOf(turnComplete()))
    Telemetry.setTracerForTest(SpanLoggingTracer(connection.log))
    try {
      val agent = liveAgent(connection)

      agent.runLive(liveContextFor(agent, session = sessionWithEarlierMessage())).toList()

      assertEquals(
        listOf("span start: send_data", "history:earlier", "span end: send_data"),
        connection.log.filter { "send_data" in it || it.startsWith("history:") },
      )
    } finally {
      Telemetry.resetTracer()
    }
  }

  @Test
  fun runLive_emptyHistory_recordsNoSendDataSpan() = runBlocking {
    val tracer = DummyTracer()
    Telemetry.setTracerForTest(tracer)
    try {
      val connection = RecordingLiveConnection(listOf(turnComplete()))
      val agent = liveAgent(connection)

      agent.runLive(liveContextFor(agent)).toList()

      assertTrue(tracer.recordedSpans.none { it.name == "send_data" })
    } finally {
      Telemetry.resetTracer()
    }
  }

  @Test
  fun runLive_twoRuns_recordDistinctSendDataEventIds() = runBlocking {
    val tracer = DummyTracer()
    Telemetry.setTracerForTest(tracer)
    try {
      repeat(2) {
        val connection = RecordingLiveConnection(listOf(turnComplete()))
        val agent = liveAgent(connection)
        agent.runLive(liveContextFor(agent, session = sessionWithEarlierMessage())).toList()
      }

      val eventIds =
        tracer.recordedSpans
          .filter { it.name == "send_data" }
          .map { it.attributes[TelemetryAttributes.GCP_VERTEX_AGENT_EVENT_ID] }
      assertEquals(2, eventIds.toSet().size)
    } finally {
      Telemetry.resetTracer()
    }
  }

  @Test
  fun runLive_sendDataSpan_summarizesInlineMediaAndLeavesOutThoughtSignatures() = runBlocking {
    val tracer = DummyTracer()
    Telemetry.setTracerForTest(tracer)
    TelemetryConfig.captureMessageContent = true
    try {
      val connection = RecordingLiveConnection(listOf(turnComplete()))
      val agent = liveAgent(connection)
      val history =
        userMessage(
          Part(inlineData = Blob(mimeType = "audio/pcm", data = ByteArray(64))),
          Part(inlineData = Blob()),
          Part(inlineData = Blob(mimeType = "", data = ByteArray(8))),
          Part(text = "signed", thoughtSignature = ByteArray(32)),
        )

      agent.runLive(liveContextFor(agent, session = sessionWithEarlierMessage(history))).toList()

      val data =
        tracer.recordedSpans
          .single { it.name == "send_data" }
          .attributes[TelemetryAttributes.GCP_VERTEX_AGENT_DATA]
          .toString()
      assertTrue("<inline_data: audio/pcm, 64 bytes>" in data)
      assertTrue("<inline_data: unknown, 0 bytes>" in data)
      assertTrue("<inline_data: unknown, 8 bytes>" in data)
      assertTrue("inlineData" !in data)
      assertTrue("signed" in data)
      assertTrue("thoughtSignature" !in data)
    } finally {
      TelemetryConfig.captureMessageContent = false
      Telemetry.resetTracer()
    }
  }

  @Test
  fun runLive_callersResumptionHandle_recordsNoSendDataSpan() = runBlocking {
    // A resumed session's history is already on the server, so nothing is seeded and no span opens.
    val tracer = DummyTracer()
    Telemetry.setTracerForTest(tracer)
    try {
      val connection = RecordingLiveConnection(listOf(turnComplete()))
      val agent = liveAgent(connection)
      val runConfig = RunConfig(sessionResumption = SessionResumptionConfig(handle = "h-1"))
      // The caller handle makes a clean close reconnect now, so the caller closes to end the run.
      val queue = LiveRequestQueue()
      queue.close()

      agent
        .runLive(
          liveContextFor(agent, queue, session = sessionWithEarlierMessage(), runConfig = runConfig)
        )
        .toList()

      assertTrue(tracer.recordedSpans.none { it.name == "send_data" })
    } finally {
      Telemetry.resetTracer()
    }
  }

  @Test
  fun runLive_queuedContentAndRealtime_reachTheConnectionAfterTheHistory() = runBlocking {
    val connection = RecordingLiveConnection(untilClosed = true)
    val agent = liveAgent(connection)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("now"), partial = true)
    queue.sendActivityEnd()
    queue.close()

    agent.runLive(liveContextFor(agent, queue, session = sessionWithEarlierMessage())).toList()

    assertEquals(
      listOf("history:earlier", "content:now partial=true", "realtime", "close"),
      connection.log.distinct(),
    )
  }

  @Test
  fun runLive_contentWithoutARole_isSentAsTheUsers() = runBlocking {
    val connection = RecordingLiveConnection(untilClosed = true)
    val agent = liveAgent(connection)
    val queue = LiveRequestQueue()
    queue.sendContent(Content(parts = listOf(Part(text = "hi"))))
    queue.close()

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(Role.USER, connection.sentContent.single().role)
  }

  @Test
  fun runLive_toolAnswerFromTheCaller_isSentWithoutARoleOrScreening() = runBlocking {
    var screened = 0
    val connection = RecordingLiveConnection(untilClosed = true)
    val agent =
      liveAgent(
        connection,
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              screened++
              CallbackChoice.Continue(request)
            }
          ),
      )
    val answer = FunctionResponse("my_function", response = mapOf("ok" to true), id = "c1")
    val queue = LiveRequestQueue()
    queue.sendContent(Content(parts = listOf(Part(functionResponse = answer))))
    queue.close()

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertNull(connection.sentContent.single().role)
    assertEquals(0, screened)
  }

  @Test
  fun runLive_secondTurn_isReadOnTheSameConnection() = runBlocking {
    val connection =
      RecordingLiveConnection(
        listOf(LlmResponse(content = modelMessage("first")), turnComplete()),
        listOf(LlmResponse(content = modelMessage("second")), turnComplete()),
      )
    val agent = liveAgent(connection)

    val events = agent.runLive(liveContextFor(agent)).toList()

    assertEquals(listOf("first", "second"), events.mapNotNull { it.content?.parts?.single()?.text })
  }

  @Test
  fun runLive_callerClosesTheQueue_endsTheRunAndClosesTheConnection() = runBlocking {
    val connection = RecordingLiveConnection(untilClosed = true)
    val agent = liveAgent(connection)
    val queue = LiveRequestQueue()
    queue.close()

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertTrue(connection.closeCalls > 0)
  }

  @Test
  fun runLive_callerCancels_closesTheConnection() = runBlocking {
    val connection = RecordingLiveConnection(untilClosed = true)
    val model = RecordingLiveModel(connection)
    val agent = LlmAgent(name = "LiveAgent", model = model)
    val context = liveContextFor(agent)

    val job = launch { agent.runLive(context).toList() }
    while (model.connectCalls == 0) yield()
    job.cancelAndJoin()

    assertEquals(1, connection.closeCalls)
  }

  @Test
  fun runLive_callerClosesAndTheCloseFails_stillEndsTheRunNormally() = runBlocking {
    val connection = RecordingLiveConnection(untilClosed = true, failsClose = true)
    val agent = liveAgent(connection)
    val queue = LiveRequestQueue()
    queue.close()

    val events = agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(emptyList<Event>(), events)
  }

  @Test
  fun runLive_teardownFails_keepsTheCallersExceptionNotTheTeardownOne(): Unit = runBlocking {
    val connection = RecordingLiveConnection(failsTurn = true, failsClose = true)
    val agent = liveAgent(connection)

    val error =
      assertFailsWith<ScriptedTurnFailure> { agent.runLive(liveContextFor(agent)).toList() }

    assertEquals("the turn failed", error.message)
  }

  @Test
  fun runLive_closeThatNeverReturns_endsTheRunAfterTheTeardownTimeout() = runTest {
    // runTest's virtual time skips the 10-second close bound; the connection ends at once.
    val connection = RecordingLiveConnection(hangsClose = true)
    val agent = liveAgent(connection)

    val events = agent.runLive(liveContextFor(agent)).toList()

    assertEquals(emptyList<Event>(), events)
    assertEquals(1, connection.closeCalls)
    assertTrue(testScheduler.currentTime >= 10_000)
  }

  @Test
  fun runLive_beforeModelCallbackReturningZeroOrSeveralContents_sendsTheOriginal() = runBlocking {
    val connection = RecordingLiveConnection(untilClosed = true)
    val agent =
      liveAgent(
        connection,
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              val original = request.contents.single()
              val rewritten =
                if (original.parts.single().text == "none") emptyList()
                else listOf(original, original)
              CallbackChoice.Continue(request.copy(contents = rewritten))
            }
          ),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("none"))
    queue.sendContent(userMessage("two"))
    queue.close()

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(listOf("none", "two"), connection.sentTexts())
  }

  @Test
  fun runLive_beforeModelRewriteBreakingTheContentRules_failsNamingTheCallback() = runBlocking {
    val agent =
      liveAgent(
        RecordingLiveConnection(untilClosed = true),
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              CallbackChoice.Continue(
                request.copy(contents = listOf(Content(Role.USER, emptyList())))
              )
            }
          ),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hi"))
    queue.close()

    val error =
      assertFailsWith<IllegalArgumentException> {
        agent.runLive(liveContextFor(agent, queue)).toList()
      }

    assertTrue("before-model callback" in error.message.orEmpty())
  }

  @Test
  fun runLive_restartWhileAStateDeltaIsSaving_stillSavesIt() = runBlocking {
    val inner = InMemorySessionService()
    val slowSessionService =
      object : SessionService by inner {
        override suspend fun appendEvent(session: Session, event: Event): Event {
          delay(100)
          return inner.appendEvent(session, event)
        }
      }
    val key = SessionKey("app", "user", "slow-delta")
    val queue = LiveRequestQueue()
    queue.send(LiveRequest(stateDelta = mapOf("k" to "v")))
    val model =
      RecordingLiveModel(secretTurn(), RecordingLiveConnection(untilClosed = true)) { connects ->
        if (connects == 2) queue.close()
      }
    val agent =
      LlmAgent(name = "LiveAgent", model = model, afterModelCallbacks = listOf(blockSecret()))

    // The block restarts the session while the sender is still saving the delta.
    agent
      .runLive(
        liveContextFor(
          agent,
          queue,
          session = inner.createSession(key),
          sessionService = slowSessionService,
        )
      )
      .toList()

    assertEquals("v", checkNotNull(inner.getSession(key)).state["k"])
  }

  @Test
  fun runLive_callerClosesAndCloseNeverReturns_closesOnceWithinTheBound() = runTest {
    // runTest's virtual time skips the 10-second close bound.
    val connection = RecordingLiveConnection(untilClosed = true, hangsClose = true)
    val agent = liveAgent(connection)
    val queue = LiveRequestQueue()
    queue.close()

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(1, connection.closeCalls)
    assertTrue(testScheduler.currentTime < 20_000)
  }

  @Test
  fun runLive_callerClosesAndTheConnectionNeverEnds_endsAfterTheTeardownTimeout() = runTest {
    val connection =
      object : LiveConnection {
        override suspend fun sendHistory(history: List<Content>) {}

        override suspend fun sendContent(content: Content, partial: Boolean) {}

        override suspend fun sendRealtime(input: RealtimeInput) {}

        override fun receive(): Flow<LlmResponse> = flow { awaitCancellation() }

        override suspend fun closeSession() {
          awaitCancellation()
        }
      }
    val agent = liveAgent(connection)
    val queue = LiveRequestQueue()
    queue.close()

    // Bounded so a regression fails here instead of hanging the suite.
    withTimeout(60.seconds) { agent.runLive(liveContextFor(agent, queue)).toList() }

    assertTrue(testScheduler.currentTime < 20_000)
  }

  @Test
  fun runLive_contextWithoutALiveRequestQueue_failsBeforeConnecting(): Unit = runBlocking {
    val model = RecordingLiveModel(RecordingLiveConnection())
    val agent = LlmAgent(name = "LiveAgent", model = model)
    val context =
      InvocationContext(
        agent = agent,
        session = InMemorySessionService().createSession(SessionKey("app", "user", "no-queue")),
      )

    val error = assertFailsWith<IllegalStateException> { agent.runLive(context).toList() }

    assertEquals("a live run needs a live request queue on its context", error.message)
    assertEquals(0, model.connectCalls)
  }

  @Test
  fun executeLive_requestPreparationEndsTheInvocation_doesNotConnect() = runBlocking {
    val model = RecordingLiveModel(RecordingLiveConnection(listOf(turnComplete())))
    val agent = LlmAgent(name = "LiveAgent", model = model)
    val context = liveContextFor(agent)
    val ending =
      object : LlmRequestProcessor {
        override suspend fun process(
          context: InvocationContext,
          request: LlmRequest,
          emitEvent: suspend (Event) -> Unit,
        ): LlmRequest {
          context.isEndOfInvocation = true
          return request
        }
      }

    LlmAgentTurn(agent, context, listOf(ending), emptyList()).executeLive().toList()

    assertEquals(0, model.connectCalls)
  }

  @Test
  fun runLive_beforeModelCallback_screensEachTypedMessageOnItsOwn() = runBlocking {
    // Partial input is screened too; nothing is screened at connect time.
    val screened = mutableListOf<List<Content>>()
    val connection = RecordingLiveConnection(untilClosed = true)
    val agent =
      liveAgent(
        connection,
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              screened += request.contents
              CallbackChoice.Continue(request)
            }
          ),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("first"))
    queue.sendContent(userMessage("second"), partial = true)
    queue.close()

    agent.runLive(liveContextFor(agent, queue, session = sessionWithEarlierMessage())).toList()

    assertEquals(listOf(listOf(userMessage("first")), listOf(userMessage("second"))), screened)
    assertEquals(listOf("first", "second"), connection.sentTexts())
  }

  @Test
  fun runLive_beforeModelCallbackBlocksATypedMessage_skipsItsSendAndCompletesTheTurn() =
    runBlocking {
      val refusal = modelMessage("not allowed")
      val connection = RecordingLiveConnection(untilClosed = true)
      val agent =
        liveAgent(connection, beforeModelCallbacks = listOf(blockText("blocked", refusal)))
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("blocked"))
      queue.sendContent(userMessage("fine"))
      queue.close()

      val events = agent.runLive(liveContextFor(agent, queue)).toList()

      assertEquals(listOf("fine"), connection.sentTexts())
      val blocked = events.single { it.content == refusal }
      assertEquals(agent.name, blocked.author)
      assertEquals(true, blocked.turnComplete)
      assertEquals("v", blocked.actions.stateDelta["k"])
    }

  @Test
  fun runLive_beforeModelCallbackBlocks_savesItsTextToOutputKey() = runBlocking {
    val connection = RecordingLiveConnection(untilClosed = true)
    val agent =
      liveAgent(
        connection,
        outputKey = "answer",
        beforeModelCallbacks = listOf(blockText("blocked", modelMessage("refused"))),
      )
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("blocked"))
    queue.close()

    val event = agent.runLive(liveContextFor(agent, queue)).toList().single()

    assertEquals("refused", event.actions.stateDelta["answer"])
  }

  @Test
  fun runLive_beforeModelCallbackRewritesATypedMessage_sendsTheRewrite() = runBlocking {
    val connection = RecordingLiveConnection(untilClosed = true)
    val agent = liveAgent(connection, beforeModelCallbacks = listOf(redactingCallback()))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("card 4111"))
    queue.close()

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(listOf("card ****"), connection.sentTexts())
  }

  @Test
  fun runLive_beforeModelCallbackStateWriteOnTypedInput_reachesTheSession() = runBlocking {
    val connection = RecordingLiveConnection(untilClosed = true)
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "callback-state")
    val agent =
      liveAgent(connection, beforeModelCallbacks = listOf(writeStateAndContinue("k", "v")))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hi"))
    queue.close()

    agent
      .runLive(
        liveContextFor(
          agent,
          queue,
          session = sessionService.createSession(key),
          sessionService = sessionService,
        )
      )
      .toList()

    val stored = checkNotNull(sessionService.getSession(key))
    assertEquals("v", stored.state["k"])
    // The session holds the user turn plus the callback's state event.
    assertEquals(2, stored.events.size)
    assertTrue(stored.events.any { it.author == Role.USER })
    assertTrue(stored.events.any { it.author == agent.name })
    assertEquals(listOf("hi"), connection.sentTexts())
  }

  @Test
  fun runLive_afterModelCallbackStateWriteOnOutputTranscription_reachesTheSession() = runBlocking {
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "after-model-state")
    val agent =
      liveAgent(
        RecordingLiveConnection(
          listOf(
            LlmResponse(outputTranscription = Transcription(text = "hi"), partial = true),
            turnComplete(),
          )
        ),
        afterModelCallbacks =
          listOf(
            AfterModelCallback { callbackContext, response ->
              callbackContext.updateState("k", "v")
              response
            }
          ),
      )

    agent
      .runLive(
        liveContextFor(
          agent,
          session = sessionService.createSession(key),
          sessionService = sessionService,
        )
      )
      .toList()

    val stored = checkNotNull(sessionService.getSession(key))
    assertEquals("v", stored.state["k"])
    assertEquals(agent.name, stored.events.single().author)
  }

  @Test
  fun runLive_afterModelCallbackStateWriteOnANonPartialFrame_ridesOnItsEvent() = runBlocking {
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "after-model-final")
    val agent =
      liveAgent(
        RecordingLiveConnection(
          listOf(LlmResponse(outputTranscription = Transcription(text = "hi")), turnComplete())
        ),
        afterModelCallbacks =
          listOf(
            AfterModelCallback { callbackContext, response ->
              callbackContext.updateState("k", "v")
              response
            }
          ),
      )

    val events =
      agent
        .runLive(
          liveContextFor(
            agent,
            session = sessionService.createSession(key),
            sessionService = sessionService,
          )
        )
        .toList()

    // The caller saves this non-partial event itself, so the turn saves nothing of its own.
    assertEquals("v", events.first { it.outputTranscription != null }.actions.stateDelta["k"])
    assertEquals(emptyList<Event>(), checkNotNull(sessionService.getSession(key)).events)
  }

  @Test
  fun runLive_afterModelCallback_seesTheTurnsOutputTranscriptionSoFar() = runBlocking {
    // A finished chunk or a completed turn starts the next screening from nothing.
    val seen = mutableListOf<String?>()
    val connection =
      RecordingLiveConnection(
        listOf(
          LlmResponse(outputTranscription = Transcription(text = "Hel")),
          LlmResponse(outputTranscription = Transcription(text = "lo")),
          outputTranscription("Hello", finished = true),
          turnComplete(),
        ),
        listOf(LlmResponse(outputTranscription = Transcription(text = "Bye")), turnComplete()),
      )
    val agent =
      liveAgent(
        connection,
        afterModelCallbacks =
          listOf(
            AfterModelCallback { _, response ->
              seen += response.outputTranscription?.text
              response
            }
          ),
      )
    val model = agent.model as RecordingLiveModel

    agent.runLive(liveContextFor(agent)).toList()

    assertEquals(listOf("Hel", "Hello", "Bye"), seen)
    // Returning the screened response unchanged lets the output through without a restart.
    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_afterModelCallback_skipsResponsesWithoutOutputTranscription() = runBlocking {
    var calls = 0
    val connection =
      RecordingLiveConnection(listOf(LlmResponse(content = modelMessage("hi")), turnComplete()))
    val agent =
      liveAgent(
        connection,
        afterModelCallbacks =
          listOf(
            AfterModelCallback { _, response ->
              calls++
              response
            }
          ),
      )

    agent.runLive(liveContextFor(agent)).toList()

    assertEquals(0, calls)
  }

  @Test
  fun runLive_afterModelCallbackReplacesTheOutput_restartsWithoutTheResumptionHandle() =
    runBlocking {
      val redacted = modelMessage("redacted")
      val first =
        RecordingLiveConnection(
          listOf(
            LlmResponse(outputTranscription = Transcription(text = "secret")),
            LlmResponse(content = modelMessage("never seen")),
            turnComplete(),
          )
        )
      val second = RecordingLiveConnection(listOf(turnComplete()))
      val model = RecordingLiveModel(first, second)
      val agent =
        LlmAgent(
          name = "LiveAgent",
          model = model,
          afterModelCallbacks =
            listOf(
              AfterModelCallback { _, response ->
                if (response.outputTranscription?.text == "secret") LlmResponse(content = redacted)
                else response
              }
            ),
        )
      val runConfig =
        RunConfig(sessionResumption = SessionResumptionConfig(handle = "h-1", transparent = true))

      val events = agent.runLive(liveContextFor(agent, runConfig = runConfig)).toList()

      assertEquals(true, events.single { it.content == redacted }.turnComplete)
      assertTrue(events.none { it.content == modelMessage("never seen") })
      assertTrue(first.closeCalls > 0)
      assertEquals(
        listOf(
          SessionResumptionConfig(handle = "h-1", transparent = true),
          SessionResumptionConfig(transparent = true),
        ),
        model.connectedRequests.map { it.liveConnectConfig.sessionResumption },
      )
    }

  @Test
  fun runLive_beforeModelCallbackBlocksFinishedInputTranscription_emitsItThenRestarts() =
    runBlocking {
      val screened = mutableListOf<String?>()
      val refusal = modelMessage("not allowed")
      val first =
        RecordingLiveConnection(
          listOf(
            LlmResponse(inputTranscription = Transcription(text = "forbid")),
            inputTranscription("forbidden", finished = true),
            LlmResponse(content = modelMessage("never seen")),
            turnComplete(),
          )
        )
      val second = RecordingLiveConnection(listOf(turnComplete()))
      val model = RecordingLiveModel(first, second)
      val agent =
        LlmAgent(
          name = "LiveAgent",
          model = model,
          beforeModelCallbacks =
            listOf(
              BeforeModelCallback { _, request ->
                val text = request.contents.single().parts.single().text
                screened += text
                if (text == "forbidden") CallbackChoice.Break(LlmResponse(content = refusal))
                else CallbackChoice.Continue(request)
              }
            ),
        )

      val events = agent.runLive(liveContextFor(agent)).toList()

      assertEquals(listOf<String?>("forbidden"), screened)
      val heardAt = events.indexOfFirst { it.inputTranscription?.finished == true }
      val blockedAt = events.indexOfFirst { it.content == refusal }
      assertTrue(heardAt in 0 until blockedAt)
      assertEquals(true, events[blockedAt].turnComplete)
      assertTrue(events.none { it.content == modelMessage("never seen") })
      assertEquals(2, model.connectCalls)
    }

  @Test
  fun runLive_repeatedRestarts_doNotNestFlows() = runBlocking {
    val queue = LiveRequestQueue()
    val model =
      RecordingLiveModel(
        secretTurn(),
        secretTurn(),
        secretTurn(),
        RecordingLiveConnection(untilClosed = true),
      ) { connects ->
        if (connects == 4) queue.close()
      }
    val agent =
      LlmAgent(name = "LiveAgent", model = model, afterModelCallbacks = listOf(blockSecret()))
    val depths = mutableListOf<Int>()

    agent.runLive(liveContextFor(agent, queue)).collect { event ->
      if (event.content?.parts?.firstOrNull()?.text == "x") {
        depths += Throwable().stackTraceToString().lines().size
      }
    }

    // A nested restart would put more frames between each later session and this collector.
    assertEquals(4, model.connectCalls)
    assertEquals(3, depths.size)
    assertEquals(1, depths.distinct().size)
  }

  @Test
  fun runLive_repeatedGoAwaysWithNoHandle_doNotNestFlows() = runTest {
    val queue = LiveRequestQueue()
    // Each session speaks once, then a handle-less go-away, which reopens a fresh session.
    fun goAwayTurn() =
      RecordingLiveConnection(
        listOf(LlmResponse(content = modelMessage("x"), turnComplete = true), goAway())
      )
    val model =
      RecordingLiveModel(
        goAwayTurn(),
        goAwayTurn(),
        goAwayTurn(),
        RecordingLiveConnection(untilClosed = true),
      ) { connects ->
        if (connects == 4) queue.close()
      }
    val agent = LlmAgent(name = "LiveAgent", model = model)
    val depths = mutableListOf<Int>()

    agent.runLive(liveContextFor(agent, queue)).collect { event ->
      if (event.content?.parts?.firstOrNull()?.text == "x") {
        depths += Throwable().stackTraceToString().lines().size
      }
    }

    // A nested restart would put more frames between each later session and this collector.
    assertEquals(4, model.connectCalls)
    assertEquals(3, depths.size)
    assertEquals(1, depths.distinct().size)
  }

  @Test
  fun runLive_restartedTurnEndingTheInvocation_skipsAfterAgentCallbacks() = runBlocking {
    val queue = LiveRequestQueue()
    // The input arrives only after the first session ends, so only the restarted turn screens it.
    val model =
      RecordingLiveModel(secretTurn(), RecordingLiveConnection(untilClosed = true)) { connects ->
        if (connects == 2) {
          queue.sendContent(userMessage("bye"))
          queue.close()
        }
      }
    var afterAgentCalls = 0
    val agent =
      LlmAgent(
        name = "LiveAgent",
        model = model,
        afterModelCallbacks = listOf(blockSecret()),
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { callbackContext, request ->
              if (request.contents.single().parts.single().text == "bye") {
                callbackContext.endInvocation()
              }
              CallbackChoice.Continue(request)
            }
          ),
        afterAgentCallbacks =
          listOf(
            AfterAgentCallback { _ ->
              afterAgentCalls++
              CallbackChoice.Continue(Unit)
            }
          ),
      )

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(2, model.connectCalls)
    assertEquals(0, afterAgentCalls)
  }

  @Test
  fun runLive_restart_keepsReadingTheSameQueue() = runBlocking {
    val queue = LiveRequestQueue()
    val first =
      RecordingLiveConnection(
        listOf(LlmResponse(outputTranscription = Transcription(text = "secret")), turnComplete())
      )
    val second = RecordingLiveConnection(untilClosed = true)
    // The input arrives only after the first sender has stopped, so only the restart can read it.
    val model =
      RecordingLiveModel(first, second) { connects ->
        if (connects == 2) {
          queue.sendContent(userMessage("after restart"))
          queue.close()
        }
      }
    val agent =
      LlmAgent(
        name = "LiveAgent",
        model = model,
        afterModelCallbacks =
          listOf(
            AfterModelCallback { _, response ->
              if (response.outputTranscription?.text == "secret")
                LlmResponse(content = modelMessage("x"))
              else response
            }
          ),
      )

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(emptyList<String>(), first.sentTexts())
    assertEquals(listOf("after restart"), second.sentTexts())
  }

  @Test
  fun runLive_restartAfterABlockedTurn_replaysItEvenWhenTheCallerStoresItSlowly() = runBlocking {
    val first =
      RecordingLiveConnection(
        listOf(LlmResponse(outputTranscription = Transcription(text = "secret")), turnComplete())
      )
    val second = RecordingLiveConnection(listOf(turnComplete()))
    val model = RecordingLiveModel(first, second)
    val agent =
      LlmAgent(
        name = "LiveAgent",
        model = model,
        afterModelCallbacks =
          listOf(
            AfterModelCallback { _, response ->
              if (response.outputTranscription?.text == "secret") {
                LlmResponse(content = modelMessage("redacted"))
              } else {
                response
              }
            }
          ),
      )
    val sessionService = InMemorySessionService()
    val context =
      liveContextFor(
        agent,
        session = sessionService.createSession(SessionKey("app", "user", "slow")),
        sessionService = sessionService,
      )

    // Stores each final event after a delay, as a runner writing to a remote session would.
    agent.runLive(context).collect { event ->
      if (!event.partial) {
        delay(100)
        val unused = sessionService.appendEvent(context.session, event)
      }
    }

    assertEquals(2, model.connectCalls)
    val replayed = model.connectedRequests[1].contents.flatMap { it.parts }.mapNotNull { it.text }
    assertTrue("redacted" in replayed)
  }

  @Test
  fun runLive_afterModelCallbackBlocksByCopyingAPartialChunk_emitsAFinalTurnWithoutTheTranscript() =
    runBlocking {
      val redacted = modelMessage("redacted")
      val agent =
        LlmAgent(
          name = "LiveAgent",
          model = RecordingLiveModel(partialSecretTurn(), RecordingLiveConnection(listOf())),
          outputKey = "answer",
          afterModelCallbacks =
            listOf(
              AfterModelCallback { _, response ->
                if (response.outputTranscription?.text == "secret") {
                  response.copy(content = redacted)
                } else {
                  response
                }
              }
            ),
        )

      val events = agent.runLive(liveContextFor(agent)).toList()

      val blocked = events.single { it.content == redacted }
      assertEquals(true, blocked.turnComplete)
      assertEquals(false, blocked.partial)
      assertNull(blocked.outputTranscription)
      assertEquals("redacted", blocked.actions.stateDelta["answer"])
    }

  @Test
  fun runLive_afterModelCallbackReplacesTheTranscript_blockedTurnKeepsTheReplacement() =
    runBlocking {
      val removed = Transcription(text = "[removed]", finished = true)
      val agent =
        LlmAgent(
          name = "LiveAgent",
          model = RecordingLiveModel(partialSecretTurn(), RecordingLiveConnection(listOf())),
          afterModelCallbacks =
            listOf(
              AfterModelCallback { _, response ->
                if (response.outputTranscription?.text == "secret") {
                  response.copy(outputTranscription = removed)
                } else {
                  response
                }
              }
            ),
        )

      val events = agent.runLive(liveContextFor(agent)).toList()

      val blocked = events.single { it.turnComplete && it.outputTranscription != null }
      assertEquals(false, blocked.partial)
      assertEquals(removed, blocked.outputTranscription)
    }

  @Test
  fun runLive_customToolTransfer_doesNotRunTheTargetTurnBased() = runBlocking {
    var childModelCalls = 0
    val childModel =
      object : Model {
        override val name = "child-model"

        override suspend fun connect(request: LlmRequest): LiveConnection =
          RecordingLiveConnection(listOf(turnComplete()))

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {
            childModelCalls++
          }
      }
    val child = LlmAgent(name = "child", description = "The transfer target.", model = childModel)
    // A live run isn't offered transfer_to_agent, but a custom tool can still request a transfer.
    val handOff =
      DummyTool("hand_off") { toolContext, _ ->
        toolContext.actions.transferToAgent = "child"
        mapOf("status" to "ok")
      }
    val connection =
      RecordingLiveConnection(
        listOf(
          LlmResponse(
            content =
              Content(
                Role.MODEL,
                listOf(Part(functionCall = FunctionCall("hand_off", emptyMap(), id = "t1"))),
              )
          ),
          turnComplete(),
        )
      )
    val root =
      LlmAgent(
        name = "root",
        description = "Transfers mid-conversation.",
        model = RecordingLiveModel(connection),
        tools = listOf(handOff),
        subAgents = listOf(child),
      )

    root.runLive(liveContextFor(root)).toList()

    // Running the target turn-based would call its model with the parent's live queue in hand.
    assertEquals(0, childModelCalls)
  }

  @Test
  fun runLive_stateDelta_reachesTheSession() = runBlocking {
    val agent = liveAgent(RecordingLiveConnection(untilClosed = true))
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "state-session")
    val queue = LiveRequestQueue()
    queue.send(LiveRequest(stateDelta = mapOf("k" to "v")))
    queue.close()

    agent
      .runLive(
        liveContextFor(
          agent,
          queue,
          session = sessionService.createSession(key),
          sessionService = sessionService,
        )
      )
      .toList()

    assertEquals("v", sessionService.getSession(key)?.state?.get("k"))
  }

  @Test
  fun runLive_sessionAppends_neverOverlapTheCallersAppends() = runBlocking {
    val agent =
      liveAgent(
        RecordingLiveConnection(
          listOf(LlmResponse(content = modelMessage("hi")), turnComplete()),
          untilClosed = true,
        ),
        beforeModelCallbacks = listOf(writeStateAndContinue("c", "d")),
      )
    val sessionService = OverlapCheckingSessionService()
    val key = SessionKey("app", "user", "append-lock")
    val session = sessionService.createSession(key)
    val queue = LiveRequestQueue()
    queue.send(LiveRequest(stateDelta = mapOf("k" to "v")))
    queue.sendContent(userMessage("typed"))
    queue.close()
    val context = liveContextFor(agent, queue, session = session, sessionService = sessionService)

    // Appends each event under the context's lock, as the runner does.
    agent.runLive(context).collect { event ->
      if (!event.partial) {
        val unused =
          context.sessionAppendLock.withLock { sessionService.appendEvent(session, event) }
      }
    }

    val stored = checkNotNull(sessionService.getSession(key))
    assertEquals("v", stored.state["k"])
    assertEquals("d", stored.state["c"])
    assertTrue(stored.events.any { it.content?.parts?.firstOrNull()?.text == "hi" })
  }

  @Test
  fun runLive_callerAppendingUnderTheLock_neverDeadlocksTheTurn() = runBlocking {
    val agent =
      liveAgent(
        RecordingLiveConnection(
          listOf(LlmResponse(content = modelMessage("hi")), turnComplete()),
          untilClosed = true,
        ),
        beforeModelCallbacks = listOf(writeStateAndContinue("c", "d")),
      )
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "append-no-deadlock")
    val session = sessionService.createSession(key)
    val queue = LiveRequestQueue()
    queue.send(LiveRequest(stateDelta = mapOf("k" to "v")))
    queue.sendContent(userMessage("typed"))
    queue.close()
    val context = liveContextFor(agent, queue, session = session, sessionService = sessionService)

    // A turn emitting under the lock would deadlock against this caller; the timeout fails it.
    withTimeout(10.seconds) {
      agent.runLive(context).collect { event ->
        val unused =
          context.sessionAppendLock.withLock { sessionService.appendEvent(session, event) }
      }
    }

    val stored = checkNotNull(sessionService.getSession(key))
    assertEquals("v", stored.state["k"])
    assertEquals("d", stored.state["c"])
  }

  @Test
  fun runLive_dropAfterTheUserTurnIsStored_doesNotStoreItTwice() = runTest {
    val committed = CompletableDeferred<Unit>()
    val sessionService = CommitThenYieldSessionService({ it.author == Role.USER }, committed)
    val key = SessionKey("app", "user", "replay-user")
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hi"))
    val firstSent = mutableListOf<String>()
    val secondSent = mutableListOf<String>()
    val first =
      ScriptedConn("c0") {
        committed.await()
        throw connectionDrop()
      }
    first.onSendContent = { c -> firstSent += c.parts.mapNotNull { it.text } }
    val second =
      ScriptedConn("c1") {
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    second.onSendContent = { c -> secondSent += c.parts.mapNotNull { it.text } }
    val agent = LlmAgent(name = "live_agent", model = RecordingLiveModel(first, second))
    val context =
      liveContextFor(
        agent,
        queue,
        session = sessionService.createSession(key),
        sessionService = sessionService,
        runConfig = RunConfig(sessionResumption = SessionResumptionConfig(handle = "h-1")),
      )

    agent.runLive(context).toList()

    // The cancelled sender never writes to c0; the resumed c1 receives the replayed turn.
    assertEquals(emptyList<String>(), firstSent)
    assertEquals(listOf("hi"), secondSent)
    val stored = checkNotNull(sessionService.getSession(key))
    assertEquals(1, stored.events.count { it.author == Role.USER })
  }

  @Test
  fun runLive_dropAfterACallbacksStateIsStored_doesNotStoreItTwice() = runTest {
    val committed = CompletableDeferred<Unit>()
    val sessionService =
      CommitThenYieldSessionService({ it.actions.stateDelta["k"] == "v" }, committed)
    val key = SessionKey("app", "user", "replay-delta")
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hi"))
    var callbackRuns = 0
    val countingCallback = BeforeModelCallback { callbackContext, request ->
      callbackRuns++
      callbackContext.updateState("k", "v")
      CallbackChoice.Continue(request)
    }
    val firstSent = mutableListOf<String>()
    val secondSent = mutableListOf<String>()
    val first =
      ScriptedConn("c0") {
        committed.await()
        throw connectionDrop()
      }
    first.onSendContent = { c -> firstSent += c.parts.mapNotNull { it.text } }
    val second =
      ScriptedConn("c1") {
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    second.onSendContent = { c -> secondSent += c.parts.mapNotNull { it.text } }
    val agent =
      LlmAgent(
        name = "live_agent",
        model = RecordingLiveModel(first, second),
        beforeModelCallbacks = listOf(countingCallback),
      )
    val context =
      liveContextFor(
        agent,
        queue,
        session = sessionService.createSession(key),
        sessionService = sessionService,
        runConfig = RunConfig(sessionResumption = SessionResumptionConfig(handle = "h-1")),
      )

    agent.runLive(context).toList()

    // The replay reuses the screened content, so the callback runs once and c1 receives the turn.
    assertEquals(emptyList<String>(), firstSent)
    assertEquals(listOf("hi"), secondSent)
    assertEquals(1, callbackRuns)
    val stored = checkNotNull(sessionService.getSession(key))
    assertEquals(1, stored.events.count { it.actions.stateDelta["k"] == "v" })
  }

  @Test
  fun runLive_sessionAppendThatTimesOut_failsTheRunWithAClearError() =
    runTest(timeout = 20.seconds) {
      val inner = InMemorySessionService()
      val sessionService =
        object : SessionService by inner {
          override suspend fun appendEvent(session: Session, event: Event): Event =
            awaitCancellation()
        }
      val key = SessionKey("app", "user", "hung-append")
      val queue = LiveRequestQueue()
      queue.sendContent(userMessage("hi"))
      queue.close()
      val connection = ScriptedConn("c0") { it.closed.await() }
      val agent = LlmAgent(name = "live_agent", model = RecordingLiveModel(connection))
      val context =
        liveContextFor(
          agent,
          queue,
          session = inner.createSession(key),
          sessionService = sessionService,
        )

      // A timed-out append leaves the session state unknown, so the run fails rather than continue.
      val failure = assertFailsWith<IllegalStateException> { agent.runLive(context).toList() }

      assertTrue(testScheduler.currentTime >= 10_000, "did not wait the append timeout first")
      assertTrue(
        failure.message?.contains("did not finish") == true,
        "the failure did not explain itself: ${failure.message}",
      )
      assertTrue(failure.message?.contains("hi") != true, "the error echoed the payload")
    }

  @Test
  fun runLive_appendWaitingForAHeldLock_appendsOnceItIsReleased() = runTest {
    // Every holder's work is bounded, so a held lock delays the append instead of failing the run.
    val inner = InMemorySessionService()
    val key = SessionKey("app", "user", "held-lock")
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hi"))
    queue.close()
    val connection = ScriptedConn("c0") { it.closed.await() }
    val agent = LlmAgent(name = "live_agent", model = RecordingLiveModel(connection))
    val context =
      liveContextFor(agent, queue, session = inner.createSession(key), sessionService = inner)
    // Another appender holds the lock.
    context.sessionAppendLock.lock()

    val run = async { agent.runLive(context).toList() }
    advanceTimeBy(30_000)

    assertTrue(run.isActive, "the run gave up waiting for the held lock")
    context.sessionAppendLock.unlock()
    run.await()
    assertEquals(1, checkNotNull(inner.getSession(key)).events.count { it.author == Role.USER })
  }

  @Test
  fun runLive_cancelledWhileWaitingForAHeldLock_stopsWaiting() = runTest {
    // A user turn's wait for the lock stays cancellable, so cancelling the run does not hang on it.
    val inner = InMemorySessionService()
    val key = SessionKey("app", "user", "held-lock-cancel")
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hi"))
    val connection = ScriptedConn("c0") { it.closed.await() }
    val agent = LlmAgent(name = "live_agent", model = RecordingLiveModel(connection))
    val context =
      liveContextFor(agent, queue, session = inner.createSession(key), sessionService = inner)
    context.sessionAppendLock.lock()
    val run = launch { agent.runLive(context).toList() }
    runCurrent()
    assertTrue(run.isActive, "the run should be waiting for the held lock")

    run.cancelAndJoin()

    assertTrue(run.isCancelled)
    assertTrue(
      context.sessionAppendLock.isLocked,
      "the cancelled run released a lock it never held",
    )
    assertEquals(0, checkNotNull(inner.getSession(key)).events.count { it.author == Role.USER })
    context.sessionAppendLock.unlock()
  }

  @Test
  fun runLive_cancelledWhileAStateDeltaWaitsForAHeldLock_stopsWaiting() = runTest {
    // A state-only request's wait for the lock is cancellable too, like a user turn's.
    val inner = InMemorySessionService()
    val key = SessionKey("app", "user", "held-lock-delta")
    val queue = LiveRequestQueue()
    queue.send(LiveRequest(stateDelta = mapOf("k" to "v")))
    val connection = ScriptedConn("c0") { it.closed.await() }
    val agent = LlmAgent(name = "live_agent", model = RecordingLiveModel(connection))
    val context =
      liveContextFor(agent, queue, session = inner.createSession(key), sessionService = inner)
    context.sessionAppendLock.lock()
    val run = launch { agent.runLive(context).toList() }
    runCurrent()
    assertTrue(run.isActive, "the run should still be running before the cancel")

    try {
      // A wait that ignored the cancel fails here instead of hanging the test.
      withTimeout(1.seconds) { run.cancelAndJoin() }
      assertTrue(run.isCancelled)
      assertTrue(
        context.sessionAppendLock.isLocked,
        "the cancelled run released a lock it never held",
      )
      assertEquals(0, checkNotNull(inner.getSession(key)).events.count { it.author == Role.USER })
    } finally {
      context.sessionAppendLock.unlock()
    }
  }

  @Test
  fun runLive_stateWrites_addNoEventToTheStream() = runBlocking {
    val agent =
      liveAgent(
        RecordingLiveConnection(untilClosed = true),
        beforeModelCallbacks = listOf(writeStateAndContinue("c", "d")),
      )
    val sessionService = InMemorySessionService()
    val queue = LiveRequestQueue()
    queue.send(LiveRequest(stateDelta = mapOf("k" to "v")))
    queue.sendContent(userMessage("typed"))
    queue.close()

    val events =
      agent
        .runLive(
          liveContextFor(
            agent,
            queue,
            session = sessionService.createSession(SessionKey("app", "user", "no-extra-events")),
            sessionService = sessionService,
          )
        )
        .toList()

    assertEquals(emptyList<Event>(), events)
  }

  @Test
  fun runLive_agentWithSubAgents_isNotOfferedTransferToAgent() = runBlocking {
    val model = RecordingLiveModel(RecordingLiveConnection(untilClosed = true))
    val agent =
      LlmAgent(
        name = "LiveAgent",
        model = model,
        subAgents =
          listOf(LlmAgent(name = "Helper", description = "Helps.", model = DummyModel("helper"))),
      )
    val queue = LiveRequestQueue()
    queue.close()

    agent.runLive(liveContextFor(agent, queue)).toList()

    val offered =
      model.connectedRequests.single().config.tools.orEmpty().flatMap {
        it.functionDeclarations.orEmpty()
      }
    assertTrue(offered.none { it.name == TRANSFER_TO_AGENT_TOOL_NAME })
  }

  @Test
  fun runLive_stateDeltaWithoutASessionService_isDroppedWithoutFailingTheRun() = runBlocking {
    val agent = liveAgent(RecordingLiveConnection(untilClosed = true))
    val queue = LiveRequestQueue()
    queue.send(LiveRequest(stateDelta = mapOf("k" to "v")))
    queue.close()

    val events = agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(emptyList<Event>(), events)
  }

  @Test
  fun runLive_turnComplete_carriesInteractionStatusReasonAndSessionIdToTheEvent() = runBlocking {
    val connection =
      RecordingLiveConnection(
        listOf(
          LlmResponse(
            turnComplete = true,
            turnCompleteReason = TurnCompleteReason.NEED_MORE_INPUT,
            interactionStatus = InteractionStatus.IN_PROGRESS,
            liveSessionId = "live-1",
          )
        )
      )
    val agent = liveAgent(connection)

    val event = agent.runLive(liveContextFor(agent)).toList().single { it.turnComplete }

    assertEquals(TurnCompleteReason.NEED_MORE_INPUT, event.turnCompleteReason)
    assertEquals(InteractionStatus.IN_PROGRESS, event.interactionStatus)
    assertEquals("live-1", event.liveSessionId)
  }

  @Test
  fun runLive_outputKey_savesNonPartialFinalText() = runBlocking {
    val agent =
      outputKeyAgent(LlmResponse(content = Content(Role.MODEL, listOf(Part(text = "done")))))
    val saved = mutableListOf<Any?>()

    // Read on arrival: a save made after emitting would still show on the retained event.
    agent.runLive(liveContextFor(agent)).collect { saved += it.actions.stateDelta["answer"] }

    assertEquals(listOf("done", null), saved)
  }

  @Test
  fun runLive_outputKey_skipsPartialText() = runBlocking {
    val agent =
      outputKeyAgent(
        LlmResponse(content = Content(Role.MODEL, listOf(Part(text = "do"))), partial = true)
      )

    val events = agent.runLive(liveContextFor(agent)).toList()

    assertNull(events.single { it.partial }.actions.stateDelta["answer"])
  }

  @Test
  fun runLive_outputKey_skipsUserAuthoredText() = runBlocking {
    // The user's words arrive as live events; the author check keeps them out of the output key.
    val agent =
      outputKeyAgent(LlmResponse(content = Content(Role.USER, listOf(Part(text = "book it")))))

    val events = agent.runLive(liveContextFor(agent)).toList()

    assertEquals(listOf(null, null), events.map { it.actions.stateDelta["answer"] })
  }

  @Test
  fun runLive_contentResponse_carriesLiveSessionIdButNoTurnSignals() = runBlocking {
    val connection =
      RecordingLiveConnection(
        listOf(
          LlmResponse(
            content = Content(Role.MODEL, listOf(Part(text = "hi"))),
            liveSessionId = "live-1",
          )
        )
      )
    val agent = liveAgent(connection)

    val event = agent.runLive(liveContextFor(agent)).toList().single()

    assertEquals("live-1", event.liveSessionId)
    assertNull(event.turnCompleteReason)
    assertNull(event.interactionStatus)
  }

  @Test
  fun runAsync_streamingStep_eachEmittedEventGetsItsOwnActionsSnapshot() = runBlocking {
    // Each emitted event must carry its own EventActions, isolating already-emitted partials.
    val streamingModel =
      DummyModel(
        "streaming-model",
        listOf(
          flowOf(
            LlmResponse(content = modelMessage("a"), partial = true),
            LlmResponse(content = modelMessage("b"), partial = true),
            LlmResponse(content = modelMessage("ab")),
          )
        ),
      )
    val agent = LlmAgent(name = "test-agent", model = streamingModel, outputKey = "output")
    val runner = InMemoryRunner(agent = agent)

    val modelEvents =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = userMessage("hi"),
          runConfig = RunConfig(streamingMode = StreamingMode.SSE),
        )
        .toList()
        .filter { it.author == "test-agent" }

    // Two partials followed by the aggregated final event, each with a distinct EventActions.
    assertEquals(3, modelEvents.size)
    assertNotSame(modelEvents[0].actions, modelEvents[1].actions)
    assertNotSame(modelEvents[1].actions, modelEvents[2].actions)
    assertNotSame(modelEvents[0].actions, modelEvents[2].actions)

    // The final-response event writes outputKey into its own snapshot, not the earlier partials'.
    assertEquals("ab", modelEvents[2].actions.stateDelta["output"])
    assertNull(modelEvents[0].actions.stateDelta["output"])
    assertNull(modelEvents[1].actions.stateDelta["output"])
  }

  @Test
  fun runAsync_streamingSteps_shareOneEventIdPerModelResponse() = runBlocking {
    // Clients such as the Dev UI replace a reply's streamed rows with its complete event by id.
    val call = FunctionCall(name = "lookup", id = "call_1")
    val streamingModel =
      DummyModel(
        "streaming-model",
        listOf(
          flowOf(
            LlmResponse(
              content = Content(Role.MODEL, listOf(Part(functionCall = call))),
              partial = true,
            ),
            LlmResponse(content = Content(Role.MODEL, listOf(Part(functionCall = call)))),
          ),
          flowOf(
            LlmResponse(content = modelMessage("a"), partial = true),
            LlmResponse(content = modelMessage("b"), partial = true),
            LlmResponse(content = modelMessage("ab")),
          ),
        ),
      )
    val agent =
      LlmAgent(
        name = "test-agent",
        model = streamingModel,
        tools = listOf(DummyTool(name = "lookup")),
      )
    val runner = InMemoryRunner(agent = agent)

    val events =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = userMessage("hi"),
          runConfig = RunConfig(streamingMode = StreamingMode.SSE),
        )
        .toList()
        .filter { it.author == "test-agent" }

    val callIds = events.filter { it.functionCalls().isNotEmpty() }.map { it.id }
    val responseIds = events.filter { it.functionResponses().isNotEmpty() }.map { it.id }
    val textIds =
      events.filter { it.content?.parts.orEmpty().any { p -> p.text != null } }.map { it.id }
    assertEquals(2, callIds.size)
    assertEquals(1, callIds.toSet().size)
    assertEquals(3, textIds.size)
    assertEquals(1, textIds.toSet().size)
    // The tool response and the second model response each start a new id.
    assertEquals(3, (callIds + responseIds + textIds).toSet().size)
  }

  @Test
  fun runAsync_oneCallWithTwoCompleteResponses_givesEachItsOwnId() = runBlocking {
    // Both complete events are saved, and RoomSessionService keys rows by event id.
    val streamingModel =
      DummyModel(
        "streaming-model",
        listOf(
          flowOf(
            LlmResponse(content = modelMessage("a"), partial = true),
            LlmResponse(content = modelMessage("a")),
            LlmResponse(content = modelMessage("b"), partial = true),
            LlmResponse(content = modelMessage("b")),
          )
        ),
      )
    val runner = InMemoryRunner(agent = LlmAgent(name = "test-agent", model = streamingModel))

    val events =
      runner
        .runAsync(
          userId = "user1",
          sessionId = "session1",
          newMessage = userMessage("hi"),
          runConfig = RunConfig(streamingMode = StreamingMode.SSE),
        )
        .toList()
        .filter { it.author == "test-agent" }

    assertEquals(listOf(true, false, true, false), events.map { it.partial })
    assertEquals(events[0].id, events[1].id)
    assertEquals(events[2].id, events[3].id)
    assertNotEquals(events[1].id, events[3].id)
  }

  private fun liveAgent(
    connection: LiveConnection,
    outputKey: String? = null,
    beforeModelCallbacks: List<BeforeModelCallback> = emptyList(),
    afterModelCallbacks: List<AfterModelCallback> = emptyList(),
  ) =
    LlmAgent(
      name = "LiveAgent",
      description = "Answers live.",
      model = RecordingLiveModel(connection),
      outputKey = outputKey,
      beforeModelCallbacks = beforeModelCallbacks,
      afterModelCallbacks = afterModelCallbacks,
    )

  private fun toolAgent(connection: LiveConnection) =
    LlmAgent(
      name = "LiveAgent",
      description = "Answers live.",
      model = RecordingLiveModel(connection),
      tools = listOf(DummyTool("my_function", onRun = { _, _ -> mapOf("response" to "done") })),
    )

  private fun outputKeyAgent(response: LlmResponse) =
    liveAgent(RecordingLiveConnection(listOf(response, turnComplete())), outputKey = "answer")

  /** A model turn that calls `my_function`, then completes. */
  private fun toolCallTurn(liveSessionId: String? = null): List<LlmResponse> =
    listOf(
      LlmResponse(
        content =
          Content(
            Role.MODEL,
            listOf(Part(functionCall = FunctionCall("my_function", mapOf("a" to "b"), id = "c1"))),
          ),
        liveSessionId = liveSessionId,
      ),
      turnComplete(),
    )

  /** Writes state [key] = [value] and lets the input through. */
  private fun writeStateAndContinue(key: String, value: String) =
    BeforeModelCallback { callbackContext, request ->
      callbackContext.updateState(key, value)
      CallbackChoice.Continue(request)
    }

  /** Blocks a typed message reading [text], writing state `k` = `v` and answering [refusal]. */
  private fun blockText(text: String, refusal: Content) =
    BeforeModelCallback { callbackContext, request ->
      if (request.contents.single().parts.single().text == text) {
        callbackContext.updateState("k", "v")
        CallbackChoice.Break(LlmResponse(content = refusal))
      } else {
        CallbackChoice.Continue(request)
      }
    }

  /** Rewrites "4111" to "****" in every text part and continues, as a redaction guardrail would. */
  private fun redactingCallback() = BeforeModelCallback { _, request ->
    val redacted =
      request.contents.map { content ->
        content.copy(
          parts =
            content.parts.map { part ->
              part.text?.let { Part(text = it.replace("4111", "****")) } ?: part
            }
        )
      }
    CallbackChoice.Continue(request.copy(contents = redacted))
  }

  /** Replaces any output transcription reading "secret", which blocks the turn and restarts. */
  private fun blockSecret() = AfterModelCallback { _, response ->
    if (response.outputTranscription?.text == "secret") LlmResponse(content = modelMessage("x"))
    else response
  }

  /** A turn whose output transcription reads "secret", then completes. */
  private fun secretTurn() =
    RecordingLiveConnection(
      listOf(LlmResponse(outputTranscription = Transcription(text = "secret")), turnComplete())
    )

  /** A turn whose first chunk is a partial output transcription reading "secret". */
  private fun partialSecretTurn() =
    RecordingLiveConnection(
      listOf(
        LlmResponse(outputTranscription = Transcription(text = "secret"), partial = true),
        turnComplete(),
      )
    )

  /**
   * Writes each span's start and end into [log], so a test can order them against the connection.
   */
  private class SpanLoggingTracer(private val log: MutableList<String>) : Tracer {
    private val delegate = DummyTracer()

    override fun spanBuilder(spanName: String): SpanBuilder {
      val builder = delegate.spanBuilder(spanName)
      return object : SpanBuilder by builder {
        override fun startSpan(): Span {
          log += "span start: $spanName"
          val span = builder.startSpan()
          return object : Span by span {
            override fun end() {
              log += "span end: $spanName"
              span.end()
            }
          }
        }
      }
    }

    override suspend fun currentContext() = delegate.currentContext()

    override fun contextWithSpan(span: Span) = delegate.contextWithSpan(span)
  }

  private suspend fun sessionWithEarlierMessage(
    content: Content = userMessage("earlier")
  ): Session {
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "live-session")
    val unused =
      sessionService.appendEvent(
        sessionService.createSession(key),
        Event(author = Role.USER, content = content),
      )
    return checkNotNull(sessionService.getSession(key))
  }

  private suspend fun liveContextFor(
    agent: BaseAgent,
    queue: LiveRequestQueue = LiveRequestQueue(),
    session: Session? = null,
    sessionService: SessionService? = null,
    runConfig: RunConfig? = null,
  ): InvocationContext =
    InvocationContext(
        agent = agent,
        session =
          session
            ?: InMemorySessionService().createSession(SessionKey("app", "user", "live-session")),
        runConfig = runConfig,
        sessionService = sessionService,
      )
      .apply { frameworkData.liveRequestQueue = queue }

  /**
   * Commits an append, then suspends, so a drop can cancel the caller after the commit is stored.
   */
  private class CommitThenYieldSessionService(
    private val gateOn: (Event) -> Boolean,
    private val committed: CompletableDeferred<Unit>,
    private val inner: InMemorySessionService = InMemorySessionService(),
  ) : SessionService by inner {
    override suspend fun appendEvent(session: Session, event: Event): Event {
      val stored = inner.appendEvent(session, event)
      if (gateOn(event)) {
        committed.complete(Unit)
        yield()
      }
      return stored
    }
  }

  /**
   * Fails an append that starts while another is still running, as `RoomSessionService` rejects one
   * that read the session before the other committed.
   */
  private class OverlapCheckingSessionService(
    private val inner: InMemorySessionService = InMemorySessionService()
  ) : SessionService by inner {
    private val appending = Mutex()

    override suspend fun appendEvent(session: Session, event: Event): Event {
      check(appending.tryLock()) { "Overlapping appends to one session" }
      try {
        delay(50)
        return inner.appendEvent(session, event)
      } finally {
        appending.unlock()
      }
    }
  }

  /**
   * A live connection that records what is written to it and replays [turns] one per collection,
   * holding a turn open after a tool call until the answer arrives as a live model may. Once the
   * turns run out, a collection comes back empty and ends the run, unless [untilClosed] makes it
   * wait for [closeSession] first. A closed connection rejects writes; [hangsClose] makes the close
   * never return.
   */
  private class RecordingLiveConnection(
    vararg turns: List<LlmResponse>,
    private val untilClosed: Boolean = false,
    private val servesAfterClose: Boolean = false,
    private val failsTurn: Boolean = false,
    private val failsClose: Boolean = false,
    private val hangsClose: Boolean = false,
  ) : SingleCollectorLiveConnection() {
    private val turns = turns.toList()
    val log = mutableListOf<String>()
    val sentContent = mutableListOf<Content>()
    val sentHistory = mutableListOf<Content>()
    var sendHistoryCalls = 0
    var closeCalls = 0
    private var next = 0
    private val answered = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()

    fun sentTexts(): List<String> = sentContent.flatMap { it.parts }.mapNotNull { it.text }

    override fun responses(): Flow<LlmResponse> = flow {
      if (failsTurn) throw ScriptedTurnFailure()
      if (servesAfterClose) closed.await()
      when {
        next < turns.size -> {
          for (response in turns[next++]) {
            emit(response)
            if (
              response.content?.parts.orEmpty().any { it.functionCall != null } &&
                !closed.isCompleted
            ) {
              withTimeoutOrNull(5.seconds) { answered.await() }
            }
          }
        }
        untilClosed -> closed.await()
      }
    }

    override suspend fun sendHistory(history: List<Content>) {
      sendHistoryCalls++
      sentHistory += history
      log += "history:" + history.flatMap { it.parts }.mapNotNull { it.text }.joinToString()
    }

    override suspend fun sendContent(content: Content, partial: Boolean) {
      check(!closed.isCompleted) { "connection closed" }
      sentContent += content
      log += "content:" + content.parts.mapNotNull { it.text }.joinToString() + " partial=$partial"
      if (content.parts.any { it.functionResponse != null }) answered.complete(Unit)
    }

    override suspend fun sendRealtime(input: RealtimeInput) {
      log += "realtime"
    }

    override suspend fun closeSession() {
      closeCalls++
      log += "close"
      closed.complete(Unit)
      if (failsClose) throw TeardownFailure()
      if (hangsClose) awaitCancellation()
    }
  }

  /** Hands out [connections] in order, one per connect, and records each connect's request. */
  private class RecordingLiveModel(
    private vararg val connections: LiveConnection,
    private val onConnect: (connects: Int) -> Unit = {},
  ) : Model {
    override val name = "live-model"
    val connectedRequests = mutableListOf<LlmRequest>()
    val connectCalls: Int
      get() = connectedRequests.size

    override suspend fun connect(request: LlmRequest): LiveConnection {
      connectedRequests += request
      onConnect(connectCalls)
      return connections[minOf(connectCalls, connections.size) - 1]
    }

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {}
  }

  @Test
  fun runLive_callersResumptionHandle_resumesTheFirstConnectionFromIt() = runBlocking {
    val connection = RecordingLiveConnection(listOf(turnComplete()))
    val model = RecordingLiveModel(connection)
    val agent = LlmAgent(name = "live_agent", description = "A live agent.", model = model)
    val runConfig = RunConfig(sessionResumption = SessionResumptionConfig(handle = "h-1"))
    // The caller handle makes a clean close reconnect now, so the caller closes to end the run.
    val queue = LiveRequestQueue()
    queue.close()

    agent.runLive(liveContextFor(agent, queue, runConfig = runConfig)).toList()

    assertEquals(
      "h-1",
      model.connectedRequests.single().liveConnectConfig.sessionResumption?.handle,
    )
  }

  /**
   * A live connection whose [script] drives what `receive()` emits or throws, for the reconnect and
   * drop tests. [onSendContent] and [onClose] let a test interpose on a write or the teardown, and
   * the script runs once, so a later `receive()` returns empty, which the flow reads as a close.
   */
  private class ScriptedConn(
    val name: String,
    private val script:
      suspend kotlinx.coroutines.flow.FlowCollector<LlmResponse>.(ScriptedConn) -> Unit,
  ) : LiveConnection {
    val closed = CompletableDeferred<Unit>()
    var closeCalls = 0
    var onSendContent: suspend (Content) -> Unit = {}
    var onSendRealtime: suspend (RealtimeInput) -> Unit = {}
    var onClose: suspend () -> Unit = {}
    var sendHistoryCalls = 0
    private var served = false

    override suspend fun sendHistory(history: List<Content>) {
      sendHistoryCalls++
    }

    override suspend fun sendContent(content: Content, partial: Boolean) {
      onSendContent(content)
    }

    override suspend fun sendRealtime(input: RealtimeInput) {
      onSendRealtime(input)
    }

    override fun receive(): Flow<LlmResponse> = flow {
      if (served) return@flow
      served = true
      script(this@ScriptedConn)
    }

    override suspend fun closeSession() {
      closeCalls++
      closed.complete(Unit)
      onClose()
    }
  }

  @Test
  fun runLive_reconnectAfterADrop_closesEachConnectionOnce() = runTest {
    // The close-once flag is per connection, so a reconnect's new connection is also closed once.
    val queue = LiveRequestQueue()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        throw connectionDrop()
      }
    val second = ScriptedConn("c1") { it.closed.await() }
    val model = RecordingLiveModel(first, second) { connects -> if (connects == 2) queue.close() }
    val agent = LlmAgent(name = "live_agent", model = model)

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(2, model.connectCalls)
    assertEquals(1, first.closeCalls)
    assertEquals(1, second.closeCalls)
  }

  @Test
  fun runLive_senderCancelledAtGoAwayWhileACallbackSavesState_reconnectStillAppends() = runTest {
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "cancelled-append")
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("first"))
    var screened = false
    // Screens "first" under NonCancellable until the sender is cancelled, then writes state once.
    val callback = BeforeModelCallback { callbackContext, request ->
      if (request.contents.any { c -> c.parts.any { it.text == "first" } } && !screened) {
        screened = true
        val job = currentCoroutineContext().job
        withContext(NonCancellable) { withTimeout(5.seconds) { while (job.isActive) delay(10) } }
        callbackContext.updateState("k", "v")
      }
      CallbackChoice.Continue(request)
    }
    // conn1 hands back a handle, then a go-away that cancels the sender mid-callback.
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        emit(goAway())
      }
    // conn2 resumes, waits for the re-sent "first", takes a new "second", then ends the run.
    val firstResent = CompletableDeferred<Unit>()
    val secondSent = CompletableDeferred<Unit>()
    val second =
      ScriptedConn("c1") {
        firstResent.await()
        queue.sendContent(userMessage("second"))
        secondSent.await()
        emit(LlmResponse(content = modelMessage("hi"), turnComplete = true))
        queue.close()
        it.closed.await()
      }
    second.onSendContent = { content ->
      when (content.parts.singleOrNull()?.text) {
        "first" -> firstResent.complete(Unit)
        "second" -> secondSent.complete(Unit)
      }
    }
    val model = RecordingLiveModel(first, second)
    val agent =
      LlmAgent(name = "live_agent", model = model, beforeModelCallbacks = listOf(callback))
    val context =
      liveContextFor(
        agent,
        queue,
        session = sessionService.createSession(key),
        sessionService = sessionService,
      )

    agent.runLive(context).toList()

    assertEquals(2, model.connectCalls)
    val stored = checkNotNull(sessionService.getSession(key))
    val userTexts =
      stored.events
        .filter { it.author == Role.USER }
        .mapNotNull { it.content?.parts?.singleOrNull()?.text }
    assertEquals(listOf("first", "second"), userTexts)
  }

  @Test
  fun runLive_reconnectWhoseOpenDrops_isRetriedWithinTheBudget() = runTest {
    // conn1 drops after seeding a handle; the 2nd open fails as a drop; the 3rd opens and ends.
    val queue = LiveRequestQueue()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        throw connectionDrop()
      }
    val third =
      ScriptedConn("c2") {
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    val model =
      RecordingLiveModel(first, third) { connects ->
        if (connects == 2) {
          throw IllegalStateException(
            "The live session failed to open.",
            GenAiApiException(1011, "ConnectionClosed", "internal error"),
          )
        }
      }
    val agent = LlmAgent(name = "live_agent", model = model)

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(3, model.connectCalls)
    assertEquals(
      listOf("h-1", "h-1"),
      model.connectedRequests.drop(1).map { it.liveConnectConfig.sessionResumption?.handle },
    )
  }

  @Test
  fun runLive_firstOpenDropsWithoutAHandle_failsAtOnce() = runBlocking {
    // With no handle to resume from, a drop while opening fails the run as any drop does.
    val openFailure = IllegalStateException("The live session failed to open.", connectionDrop())
    val model =
      RecordingLiveModel(ScriptedConn("unused") {}) { connects ->
        if (connects == 1) throw openFailure
      }
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      assertFailsWith<IllegalStateException> {
        agent.runLive(liveContextFor(agent, LiveRequestQueue())).toList()
      }

    assertSame(openFailure, failure)
    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_reconnectWhoseOpenIsRefused_failsAtOnce() = runTest {
    // conn1 drops after seeding a handle; the 2nd open fails with a non-drop cause, so it surfaces.
    val queue = LiveRequestQueue()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        throw connectionDrop()
      }
    val second = ScriptedConn("c1") { it.closed.await() }
    val model =
      RecordingLiveModel(first, second) { connects ->
        if (connects == 2) {
          throw IllegalStateException(
            "The live session failed to open.",
            GenAiApiException(1008, "ConnectionClosed", "policy"),
          )
        }
      }
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      assertFailsWith<IllegalStateException> {
        agent.runLive(liveContextFor(agent, queue)).toList()
      }

    assertEquals("The live session failed to open.", failure.message)
    assertEquals(2, model.connectCalls)
  }

  @Test
  fun runLive_everyReconnectsOpenDrops_failsAfterTheBudget() = runTest {
    // conn1 seeds a handle then drops; every later open fails as a drop, exhausting the budget.
    var connects = 0
    val model =
      object : Model {
        override val name = "always-fails-to-open"

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {}

        override suspend fun connect(request: LlmRequest): LiveConnection {
          connects++
          if (connects == 1) {
            return ScriptedConn("c0") {
              emit(handleUpdate("h-1"))
              throw connectionDrop()
            }
          }
          throw IllegalStateException("The live session failed to open.", connectionDrop())
        }
      }
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      assertFailsWith<IllegalStateException> { agent.runLive(liveContextFor(agent)).toList() }

    assertEquals("The live session failed to open.", failure.message)
    assertEquals(MAX_LIVE_CONNECTS, connects)
  }

  @Test
  fun runLive_policyViolationClose_isNotRetried() = runBlocking {
    // A policy close (1008) is the server refusing us, not a drop, so the run does not reconnect.
    val connection =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        throw GenAiApiException(1008, "ConnectionClosed", "policy violation")
      }
    val model = RecordingLiveModel(connection)
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      assertFailsWith<GenAiApiException> { agent.runLive(liveContextFor(agent)).toList() }

    assertEquals(1008, failure.code)
    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_unresumableUpdate_dropSurfacesInsteadOfResumingAnOlderHandle() = runBlocking {
    // A withdrawn handle is cleared, so the next drop has nothing to resume and surfaces itself.
    val connection =
      ScriptedConn("c0") {
        emit(handleUpdate("h-old"))
        emit(turnComplete())
        emit(
          LlmResponse(
            liveSessionResumptionUpdate =
              LiveServerSessionResumptionUpdate(newHandle = null, resumable = false)
          )
        )
        throw connectionDrop()
      }
    val model = RecordingLiveModel(connection)
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      assertFailsWith<GenAiApiException> { agent.runLive(liveContextFor(agent)).toList() }

    assertEquals(1006, failure.code)
    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_cleanCloseWhileHoldingAHandle_resumesTheSession() = runTest {
    val queue = LiveRequestQueue()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        emit(LlmResponse(content = modelMessage("hi"), turnComplete = true))
        // receive() ends here cleanly: no drop, no go-away, caller still connected.
      }
    val second =
      ScriptedConn("c1") {
        emit(LlmResponse(content = modelMessage("resumed"), turnComplete = true))
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    val events = agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(2, model.connectCalls)
    assertEquals("h-1", model.connectedRequests[1].liveConnectConfig.sessionResumption?.handle)
    assertTrue(events.any { it.content?.parts?.singleOrNull()?.text == "resumed" })
  }

  @Test
  fun runLive_goAwayWhileHoldingAHandle_resumesWithItAndSendsNoHistory() = runTest {
    val queue = LiveRequestQueue()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        emit(goAway())
        it.closed.await()
      }
    val second =
      ScriptedConn("c1") {
        emit(LlmResponse(content = modelMessage("resumed"), turnComplete = true))
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    val events =
      agent.runLive(liveContextFor(agent, queue, session = sessionWithEarlierMessage())).toList()

    assertEquals(2, model.connectCalls)
    assertEquals(1, first.sendHistoryCalls)
    assertEquals("h-1", model.connectedRequests[1].liveConnectConfig.sessionResumption?.handle)
    // The resumed session already holds the history, so it is not sent again.
    assertEquals(0, second.sendHistoryCalls)
    assertTrue(events.any { it.content?.parts?.singleOrNull()?.text == "resumed" })
  }

  @Test
  fun runLive_dropsUntilTheBudgetRunsOut_rethrowsTheDrop() = runTest {
    // On exhaustion the drop that caused it surfaces, not a generic IllegalStateException.
    var connects = 0
    val model =
      object : Model {
        override val name = "always-drops"

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {}

        override suspend fun connect(request: LlmRequest): LiveConnection {
          connects++
          val first = connects == 1
          return ScriptedConn("c$connects") {
            if (first) emit(handleUpdate("h-1"))
            throw connectionDrop()
          }
        }
      }
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      assertFailsWith<GenAiApiException> { agent.runLive(liveContextFor(agent)).toList() }

    assertEquals(1006, failure.code)
    assertEquals(MAX_LIVE_CONNECTS, connects)
  }

  @Test
  fun runLive_dropsAroundACompletedTurn_resetTheReconnectBudget() = runTest {
    // c5 completes a turn before it drops, resetting the budget, so all nine drops reconnect.
    val queue = LiveRequestQueue()
    var connects = 0
    val model =
      object : Model {
        override val name = "completed-turn-resets-budget"

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {}

        override suspend fun connect(request: LlmRequest): LiveConnection {
          connects++
          return when (connects) {
            1 ->
              ScriptedConn("c1") {
                emit(handleUpdate("h-1"))
                throw connectionDrop()
              }
            5 ->
              ScriptedConn("c5") {
                emit(turnComplete())
                throw connectionDrop()
              }
            10 ->
              ScriptedConn("c10") {
                emit(turnComplete())
                queue.close()
                it.closed.await()
              }
            else -> ScriptedConn("c$connects") { throw connectionDrop() }
          }
        }
      }
    val agent = LlmAgent(name = "live_agent", model = model)

    // The completed turn reset the budget, so the run finishes instead of exhausting it.
    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(10, connects)
  }

  @Test
  fun runLive_collectorThrowsConnectionClosed_propagatesWithoutReconnect() = runBlocking {
    // A ConnectionClosed thrown by the caller collecting events is theirs, not a drop to retry.
    val connection =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        emit(LlmResponse(content = modelMessage("hi"), turnComplete = true))
        it.closed.await()
      }
    val model = RecordingLiveModel(connection)
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      assertFailsWith<GenAiApiException> {
        agent.runLive(liveContextFor(agent)).collect {
          throw GenAiApiException(1006, "ConnectionClosed", "downstream")
        }
      }

    assertEquals(1006, failure.code)
    assertTrue("downstream" in failure.message.orEmpty())
    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_dropAfterTheCallerClosedMidWrite_isReportedWithoutReconnecting() = runBlocking {
    // The caller hangs up mid-send, so the direct queue check, not the sender, catches it.
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hold me"))
    val sendStarted = CompletableDeferred<Unit>()
    val mayDrop = CompletableDeferred<Unit>()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        mayDrop.await()
        throw connectionDrop()
      }
    first.onSendContent = {
      sendStarted.complete(Unit)
      awaitCancellation()
    }
    val second = ScriptedConn("c1") { it.closed.await() }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    var failure: Throwable? = null
    val job = launch {
      failure =
        runCatching { agent.runLive(liveContextFor(agent, queue)).toList() }.exceptionOrNull()
    }
    sendStarted.await()
    queue.close()
    mayDrop.complete(Unit)
    job.join()

    assertEquals(1, model.connectCalls)
    assertTrue(failure is GenAiApiException)
  }

  @Test
  fun runLive_goAwayWithNoHandle_continuesInAFreshSession() = runTest {
    val queue = LiveRequestQueue()
    val first =
      ScriptedConn("c0") {
        emit(LlmResponse(content = modelMessage("hi"), turnComplete = true))
        emit(goAway())
      }
    val second =
      ScriptedConn("c1") {
        emit(LlmResponse(content = modelMessage("fresh"), turnComplete = true))
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second, onConnect = { if (it == 2) queue.close() })
    val agent = LlmAgent(name = "live_agent", model = model)
    val context = liveContextFor(agent, queue, session = sessionWithEarlierMessage())

    val events = agent.runLive(context).toList()

    assertEquals(2, model.connectCalls)
    // The fresh session is seeded with history and carries no resumption handle.
    assertNull(model.connectedRequests[1].liveConnectConfig.sessionResumption?.handle)
    assertTrue(
      "earlier" in model.connectedRequests[1].contents.flatMap { it.parts }.mapNotNull { it.text }
    )
    assertTrue(events.any { it.content?.parts?.singleOrNull()?.text == "fresh" })
  }

  @Test
  fun runLive_goAwaysWithAHandleThatNeverCompleteATurn_doNotExhaustTheBudget() = runTest {
    // A go-away never counts against the reconnect budget, so seven in a row still reconnect.
    goAwaysThenFinish(withHandle = true)
  }

  @Test
  fun runLive_goAwaysWithNoHandleThatNeverCompleteATurn_doNotExhaustTheBudget() = runTest {
    // The same on the fresh-session path: a handle-less go-away restarts and never counts either.
    goAwaysThenFinish(withHandle = false)
  }

  private suspend fun goAwaysThenFinish(withHandle: Boolean) {
    val queue = LiveRequestQueue()
    var connects = 0
    val model =
      object : Model {
        override val name = "goaways-then-finish"

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {}

        override suspend fun connect(request: LlmRequest): LiveConnection {
          connects++
          return if (connects <= 7) {
            ScriptedConn("c$connects") {
              if (withHandle) emit(handleUpdate("h"))
              emit(goAway())
            }
          } else {
            ScriptedConn("c$connects") {
              emit(LlmResponse(content = modelMessage("done"), turnComplete = true))
              queue.close()
              it.closed.await()
            }
          }
        }
      }
    val agent = LlmAgent(name = "live_agent", model = model)

    val events = agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(8, connects)
    assertTrue(events.any { it.content?.parts?.singleOrNull()?.text == "done" })
  }

  @Test
  fun runLive_blockedReplyCutOffByADrop_isStillEmittedOnce() = runTest {
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("blocked"))
    val refusal = modelMessage("not allowed")
    val callbackRan = CompletableDeferred<Unit>()
    // The drop lands before the refusal is delivered, so only the re-emit delivers it.
    val first =
      ScriptedConn("c0") {
        callbackRan.await()
        throw connectionDrop()
      }
    val second =
      ScriptedConn("c1") {
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second)
    val agent =
      LlmAgent(
        name = "live_agent",
        model = model,
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              if (request.contents.single().parts.single().text == "blocked") {
                callbackRan.complete(Unit)
                CallbackChoice.Break(LlmResponse(content = refusal))
              } else {
                CallbackChoice.Continue(request)
              }
            }
          ),
      )
    val context =
      liveContextFor(
        agent,
        queue,
        runConfig = RunConfig(sessionResumption = SessionResumptionConfig(handle = "h-1")),
      )

    val events = agent.runLive(context).toList()

    assertEquals(1, events.count { it.content == refusal })
  }

  @Test
  fun runLive_blockedReplyWhenTheCallerHangsUp_isEmittedExactlyOnce() = runBlocking {
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("blocked"))
    val refusal = modelMessage("not allowed")
    val callbackRan = CompletableDeferred<Unit>()
    // A hang-up waits for the in-flight reply to be handled, so it arrives once.
    val connection =
      ScriptedConn("c0") {
        callbackRan.await()
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(connection)
    val agent =
      LlmAgent(
        name = "live_agent",
        model = model,
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              if (request.contents.single().parts.single().text == "blocked") {
                callbackRan.complete(Unit)
                CallbackChoice.Break(LlmResponse(content = refusal))
              } else {
                CallbackChoice.Continue(request)
              }
            }
          ),
      )

    val events = agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(1, events.count { it.content == refusal })
  }

  @Test
  fun liveReconnectDelay_eachAttempt_staysWithinADoublingCeiling() {
    // A max draw hits the ceiling, so this checks that the ceiling doubles on each attempt.
    val maxDraw =
      object : Random() {
        override fun nextBits(bitCount: Int) = 0

        override fun nextLong(from: Long, until: Long) = until - 1
      }
    // attempt is 1-based: the first reconnect's ceiling is the 250 ms base, not twice it.
    assertEquals(
      listOf(250L, 500L, 1000L, 2000L, 4000L),
      (1..5).map { liveReconnectDelay(it, maxDraw).inWholeMilliseconds },
    )
    // Full jitter: every draw stays within the ceiling across the reconnect budget.
    val random = Random(20260824)
    repeat(200) {
      for (attempt in 1..5) {
        val delay = liveReconnectDelay(attempt, random)
        val ceiling = (250L shl (attempt - 1)).milliseconds
        assertTrue(delay in 0.milliseconds..ceiling, "attempt $attempt drew $delay, above $ceiling")
      }
    }
  }

  @Test
  fun liveReconnectDelay_fixedDraw_returnsTheDraw() {
    // The draw is used, not ignored: a zero draw gives no wait, a fixed draw gives exactly it.
    val zeroDraw =
      object : Random() {
        override fun nextBits(bitCount: Int) = 0

        override fun nextLong(from: Long, until: Long) = from
      }
    assertEquals(0L, liveReconnectDelay(3, zeroDraw).inWholeMilliseconds)
    val fixedDraw =
      object : Random() {
        override fun nextBits(bitCount: Int) = 0

        override fun nextLong(from: Long, until: Long) = 123L
      }
    assertEquals(123L, liveReconnectDelay(3, fixedDraw).inWholeMilliseconds)
  }

  @Test
  fun liveReconnectDelay_lastAttemptInTheBudget_capsAtFourSeconds() {
    // Five reconnects is the whole budget, so the ceiling never exceeds 4 s.
    val maxDraw =
      object : Random() {
        override fun nextBits(bitCount: Int) = 0

        override fun nextLong(from: Long, until: Long) = until - 1
      }
    assertEquals(4000L, liveReconnectDelay(5, maxDraw).inWholeMilliseconds)
  }

  @Test
  fun runLive_contentWithStateDelta_recordsTheDeltaOnceOnTheUserTurn() = runBlocking {
    val agent = liveAgent(RecordingLiveConnection(untilClosed = true))
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "delta")
    val queue = LiveRequestQueue()
    queue.send(LiveRequest(ContentInput(userMessage("hi")), stateDelta = mapOf("k" to "v")))
    queue.close()

    agent
      .runLive(
        liveContextFor(
          agent,
          queue,
          session = sessionService.createSession(key),
          sessionService = sessionService,
        )
      )
      .toList()

    val withDelta =
      sessionService.getSession(key)?.events.orEmpty().filter { it.actions.stateDelta.isNotEmpty() }
    assertEquals(1, withDelta.size)
    assertEquals("hi", withDelta.single().content?.parts?.single()?.text)
    assertEquals("v", withDelta.single().actions.stateDelta["k"])
  }

  @Test
  fun runLive_goAwayAfterTheCallerClosed_deliversTheQueuedInput() = runTest {
    // A go-away after close() still drains input queued before the close, resuming from the handle.
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("a"))
    queue.sendContent(userMessage("bye"))
    queue.close()
    // conn1 parks its write so nothing leaves on it, then hands back a handle and a go-away.
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        emit(goAway())
        it.closed.await()
      }
    first.onSendContent = { awaitCancellation() }
    val second = RecordingLiveConnection(untilClosed = true)
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "LiveAgent", model = model)

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals("the go-away did not reconnect to drain the closed queue", 2, model.connectCalls)
    assertTrue("bye" in second.sentTexts(), "queued input was dropped on the reconnect")
  }

  @Test
  fun runLive_resumptionUpdateWithAnEmptyHandle_dropSurfacesInsteadOfResuming() = runBlocking {
    // An empty handle update is no handle, so the following drop surfaces rather than resuming.
    val queue = LiveRequestQueue()
    val first =
      ScriptedConn("c0") {
        emit(LlmResponse(content = modelMessage("hi"), turnComplete = true))
        emit(handleUpdate(""))
        throw connectionDrop()
      }
    val second =
      ScriptedConn("c1") {
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      assertFailsWith<GenAiApiException> { agent.runLive(liveContextFor(agent, queue)).toList() }

    assertEquals(1006, failure.code)
    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_closeCode1011_isRetriedLikeAnAbnormalClose() = runTest {
    // 1011 (internal server error) is a recoverable drop, so a held handle reconnects on it.
    val queue = LiveRequestQueue()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        throw GenAiApiException(1011, "ConnectionClosed", "internal error")
      }
    val second =
      ScriptedConn("c1") {
        emit(LlmResponse(content = modelMessage("resumed"), turnComplete = true))
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    val events = agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(2, model.connectCalls)
    assertTrue(events.any { it.content?.parts?.singleOrNull()?.text == "resumed" })
  }

  @Test
  fun runLive_dropWithoutTheConnectionClosedStatus_isNotRetried() = runBlocking {
    // A 1006 with a non-abnormal-close status is a refusal, not a drop, so it surfaces.
    val queue = LiveRequestQueue()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        throw GenAiApiException(1006, "ServerError", "not a socket close")
      }
    val second =
      ScriptedConn("c1") {
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      assertFailsWith<GenAiApiException> { agent.runLive(liveContextFor(agent, queue)).toList() }

    assertEquals(1006, failure.code)
    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_cancelledAsADropIsCaught_opensNoFurtherConnection() = runBlocking {
    // Cancellation caught alongside the drop ends the run; it must not be retried as a drop.
    var job: Job? = null
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        job?.cancel()
        throw connectionDrop()
      }
    val second = ScriptedConn("c1") { it.closed.await() }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    job = launch { runCatching { agent.runLive(liveContextFor(agent)).toList() } }
    checkNotNull(job).join()

    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_freshSessionAfterAHandlelessGoAway_rebuildsRequestFromTheSession() = runTest {
    // A fresh restart rebuilds from the session, so the second connection gets the first's turn.
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "fresh-restart")
    val unused =
      sessionService.appendEvent(
        sessionService.createSession(key),
        Event(author = Role.USER, content = userMessage("earlier")),
      )
    val session = checkNotNull(sessionService.getSession(key))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("during"))
    val duringSeen = CompletableDeferred<Unit>()
    val first =
      ScriptedConn("c0") {
        emit(LlmResponse(content = modelMessage("hi"), turnComplete = true))
        duringSeen.await()
        emit(goAway())
      }
    // The first connection persists "during" before signalling, so the restart can rebuild with it.
    first.onSendContent = { content ->
      if (content.parts.any { it.text == "during" }) duringSeen.complete(Unit)
    }
    val second =
      ScriptedConn("c1") {
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    agent
      .runLive(liveContextFor(agent, queue, session = session, sessionService = sessionService))
      .toList()

    assertEquals(2, model.connectCalls)
    val secondHistory =
      model.connectedRequests[1].contents.flatMap { it.parts }.mapNotNull { it.text }
    assertTrue("during" in secondHistory)
  }

  @Test
  fun runLive_handlelessGoAway_waitsTheBackoffBeforeRestarting() = runTest {
    // Each handle-less go-away restarts through the jittered backoff, so virtual time advances.
    val queue = LiveRequestQueue()
    fun goAwayTurn() =
      ScriptedConn("c") {
        emit(LlmResponse(content = modelMessage("hi"), turnComplete = true))
        emit(goAway())
      }
    val last =
      ScriptedConn("last") {
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(goAwayTurn(), goAwayTurn(), goAwayTurn(), last)
    val agent = LlmAgent(name = "live_agent", model = model)

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(4, model.connectCalls)
    assertTrue(testScheduler.currentTime > 0)
  }

  @Test
  fun runLive_goAway_doesNotReadFurtherFromTheOldConnection() = runTest {
    // After a go-away the loop reconnects; a payload riding that response is not read or emitted.
    val queue = LiveRequestQueue()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        emit(
          LlmResponse(
            goAway = LiveServerGoAway(),
            content = modelMessage("leaked"),
            turnComplete = true,
          )
        )
      }
    val second =
      ScriptedConn("c1") {
        emit(LlmResponse(content = modelMessage("resumed"), turnComplete = true))
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    val events = agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(2, model.connectCalls)
    assertTrue(events.none { it.content?.parts?.singleOrNull()?.text == "leaked" })
  }

  @Test
  fun runLive_goAwayWithNoHandleWhileAWriteDrops_continuesInAFreshSession() = runTest {
    // A handle-less go-away restarts fresh even when a concurrent write drops the old connection.
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))
    val gate = CompletableDeferred<Unit>()
    val first =
      ScriptedConn("c0") {
        gate.complete(Unit)
        emit(goAway())
      }
    first.onSendContent = {
      gate.await()
      throw connectionDrop()
    }
    val second =
      ScriptedConn("c1") {
        emit(LlmResponse(content = modelMessage("fresh"), turnComplete = true))
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    val events = agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(2, model.connectCalls)
    assertTrue(events.any { it.content?.parts?.singleOrNull()?.text == "fresh" })
  }

  @Test
  fun runLive_writeFailsBeforeTheReceiveReportsTheDrop_resumesAndResendsTheRequest() = runTest {
    // A write failure before the receive side drops still replays the request on resume.
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "write-before-drop")
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))
    val sendFailed = CompletableDeferred<Unit>()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        sendFailed.await()
        throw connectionDrop()
      }
    first.onSendContent = {
      sendFailed.complete(Unit)
      throw kotlinx.coroutines.channels.ClosedSendChannelException("socket closed")
    }
    val resent = CompletableDeferred<Unit>()
    val secondSent = mutableListOf<String>()
    val second =
      ScriptedConn("c1") {
        resent.await()
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    second.onSendContent = { content ->
      secondSent += content.parts.mapNotNull { it.text }
      resent.complete(Unit)
    }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    agent
      .runLive(
        liveContextFor(
          agent,
          queue,
          session = sessionService.createSession(key),
          sessionService = sessionService,
        )
      )
      .toList()

    assertEquals(2, model.connectCalls)
    assertEquals(listOf("hello"), secondSent)
    assertEquals(
      1,
      checkNotNull(sessionService.getSession(key)).events.count { it.author == Role.USER },
    )
  }

  @Test
  fun runLive_writeFailsAfterAGoAway_continuesInAFreshSession() = runTest {
    // A plain write failure after a handleless go-away restarts fresh rather than failing the run.
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))
    val gate = CompletableDeferred<Unit>()
    val first =
      ScriptedConn("c0") {
        gate.complete(Unit)
        emit(goAway())
      }
    first.onSendContent = {
      gate.await()
      throw kotlinx.coroutines.channels.ClosedSendChannelException("socket closed")
    }
    val second =
      ScriptedConn("c1") {
        emit(LlmResponse(content = modelMessage("fresh"), turnComplete = true))
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(2, model.connectCalls)
  }

  @Test
  fun runLive_writeFailsOnAnOpenConnection_failsTheRunWithTheWriteError() = runTest {
    // A write failure while the receive side stays open fails the run after the teardown timeout.
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))
    val first = ScriptedConn("c0") { it.closed.await() }
    first.onSendContent = { throw IllegalStateException("bad frame") }
    val model = RecordingLiveModel(first)
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      runCatching { agent.runLive(liveContextFor(agent, queue)).toList() }.exceptionOrNull()

    assertTrue(failure is IllegalStateException && failure.message == "bad frame")
    assertTrue(
      testScheduler.currentTime >= 10_000,
      "did not wait the teardown bound before failing",
    )
    assertEquals(1, first.closeCalls)
    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_writeFailsOnAnOpenConnectionWithAHandle_failsTheRunWithTheWriteError() = runTest {
    // The connection outlived the failed write, so a handle cannot help.
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))
    val handleStored = CompletableDeferred<Unit>()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        handleStored.complete(Unit)
        it.closed.await()
      }
    first.onSendContent = {
      // Fail only once the handle is held, so the no-handle path cannot explain the failure.
      handleStored.await()
      throw IllegalStateException("bad frame")
    }
    val model = RecordingLiveModel(first)
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      runCatching { agent.runLive(liveContextFor(agent, queue)).toList() }.exceptionOrNull()

    assertTrue(failure is IllegalStateException && failure.message == "bad frame")
    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_storedTurnWriteFailsBeforeACleanClose_failsTheRunWithTheWriteError() = runTest {
    // The session holds a turn the model never got, and a clean close cannot resume it.
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "stored-turn"))
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))
    val sendFailed = CompletableDeferred<Unit>()
    val first = ScriptedConn("c0") { sendFailed.await() }
    first.onSendContent = {
      sendFailed.complete(Unit)
      throw IllegalStateException("bad frame")
    }
    val model = RecordingLiveModel(first)
    val agent = LlmAgent(name = "live_agent", model = model)

    val failure =
      runCatching {
          agent
            .runLive(
              liveContextFor(agent, queue, session = session, sessionService = sessionService)
            )
            .toList()
        }
        .exceptionOrNull()

    assertTrue(failure is IllegalStateException && failure.message == "bad frame")
    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_audioWriteFailsBeforeACleanClose_endsTheRunNormally() = runTest {
    // An audio frame is never stored, so a clean close with no handle ends normally, as in Python.
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "user", "audio-frame"))
    val queue = LiveRequestQueue()
    queue.sendRealtime(RealtimeInput.Audio(Blob(mimeType = "audio/pcm", data = ByteArray(64))))
    val sendFailed = CompletableDeferred<Unit>()
    val first = ScriptedConn("c0") { sendFailed.await() }
    first.onSendRealtime = {
      sendFailed.complete(Unit)
      throw kotlinx.coroutines.channels.ClosedSendChannelException("socket closed")
    }
    val model = RecordingLiveModel(first)
    val agent = LlmAgent(name = "live_agent", model = model)

    agent
      .runLive(liveContextFor(agent, queue, session = session, sessionService = sessionService))
      .toList()

    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_writeFailsThenTheServerClosesWithAHandle_resumesAndResendsTheRequest() = runTest {
    // A clean close with a handle reconnects, so the failed write is replayed, not raised.
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))
    val sendFailed = CompletableDeferred<Unit>()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        sendFailed.await()
      }
    first.onSendContent = {
      sendFailed.complete(Unit)
      throw kotlinx.coroutines.channels.ClosedSendChannelException("socket closed")
    }
    val resent = CompletableDeferred<Unit>()
    val secondSent = mutableListOf<String>()
    val second =
      ScriptedConn("c1") {
        resent.await()
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    second.onSendContent = { content ->
      secondSent += content.parts.mapNotNull { it.text }
      resent.complete(Unit)
    }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(2, model.connectCalls)
    assertEquals(listOf("hello"), secondSent)
  }

  @Test
  fun runLive_receiveDropsWhileTheCallerHandlesABlockedReply_deliversItAndResumes() = runTest {
    // A receive-side drop while the caller handles the refusal must not turn into a caller failure.
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("blocked"))
    val refusal = modelMessage("not allowed")
    val handling = CompletableDeferred<Unit>()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        handling.await()
        throw connectionDrop()
      }
    val second =
      ScriptedConn("c1") {
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    val model = RecordingLiveModel(first, second)
    val agent =
      LlmAgent(
        name = "live_agent",
        model = model,
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              if (request.contents.single().parts.single().text == "blocked") {
                CallbackChoice.Break(LlmResponse(content = refusal))
              } else {
                CallbackChoice.Continue(request)
              }
            }
          ),
      )
    var refusals = 0

    agent.runLive(liveContextFor(agent, queue)).collect { event ->
      if (event.content == refusal) {
        handling.complete(Unit)
        // Still handling the refusal while the receive side drops.
        delay(100)
        refusals++
      }
    }

    assertEquals(1, refusals)
    assertEquals(2, model.connectCalls)
  }

  @Test
  fun runLive_dropWhileTheCallerHandlesAnEvent_deliversItAndResumes() = runTest {
    // A drop must not cancel the event the caller is still handling, or the run cannot resume.
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hello"))
    val handling = CompletableDeferred<Unit>()
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        emit(LlmResponse(content = modelMessage("hi")))
        it.closed.await()
      }
    first.onSendContent = {
      handling.await()
      throw connectionDrop()
    }
    val resent = CompletableDeferred<Unit>()
    val secondSent = mutableListOf<String>()
    val second =
      ScriptedConn("c1") {
        resent.await()
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    second.onSendContent = { content ->
      secondSent += content.parts.mapNotNull { it.text }
      resent.complete(Unit)
    }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)
    val handled = mutableListOf<String>()

    agent.runLive(liveContextFor(agent, queue)).collect { event ->
      val text = event.content?.parts?.firstNotNullOfOrNull { it.text } ?: return@collect
      if (text == "hi") {
        handling.complete(Unit)
        // Still handling "hi" while the write drops.
        delay(100)
      }
      handled += text
    }

    assertEquals(listOf("hi"), handled)
    assertEquals(2, model.connectCalls)
    assertEquals(listOf("hello"), secondSent)
  }

  @Test
  fun runLive_callersEmptyResumptionHandle_stillSeedsTheHistory() = runBlocking {
    // An empty handle names no session to resume, so the run starts fresh and sends history.
    val connection = RecordingLiveConnection(listOf(turnComplete()))
    val agent = liveAgent(connection)
    val runConfig = RunConfig(sessionResumption = SessionResumptionConfig(handle = ""))

    agent
      .runLive(liveContextFor(agent, session = sessionWithEarlierMessage(), runConfig = runConfig))
      .toList()

    assertEquals(1, connection.sendHistoryCalls)
  }

  @Test
  fun runLive_partialContent_isNotRecordedInTheSession() = runBlocking {
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "partial")
    val connection = RecordingLiveConnection(listOf(turnComplete()))
    val agent = liveAgent(connection)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("half"), partial = true)
    queue.close()

    agent
      .runLive(
        liveContextFor(
          agent,
          queue,
          session = sessionService.createSession(key),
          sessionService = sessionService,
        )
      )
      .toList()

    assertEquals(listOf("half"), connection.sentTexts())
    assertEquals(
      0,
      checkNotNull(sessionService.getSession(key)).events.count { it.author == Role.USER },
    )
  }

  @Test
  fun runLive_toolAnswerFromTheCaller_isNotRecordedAsAUserTurn() = runBlocking {
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "tool-answer")
    val connection = RecordingLiveConnection(listOf(turnComplete()))
    val agent = liveAgent(connection)
    val queue = LiveRequestQueue()
    queue.sendContent(
      Content(
        parts =
          listOf(
            Part(functionResponse = FunctionResponse(name = "f", response = mapOf("r" to "ok")))
          )
      )
    )
    queue.close()

    agent
      .runLive(
        liveContextFor(
          agent,
          queue,
          session = sessionService.createSession(key),
          sessionService = sessionService,
        )
      )
      .toList()

    assertEquals(1, connection.sentContent.size)
    assertEquals(
      0,
      checkNotNull(sessionService.getSession(key)).events.count { it.author == Role.USER },
    )
  }

  @Test
  fun runLive_wrappedDropAfterTheOpen_isNotRetried() = runTest {
    // Only a failed open wraps its cause; a wrapped drop from a live connection is not a drop.
    val first =
      ScriptedConn("c0") {
        emit(handleUpdate("h-1"))
        throw IllegalStateException("wrapped", connectionDrop())
      }
    val second = ScriptedConn("c1") { it.closed.await() }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    assertFailsWith<IllegalStateException> { agent.runLive(liveContextFor(agent)).toList() }
    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_openCancelledWithADropCause_isNotRetried() = runBlocking {
    // A cancellation that carries a drop as its cause is still a cancellation, never a retry.
    val queue = LiveRequestQueue()
    val second = ScriptedConn("c1") { it.closed.await() }
    var connects = 0
    val model =
      object : Model {
        override val name = "live-model"

        override suspend fun connect(request: LlmRequest): LiveConnection {
          connects++
          if (connects == 1) {
            throw kotlinx.coroutines.CancellationException("open cancelled").apply {
              initCause(connectionDrop())
            }
          }
          // Reached only by a retry; closing the queue lets that run end on its own.
          queue.close()
          return second
        }

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
          flow {}
      }
    val agent = LlmAgent(name = "live_agent", model = model)
    val runConfig = RunConfig(sessionResumption = SessionResumptionConfig(handle = "h-1"))

    val outcome = runCatching {
      agent.runLive(liveContextFor(agent, queue, runConfig = runConfig)).toList()
    }

    assertTrue(outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    assertEquals(1, connects)
  }

  @Test
  fun runLive_dropWhileARealtimeInputsStateIsStored_sendsItOnTheResumedConnection() = runTest {
    val committed = CompletableDeferred<Unit>()
    val sessionService = CommitThenYieldSessionService({ it.author == Role.USER }, committed)
    val key = SessionKey("app", "user", "replay-realtime")
    val queue = LiveRequestQueue()
    queue.send(LiveRequest(RealtimeInput.ActivityStart, stateDelta = mapOf("k" to "v")))
    var firstSent = 0
    var secondSent = 0
    val first =
      ScriptedConn("c0") {
        committed.await()
        throw connectionDrop()
      }
    first.onSendRealtime = { firstSent++ }
    val second =
      ScriptedConn("c1") {
        emit(turnComplete())
        queue.close()
        it.closed.await()
      }
    second.onSendRealtime = { secondSent++ }
    val agent = LlmAgent(name = "live_agent", model = RecordingLiveModel(first, second))
    val context =
      liveContextFor(
        agent,
        queue,
        session = sessionService.createSession(key),
        sessionService = sessionService,
        runConfig = RunConfig(sessionResumption = SessionResumptionConfig(handle = "h-1")),
      )

    agent.runLive(context).toList()

    // The cancelled sender stops before writing to the dropped c0; the resumed c1 gets the input.
    assertEquals(0, firstSent)
    assertEquals(1, secondSent)
    assertEquals(
      1,
      checkNotNull(sessionService.getSession(key)).events.count { it.author == Role.USER },
    )
  }

  @Test
  fun runLive_goAwayAfterTheCallerHungUpAndEverythingWasSent_endsTheRun() = runTest {
    // The caller closed and the sender drained the queue, so a late go-away has nothing to resume.
    val queue = LiveRequestQueue()
    queue.close()
    val first =
      ScriptedConn("c0") {
        it.closed.await()
        emit(goAway())
      }
    val second = ScriptedConn("c1") { it.closed.await() }
    val model = RecordingLiveModel(first, second)
    val agent = LlmAgent(name = "live_agent", model = model)

    agent.runLive(liveContextFor(agent, queue)).toList()

    assertEquals(1, model.connectCalls)
  }

  @Test
  fun runLive_contextOnABranch_storesUserEventsWithoutABranch() = runBlocking {
    // Live user events carry no branch, as ADK Python's live user events do not.
    val sessionService = InMemorySessionService()
    val key = SessionKey("app", "user", "branch")
    val connection = RecordingLiveConnection(listOf(turnComplete()))
    val agent = liveAgent(connection)
    val queue = LiveRequestQueue()
    queue.sendContent(userMessage("hi"))
    queue.send(LiveRequest(stateDelta = mapOf("k" to "v")))
    queue.close()
    val context =
      InvocationContext(
          agent = agent,
          session = sessionService.createSession(key),
          sessionService = sessionService,
          branch = "root.child",
        )
        .apply { frameworkData.liveRequestQueue = queue }

    agent.runLive(context).toList()

    val userEvents =
      checkNotNull(sessionService.getSession(key)).events.filter { it.author == Role.USER }
    assertEquals(2, userEvents.size)
    assertTrue(userEvents.all { it.branch == null })
  }

  private class ScriptedTurnFailure : RuntimeException("the turn failed")

  private class TeardownFailure : RuntimeException("teardown failed")
}

/** The connections a live run opens before giving up: the first plus `MAX_RECONNECT_ATTEMPTS`. */
private const val MAX_LIVE_CONNECTS = 6
