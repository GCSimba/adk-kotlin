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
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStreamingToolsTest {

  /** A tool context for call [callId], on a throwaway invocation. */
  private fun streamingToolContext(callId: String) =
    ToolContext(
      invocationContext =
        InvocationContext(
          session = Session(key = SessionKey("app", "user", "s")),
          agent = LlmAgent(name = "agent", model = DummyModel("model")),
        ),
      functionCallId = callId,
    )

  /** The content a live run queues to answer call [callId] to `monitor`. */
  private fun answerTo(callId: String) =
    Content(
      Role.USER,
      listOf(
        Part(
          functionResponse =
            FunctionResponse(name = "monitor", id = callId, response = mapOf("status" to "x"))
        )
      ),
    )

  @Test
  fun onAnswersQueued_mayNotStart_neverStartsTheStream(): Unit = runBlocking {
    val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
    var started = false
    val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { started = true } }

    val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
    tools.onAnswersQueued(answerTo("c1"), mayStart = false)
    yield()
    assertEquals(listOf("c1"), tools.close().flatMap { it.parts }.map { it.functionResponse?.id })

    assertFalse(started, "a stream answered alongside a handover should never start")
  }

  @Test
  fun onAnswersQueued_callerHasHungUp_neverStartsTheStream(): Unit = runBlocking {
    val queue = LiveRequestQueue().apply { close() }
    val tools = LiveStreamingTools(queue, "agent", "invocation", coroutineContext)
    var started = false
    val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { started = true } }

    val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
    tools.onAnswersQueued(answerTo("c1"))
    yield()
    // Answered as pending by `run`, so `close` still returns a final answer though it never ran.
    assertEquals(listOf("c1"), tools.close().flatMap { it.parts }.map { it.functionResponse?.id })

    assertFalse(started, "a stream whose pending answer was dropped should never start")
  }

  @Test
  fun stopRunning_stopsTheStream_stopAllStillReturnsItsFinalAnswer(): Unit = runBlocking {
    val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
    val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }
    val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
    tools.onAnswersQueued(answerTo("c1"))

    tools.stopRunning()

    assertEquals(listOf("c1"), tools.stopAll().flatMap { it.parts }.map { it.functionResponse?.id })
    assertEquals(emptyList<Content>(), tools.close())
  }

  @Test
  fun onConnect_cancelsAStreamThatNeverStarted_closeStillReturnsItsFinalAnswer(): Unit =
    runBlocking {
      val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
      val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }

      val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
      tools.onConnect()

      assertEquals(listOf("c1"), tools.close().flatMap { it.parts }.map { it.functionResponse?.id })
    }

  @Test
  fun stopAll_streamWhoseCleanupThrows_sendsNoErrorAnswer(): Unit = runBlocking {
    val queue = LiveRequestQueue()
    val tools = LiveStreamingTools(queue, "agent", "invocation", coroutineContext)
    val stream = flow<String> { awaitCancellation() }.onCompletion { error("cleanup failed") }
    val tool = DummyTool(name = "monitor") { _, _ -> stream }
    val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
    tools.onAnswersQueued(answerTo("c1"))
    yield()
    // Its stop still records a final answer; only the queue must stay empty.
    assertEquals(listOf("c1"), tools.close().flatMap { it.parts }.map { it.functionResponse?.id })
    queue.close()

    assertNull(queue.receiveRequest(), "a stream that was stopped sent an answer")
  }

  @Test
  fun stopAll_streamRunning_leavesNoStreamToStop(): Unit = runBlocking {
    val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
    val tool = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }
    val stop = DummyTool(name = "stop_streaming") { _, _ -> "ran" }

    val unused = tools.run(tool, streamingToolContext("c1"), emptyMap())
    tools.onAnswersQueued(answerTo("c1"))
    assertEquals(1, tools.stopAll().size)
    val answer = tools.run(stop, streamingToolContext("s1"), mapOf("function_name" to "monitor"))
    assertEquals(0, tools.close().size)

    assertEquals(mapOf("status" to "No active streaming function named monitor found"), answer)
  }

  @Test
  fun run_stopStreaming_stopsOnlyTheNamedTool(): Unit = runBlocking {
    val tools = LiveStreamingTools(LiveRequestQueue(), "agent", "invocation", coroutineContext)
    val monitor = DummyTool(name = "monitor") { _, _ -> flow<String> { awaitCancellation() } }
    val news = DummyTool(name = "news") { _, _ -> flow<String> { awaitCancellation() } }
    val stop = DummyTool(name = "stop_streaming") { _, _ -> "ran" }

    for ((tool, callId) in listOf(monitor to "c1", news to "n1")) {
      val unused = tools.run(tool, streamingToolContext(callId), emptyMap())
      tools.onAnswersQueued(answerTo(callId))
    }
    val answer = tools.run(stop, streamingToolContext("s1"), mapOf("function_name" to "monitor"))
    // Only news is still streaming when the run ends.
    assertEquals(1, tools.close().size)

    assertEquals(mapOf("status" to "Successfully stopped streaming function monitor"), answer)
  }
}
