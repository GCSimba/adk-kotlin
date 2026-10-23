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

package com.google.adk.kt.examples.sessions

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.sessions.BufferedSessionService
import com.google.adk.kt.sessions.SessionException
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.VertexAiSessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.8-flash"
private const val APP_NAME = "travel-app"
private const val USER_ID = "demo-user"

/**
 * This example shows how [BufferedSessionService] writes session events in the background, so that
 * a turn does not wait for the session store after each event.
 *
 * Use [BufferedSessionService] if all of these conditions are true:
 * - Writes to the session store are slow, for example network calls to Vertex AI.
 * - Your turns have many events before the final answer, for example tool calls and tool results.
 * - You accept these risks:
 *     - If your app crashes during a turn, you lose the events of that turn that Vertex AI does not
 *       have yet. The runner shows a final answer only after Vertex AI has it.
 *     - If a write fails after Vertex AI stored the event, the retry can store the event two times.
 *
 * Do not use [BufferedSessionService] with a fast store, for example `InMemorySessionService`. With
 * a fast store, a turn does not wait for writes.
 *
 * [BufferedSessionService] works with one or more instances of your app. If you cancel a turn, its
 * writes continue, and they can reach Vertex AI after the next turn starts on another instance.
 *
 * To see the same store without [BufferedSessionService], refer to `VertexAiSessionServiceExample`.
 *
 * Before you run the example:
 * 1. Log in with `gcloud auth application-default login`.
 * 2. Set `GOOGLE_CLOUD_PROJECT` to your Google Cloud project ID.
 * 3. Set `VERTEX_REASONING_ENGINE_ID` to the numeric ID of your reasoning engine.
 * 4. Optional: Set `GOOGLE_CLOUD_LOCATION` to the region of your reasoning engine. The default is
 *    `us-central1`.
 */
fun main() {
  val project = requireEnv("GOOGLE_CLOUD_PROJECT")
  val vertex =
    VertexAiSessionService(
      project = project,
      location =
        System.getenv("GOOGLE_CLOUD_LOCATION")?.takeUnless { it.isBlank() } ?: "us-central1",
      reasoningEngineId = requireEnv("VERTEX_REASONING_ENGINE_ID"),
    )

  // Make one BufferedSessionService for each instance of your app. Use it for all turns.
  val sessionService =
    BufferedSessionService(
      delegate = vertex,
      // Writes run in this context. If you cancel a turn, its writes continue.
      context = Dispatchers.Default,
    )
  // Give sessionService to all components. A direct read from vertex does not wait for writes.
  val runner =
    InMemoryRunner(
      agent = travelAgent(project),
      appName = APP_NAME,
      sessionService = sessionService,
    )

  try {
    runBlocking {
      // Make a new session. Vertex AI selects the ID, because the key has no ID.
      val key = sessionService.createSession(SessionKey(APP_NAME, USER_ID, id = null)).key

      // sessionService writes the events in the background. The answer waits for all of them.
      runner.chat(key, "What's the weather in Tokyo tomorrow?")

      // listEvents waits for the writes of the session. Then it reads the events from Vertex AI.
      val stored = sessionService.listEvents(key).events
      println("Vertex AI has ${stored.size} events for this session.")
    }
  } catch (e: SessionException) {
    // If a write fails after all retries, the turn stops with this exception.
    println("Vertex AI did not store the conversation: ${e.message}")
  } finally {
    try {
      runner.close()
    } finally {
      // Call close() at shutdown. It waits for all writes, and throws for unreported failures.
      sessionService.close()
    }
  }
  // The model's HTTP client keeps the JVM alive for a minute after its last call.
  exitProcess(0)
}

// The code below is not specific to BufferedSessionService.

/** Returns a travel agent with one tool. Each tool call and each tool result is one more event. */
private fun travelAgent(project: String): LlmAgent =
  LlmAgent(
    name = "travel_agent",
    description = "Answers travel questions.",
    model =
      Gemini(
        name = MODEL_NAME,
        vertexCredentials = VertexCredentials(project = project, location = "global"),
      ),
    instruction = Instruction("Use the getForecast tool for weather questions. Answer briefly."),
    tools = ForecastTools().generatedTools(),
  )

/** The tool of the agent. The `@Tool` KSP processor makes a tool from `getForecast`. */
class ForecastTools {
  /** Gets the weather forecast for tomorrow in a city. */
  @Tool
  fun getForecast(@Param("The city.") city: String): Map<String, Any> =
    mapOf(
      "city" to city,
      "temperatureCelsius" to Random.nextInt(-5, 35),
      "condition" to listOf("sunny", "cloudy", "rainy", "windy").random(),
    )
}

/** Sends [message] in the session [key], and prints each event of the turn. */
private suspend fun Runner.chat(key: SessionKey, message: String) {
  println("user > $message")
  runAsync(
      userId = key.userId,
      sessionId = checkNotNull(key.id),
      newMessage = Content.fromText(Role.USER, message),
    )
    .collect { event ->
      if (event.partial) return@collect
      event.functionCalls().forEach { println("${event.author} > [calls ${it.name}]") }
      event.functionResponses().forEach { println("${event.author} > [${it.name} returned]") }
      val text = event.contentText(" ")
      if (text.isNotBlank()) println("${event.author} > $text")
    }
}

private fun requireEnv(name: String): String =
  System.getenv(name)?.takeUnless { it.isBlank() }
    ?: error("Environment variable $name is not set.")
