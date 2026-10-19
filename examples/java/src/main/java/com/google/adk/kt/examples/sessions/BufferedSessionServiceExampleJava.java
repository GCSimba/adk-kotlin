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
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.BufferedSessionService;
import com.google.adk.kt.sessions.ListEventsResponse;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionException;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.sessions.VertexAiSessionService;
import com.google.adk.kt.tools.BaseTool;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.FunctionResponse;
import com.google.adk.kt.types.Role;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import kotlinx.coroutines.Dispatchers;
import org.reactivestreams.Publisher;

/**
 * This example shows how {@link BufferedSessionService} writes session events in the background, so
 * that a turn does not wait for the session store after each event.
 *
 * <p>Use {@link BufferedSessionService} if all of these conditions are true:
 *
 * <ul>
 *   <li>Writes to the session store are slow, for example network calls to Vertex AI.
 *   <li>Your turns have many events before the final answer, for example tool calls and tool
 *       results.
 *   <li>You accept these risks:
 *       <ul>
 *         <li>If your app crashes during a turn, you lose the events of that turn that Vertex AI
 *             does not have yet. The runner shows a final answer only after Vertex AI has it.
 *         <li>If a write fails after Vertex AI stored the event, the retry can store the event two
 *             times.
 *       </ul>
 * </ul>
 *
 * <p>Do not use {@link BufferedSessionService} with a fast store, for example {@code
 * InMemorySessionService}. With a fast store, a turn does not wait for writes.
 *
 * <p>{@link BufferedSessionService} works with one or more instances of your app. If you cancel a
 * turn, its writes continue, and they can reach Vertex AI after the next turn starts on another
 * instance.
 *
 * <p>To see the same store without {@link BufferedSessionService}, refer to {@code
 * VertexAiSessionServiceExampleJava}.
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
 */
public final class BufferedSessionServiceExampleJava {

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

    // Make one BufferedSessionService for each instance of your app. Use it for all turns.
    BufferedSessionService sessionService =
        new BufferedSessionService(
            vertex,
            // Writes run in this context. If you cancel a turn, its writes continue.
            Dispatchers.getDefault());
    // Give sessionService to all components. A direct read from vertex does not wait for writes.
    PublisherRunner runner =
        PublisherRunner.of(
            InMemoryRunner.builder()
                .agent(travelAgent(project))
                .appName(APP_NAME)
                .sessionService(sessionService)
                .build());

    try {
      // Make a new session. Vertex AI selects the ID, because the key has no ID.
      Session session =
          AsyncJavaHelpers.await(
              c -> sessionService.createSession(new SessionKey(APP_NAME, USER_ID, null), null, c));
      SessionKey key = session.getKey();

      // sessionService writes the events in the background. The answer waits for all of them.
      chat(runner, key, "What's the weather in Tokyo tomorrow?");

      // listEvents waits for the writes of the session. Then it reads the events from Vertex AI.
      ListEventsResponse stored = AsyncJavaHelpers.await(c -> sessionService.listEvents(key, c));
      System.out.println(
          "Vertex AI has " + stored.getEvents().size() + " events for this session.");
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

  /**
   * Returns a travel agent with one tool. Each tool call and each tool result is one more event.
   */
  private static BaseAgent travelAgent(String project) {
    return LlmAgent.builder()
        .name("travel_agent")
        .description("Answers travel questions.")
        .model(
            new Gemini(
                MODEL_NAME,
                VertexCredentials.builder().project(project).location("global").build()))
        .instruction("Use the getForecast tool for weather questions. Answer briefly.")
        .tools(GET_FORECAST)
        .build();
  }

  /** The tool of the agent. {@link ReflectiveTools} makes a tool from {@code getForecast}. */
  static final class ForecastTools {
    private static final List<String> CONDITIONS = List.of("sunny", "cloudy", "rainy", "windy");

    @Tool(name = "getForecast", description = "Gets the weather forecast for tomorrow in a city.")
    public Map<String, Object> getForecast(
        @Param(name = "city", description = "The city.") String city) {
      ThreadLocalRandom random = ThreadLocalRandom.current();
      return Map.of(
          "city",
          city,
          "temperatureCelsius",
          random.nextInt(-5, 35),
          "condition",
          CONDITIONS.get(random.nextInt(CONDITIONS.size())));
    }
  }

  // Reflection is slow, so make the tool one time.
  private static final BaseTool GET_FORECAST =
      ReflectiveTools.fromMethod(new ForecastTools(), "getForecast");

  /** Sends {@code message} in the session {@code key}, and prints each event of the turn. */
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
          if (event.getPartial()) {
            return;
          }
          for (FunctionCall call : event.functionCalls()) {
            System.out.println(event.getAuthor() + " > [calls " + call.getName() + "]");
          }
          for (FunctionResponse response : event.functionResponses()) {
            System.out.println(event.getAuthor() + " > [" + response.getName() + " returned]");
          }
          String text = event.contentText(" ");
          if (!text.isBlank()) {
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

  private BufferedSessionServiceExampleJava() {}
}
