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
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.sessions.BufferedSessionService
import com.google.adk.kt.sessions.BufferedSessionServiceConfig
import com.google.adk.kt.sessions.SessionCacheConfig
import com.google.adk.kt.sessions.SessionException
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.VertexAiSessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTimedValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.8-flash"
private const val APP_NAME = "travel-app"
private const val USER_ID = "demo-user"

/**
 * This example shows how the read cache of [BufferedSessionService] lets a turn read the session
 * from memory, not from Vertex AI. On Vertex AI, each read from memory saves two network calls.
 *
 * Use the cache only if all of these conditions are true:
 * - The conditions in `BufferedSessionServiceExample` are true. The cache is part of
 *   [BufferedSessionService].
 * - One instance of your app always serves a session: you run one instance, or you use session
 *   affinity. If another instance serves the session, this instance can use an old copy of the
 *   session until the copy expires.
 * - Only this [BufferedSessionService] changes the sessions and their `user:` and `app:` state.
 * - Your conversations have more than one turn. The first turn always reads from Vertex AI. If the
 *   runner makes the session, the second turn also reads from Vertex AI.
 *
 * For the other limits of the cache, refer to [SessionCacheConfig].
 *
 * Before you run the example:
 * 1. Log in with `gcloud auth application-default login`.
 * 2. Set `GOOGLE_CLOUD_PROJECT` to your Google Cloud project ID.
 * 3. Set `VERTEX_REASONING_ENGINE_ID` to the numeric ID of your reasoning engine.
 * 4. Optional: Set `GOOGLE_CLOUD_LOCATION` to the region of your reasoning engine. The default is
 *    `us-central1`.
 *
 * The example prints output like this. The answers and the times change from run to run:
 * ```
 * user > I'm flying to Tokyo tomorrow for a week.
 * travel_agent > <answer>
 * user > Should I pack an umbrella?
 * travel_agent > <answer>
 * Cached copy: 4 events in <time>. Vertex AI: 4 events in <time>.
 * ```
 *
 * The last line shows that the cache works. The read from the cache makes no network call, so it
 * takes less time than the read from Vertex AI.
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

  // Use the cache only if all the conditions at the top of this file are true.
  val config =
    BufferedSessionServiceConfig(
      cacheConfig =
        SessionCacheConfig(
          // A copy stays valid for this time after the cache reads it from Vertex AI.
          ttl = 5.minutes,
          // Set it to the number of conversations that one instance serves at one time.
          maxSessions = 1024,
        )
    )
  // Make one BufferedSessionService for each instance of your app. Use it for all turns.
  val sessionService =
    BufferedSessionService(
      delegate = vertex,
      // Writes run in this context. If you cancel a turn, its writes continue.
      context = Dispatchers.Default,
      config = config,
    )
  // Give sessionService to all components. The cache does not see changes made through vertex.
  val runner =
    InMemoryRunner(
      agent = travelAgent(project),
      appName = APP_NAME,
      sessionService = sessionService,
    )

  try {
    runBlocking {
      // Make the session before the first turn, so that the first turn puts it in the cache.
      val key = sessionService.createSession(SessionKey(APP_NAME, USER_ID, id = null)).key

      // The first turn reads the session from Vertex AI. The cache keeps a copy.
      runner.chat(key, "I'm flying to Tokyo tomorrow for a week.")
      // The second turn reads the copy. The copy has the events of the first turn.
      runner.chat(key, "Should I pack an umbrella?")

      // getSession returns the copy. listEvents waits for the writes, then reads Vertex AI.
      val (cached, cacheTime) =
        measureTimedValue { checkNotNull(sessionService.getSession(key)).events }
      val (stored, storeTime) = measureTimedValue { sessionService.listEvents(key).events }
      println(
        "Cached copy: ${cached.size} events in $cacheTime. " +
          "Vertex AI: ${stored.size} events in $storeTime."
      )
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

/** Returns a travel agent. Each turn has two events: the user message and the answer. */
private fun travelAgent(project: String): LlmAgent =
  LlmAgent(
    name = "travel_agent",
    description = "Helps plan trips.",
    model =
      Gemini(
        name = MODEL_NAME,
        vertexCredentials = VertexCredentials(project = project, location = "global"),
      ),
    instruction = Instruction("Help the user plan their trip. Answer briefly."),
  )

/** Sends [message] in the session [key], and prints the answer. */
private suspend fun Runner.chat(key: SessionKey, message: String) {
  println("user > $message")
  runAsync(
      userId = key.userId,
      sessionId = checkNotNull(key.id),
      newMessage = Content.fromText(Role.USER, message),
    )
    .collect { event ->
      val text = event.contentText(" ")
      if (!event.partial && text.isNotBlank()) println("${event.author} > $text")
    }
}

private fun requireEnv(name: String): String =
  System.getenv(name)?.takeUnless { it.isBlank() }
    ?: error("Environment variable $name is not set.")
