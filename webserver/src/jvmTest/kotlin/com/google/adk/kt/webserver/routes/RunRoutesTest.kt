/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.webserver.AdkServerConfig
import com.google.adk.kt.webserver.adkApiModule
import com.google.common.truth.Truth.assertThat
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Serves the run routes from Netty instead of the test engine. Netty handles each call on a single
 * event-loop thread that a blocking tool would hold.
 */
@RunWith(JUnit4::class)
class RunRoutesTest {
  private val bothArrived = CountDownLatch(2)
  private val metTheOtherCall = CopyOnWriteArrayList<Boolean>()
  private lateinit var server: EmbeddedServer<*, *>
  private var port = 0

  @Before
  fun startServer() {
    val agent =
      LlmAgent(
        name = "agent",
        model = TwoParallelCallsModel(),
        tools = listOf(WaitForOtherCallTool()),
      )
    server =
      embeddedServer(Netty, port = EPHEMERAL_PORT, host = LOOPBACK) {
        adkApiModule(AdkServerConfig.inMemory(App(appName = APP_NAME, rootAgent = agent)))
      }
    server.start(wait = false)
    port = runBlocking { server.engine.resolvedConnectors().first().port }
  }

  @After
  fun stopServer() {
    server.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
  }

  @Test
  fun run_parallelBlockingToolCalls_runConcurrently() {
    val request =
      HttpRequest.newBuilder(URI("http://$LOOPBACK:$port/run"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(RUN_BODY))
        .build()

    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

    assertThat(response.statusCode()).isEqualTo(200)
    assertThat(metTheOtherCall).containsExactly(true, true)
  }

  /** Blocks, as a synchronous tool does, until the other parallel call arrives too. */
  private inner class WaitForOtherCallTool :
    BaseTool(TOOL_NAME, "Waits for the other parallel call.") {
    override fun declaration() = FunctionDeclaration(name = name, description = description)

    @Suppress("SuspendBlocks") // Blocks on purpose to stand in for a synchronous tool.
    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
      bothArrived.countDown()
      metTheOtherCall += bothArrived.await(WAIT_MILLIS, MILLISECONDS)
      return mapOf("status" to "ok")
    }
  }

  /** Calls the tool twice in one turn, then answers. */
  private class TwoParallelCallsModel : Model {
    override val name = "scripted"
    private var calls = 0

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
      flowOf(if (calls++ == 0) TWO_CALLS else ANSWER)
  }

  private companion object {
    /** Port 0 binds an ephemeral port, which the engine reports once it is up. */
    const val EPHEMERAL_PORT = 0
    const val LOOPBACK = "127.0.0.1"
    const val APP_NAME = "app"
    const val TOOL_NAME = "wait_for_other"
    const val RUN_BODY =
      """{"appName":"$APP_NAME","userId":"u","sessionId":"s","newMessage":{"role":"user","parts":[{"text":"go"}]}}"""
    const val WAIT_MILLIS = 5_000L
    const val STOP_GRACE_MILLIS = 500L
    const val STOP_TIMEOUT_MILLIS = 2000L

    val TWO_CALLS =
      LlmResponse(
        content =
          Content(
            role = Role.MODEL,
            parts =
              listOf(
                Part(functionCall = FunctionCall(name = TOOL_NAME, args = emptyMap(), id = "c1")),
                Part(functionCall = FunctionCall(name = TOOL_NAME, args = emptyMap(), id = "c2")),
              ),
          )
      )
    val ANSWER =
      LlmResponse(content = Content(role = Role.MODEL, parts = listOf(Part(text = "done"))))
  }
}
