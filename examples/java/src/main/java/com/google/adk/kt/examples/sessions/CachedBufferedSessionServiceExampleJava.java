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

package com.google.adk.kt.examples.sessions;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.BufferedSessionService;
import com.google.adk.kt.sessions.BufferedSessionServiceConfig;
import com.google.adk.kt.sessions.ListEventsResponse;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionCacheConfig;
import com.google.adk.kt.sessions.SessionException;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.sessions.VertexAiSessionService;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import java.time.Duration;
import java.util.Objects;
import kotlinx.coroutines.Dispatchers;
import org.reactivestreams.Publisher;

/**
 * This example shows how the read cache of {@link BufferedSessionService} lets a turn read the
 * session from memory, not from Vertex AI. On Vertex AI, each read from memory saves two network
 * calls.
 *
 * <p>Use the cache only if all of these conditions are true:
 *
 * <ul>
 *   <li>The conditions in {@code BufferedSessionServiceExampleJava} are true. The cache is part of
 *       {@link BufferedSessionService}.
 *   <li>One instance of your app always serves a session: you run one instance, or you use session
 *       affinity. If another instance serves the session, this instance can use an old copy of the
 *       session until the copy expires.
 *   <li>Only this {@link BufferedSessionService} changes the sessions and their {@code user:} and
 *       {@code app:} state.
 *   <li>Your conversations have more than one turn. The first turn always reads from Vertex AI. If
 *       the runner makes the session, the second turn also reads from Vertex AI.
 * </ul>
 *
 * <p>For the other limits of the cache, refer to {@link SessionCacheConfig}.
 *
 * <p>Before you run the example:
 *
 * <ol>
 *   <li>Log in with {@code gcloud auth application-default login}.
 *   <li>Set {@code GOOGLE_CLOUD_PROJECT} to your Google Cloud project ID.
 *   <li>Set {@code VERTEX_REASONING_ENGINE_ID} to the numeric ID of your reasoning engine.
 *   <li>Optional: Set {@code GOOGLE_CLOUD_LOCATION} to the region of your reasoning engine. The
 *       default is {@code us-central1}.
 * </ol>
 *
 * <p>The example prints output like this. The answers and the times change from run to run:
 *
 * <pre>{@code
 * user > I'm flying to Tokyo tomorrow for a week.
 * travel_agent > <answer>
 * user > Should I pack an umbrella?
 * travel_agent > <answer>
 * Cached copy: 4 events in <time> ms. Vertex AI: 4 events in <time> ms.
 * }</pre>
 *
 * <p>The last line shows that the cache works. The read from the cache makes no network call, so it
 * takes less time than the read from Vertex AI.
 */
public final class CachedBufferedSessionServiceExampleJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";
  private static final String APP_NAME = "travel-app";
  private static final String USER_ID = "demo-user";

  public static void main(String[] args) {
    String project = requireEnv("GOOGLE_CLOUD_PROJECT");
    String location = System.getenv("GOOGLE_CLOUD_LOCATION");
    VertexAiSessionService vertex =
        VertexAiSessionService.builder()
            .project(project)
            .location(location == null || location.isBlank() ? "us-central1" : location)
            .reasoningEngineId(requireEnv("VERTEX_REASONING_ENGINE_ID"))
            .build();

    // Use the cache only if all the conditions at the top of this file are true.
    BufferedSessionServiceConfig config =
        BufferedSessionServiceConfig.builder()
            .cacheConfig(
                SessionCacheConfig.builder()
                    // A copy stays valid for this time after the cache reads it from Vertex AI.
                    .ttlMillis(Duration.ofMinutes(5).toMillis())
                    // Set it to the number of conversations that one instance serves at one time.
                    .maxSessions(1024)
                    .build())
            .build();
    // Make one BufferedSessionService for each instance of your app. Use it for all turns.
    BufferedSessionService sessionService =
        new BufferedSessionService(
            vertex,
            // Writes run in this context. If you cancel a turn, its writes continue.
            Dispatchers.getDefault(),
            config);
    // Give sessionService to all components. The cache does not see changes made through vertex.
    PublisherRunner runner =
        PublisherRunner.of(
            InMemoryRunner.builder()
                .agent(travelAgent(project))
                .appName(APP_NAME)
                .sessionService(sessionService)
                .build());

    try {
      // Make the session before the first turn, so that the first turn puts it in the cache.
      Session session =
          AsyncJavaHelpers.await(
              c -> sessionService.createSession(new SessionKey(APP_NAME, USER_ID, null), null, c));
      SessionKey key = session.getKey();

      // The first turn reads the session from Vertex AI. The cache keeps a copy.
      chat(runner, key, "I'm flying to Tokyo tomorrow for a week.");
      // The second turn reads the copy. The copy has the events of the first turn.
      chat(runner, key, "Should I pack an umbrella?");

      // getSession returns the copy. listEvents waits for the writes, then reads Vertex AI.
      long cacheStart = System.nanoTime();
      Session cached = AsyncJavaHelpers.await(c -> sessionService.getSession(key, null, c));
      double cacheMillis = (System.nanoTime() - cacheStart) / 1e6;
      long storeStart = System.nanoTime();
      ListEventsResponse stored = AsyncJavaHelpers.await(c -> sessionService.listEvents(key, c));
      double storeMillis = (System.nanoTime() - storeStart) / 1e6;
      System.out.printf(
          "Cached copy: %d events in %.3f ms. Vertex AI: %d events in %.3f ms.%n",
          Objects.requireNonNull(cached).getEvents().size(),
          cacheMillis,
          stored.getEvents().size(),
          storeMillis);
    } catch (SessionException e) {
      // If a write fails after all retries, the turn stops with this exception.
      System.out.println("Vertex AI did not store the conversation: " + e.getMessage());
    } finally {
      try {
        runner.close();
      } finally {
        // Call close() at shutdown. It waits for all writes, and throws for unreported failures.
        sessionService.close();
      }
    }
    // The model's HTTP client keeps the JVM alive for a minute after its last call.
    System.exit(0);
  }

  // The code below is not specific to BufferedSessionService.

  /** Returns a travel agent. Each turn has two events: the user message and the answer. */
  private static BaseAgent travelAgent(String project) {
    return LlmAgent.builder()
        .name("travel_agent")
        .description("Helps plan trips.")
        .model(
            new Gemini(
                MODEL_NAME,
                VertexCredentials.builder().project(project).location("global").build()))
        .instruction("Help the user plan their trip. Answer briefly.")
        .build();
  }

  /** Sends {@code message} in the session {@code key}, and prints the answer. */
  private static void chat(PublisherRunner runner, SessionKey key, String message) {
    System.out.println("user > " + message);
    Publisher<Event> events =
        runner.runAsync(
            key.getUserId(),
            Objects.requireNonNull(key.getId()),
            null,
            Content.fromText(Role.USER, message));
    AsyncJavaHelpers.forEach(
        events,
        event -> {
          String text = event.contentText(" ");
          if (!event.getPartial() && !text.isBlank()) {
            System.out.println(event.getAuthor() + " > " + text);
          }
        });
  }

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Environment variable " + name + " is not set.");
    }
    return value;
  }

  private CachedBufferedSessionServiceExampleJava() {}
}
