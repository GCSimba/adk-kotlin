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

package com.google.adk.kt.examples.springboot;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.examples.springboot.SupportService.AgentMessage.Type;
import com.google.adk.kt.interop.CompletableFutureSessionService;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlinx.coroutines.slf4j.MDCContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

/**
 * The service that answers customers with the ADK. {@link #answer} returns the finished reply, and
 * {@link #streamAnswer} streams progress updates and the reply while the workflow runs.
 */
@Service
class SupportService {

  /** The user ID of every session; an app with sign-in would use the caller's ID instead. */
  static final String USER_ID = "anonymous";

  private final PublisherRunner runner;
  private final CompletableFutureSessionService sessionService;

  SupportService(PublisherRunner runner, CompletableFutureSessionService sessionService) {
    this.runner = runner;
    this.sessionService = sessionService;
  }

  /** The reply to a customer. */
  record SupportReply(String text) {}

  /** A streamed message: a progress update from the workflow, or the reply. */
  record AgentMessage(Type type, String text) {
    /** What a streamed message carries. */
    enum Type {
      @JsonProperty("progress")
      PROGRESS,
      @JsonProperty("reply")
      REPLY
    }
  }

  /** Exception for a run that ends without a reply, such as when the model blocks one. */
  static final class AgentFailedException extends RuntimeException {
    AgentFailedException(String message) {
      super(message);
    }
  }

  /**
   * Runs the workflow on {@code message} and emits the reply, the output of its {@code reply} node.
   */
  Mono<SupportReply> answer(String message) {
    return run(message)
        .mapNotNull(SupportService::replyText)
        .switchIfEmpty(Mono.error(() -> new AgentFailedException("The run ended without a reply.")))
        .last()
        .map(SupportReply::new);
  }

  /**
   * Runs the workflow on {@code message}, emitting the progress messages its nodes publish and then
   * the {@code reply} node's output as the reply.
   */
  Flux<AgentMessage> streamAnswer(String message) {
    // Builds the run here, not in defer, so it copies the MDC of the thread that calls this method.
    Flux<Event> events = run(message);
    return Flux.defer(
        () -> {
          AtomicBoolean replied = new AtomicBoolean();
          return events
              .handle(SupportService::toMessage)
              .doOnNext(
                  streamed -> {
                    if (streamed.type() == Type.REPLY) {
                      replied.set(true);
                    }
                  })
              // Without the reply node's output there is no reply, even after progress.
              .concatWith(
                  Mono.defer(
                      () ->
                          replied.get()
                              ? Mono.empty()
                              : Mono.error(
                                  new AgentFailedException("The run ended without a reply."))));
        });
  }

  /**
   * Runs the workflow on {@code message} in a new session that is deleted once the run ends. Fails
   * the run on an event with an error code.
   */
  private Flux<Event> run(String message) {
    Content content = Content.fromText(Role.USER, message);
    // Copies this thread's SLF4J MDC so the run's model and tool calls on other threads keep it.
    MDCContext mdc = new MDCContext();
    return Flux.usingWhen(
        createSession(),
        session ->
            Flux.from(
                    runner.runAsync(
                        USER_ID,
                        Objects.requireNonNull(
                            session.getKey().getId(), "The new session has no ID."),
                        /* invocationId= */ null,
                        content,
                        /* stateDelta= */ null,
                        /* runConfig= */ null,
                        mdc))
                .handle(SupportService::failOnError),
        // Deletes the session even if the run fails or the client disconnects.
        session -> deleteSession(session.getKey()));
  }

  /** Fails the run on an event with an error code and forwards every other event. */
  private static void failOnError(Event event, SynchronousSink<Event> sink) {
    // The ADK emits an error event for a failed model call or node before any exception, so fail
    // on the first one.
    if (event.getErrorCode() != null) {
      sink.error(
          new AgentFailedException(
              "The " + nodeName(event) + " node failed with " + event.getErrorCode() + "."));
    } else {
      sink.next(event);
    }
  }

  /** Passes on the reply, or the progress that a node publishes as a partial message. */
  private static void toMessage(Event event, SynchronousSink<AgentMessage> sink) {
    String reply = replyText(event);
    if (reply != null) {
      sink.next(new AgentMessage(Type.REPLY, reply));
    } else if (event.getPartial() && !event.contentText().isEmpty()) {
      // Only node progress is partial: nodes publish partial messages, and no model streams.
      sink.next(new AgentMessage(Type.PROGRESS, event.contentText()));
    }
  }

  /** Returns the reply on the {@code reply} node's output event, or null on any other event. */
  private static @Nullable String replyText(Event event) {
    return SupportWorkflowConfiguration.REPLY.equals(event.getNodeName())
            && event.getOutput() instanceof String reply
        ? reply
        : null;
  }

  /** Returns the name of the node that emitted {@code event}, or an empty string outside a node. */
  private static String nodeName(Event event) {
    return Objects.requireNonNullElse(event.getNodeName(), "");
  }

  /**
   * Creates a new session on each subscription; the supplier form of {@code fromFuture} defers the
   * call until then.
   */
  private Mono<Session> createSession() {
    SessionKey key = new SessionKey(runner.getAppName(), USER_ID, /* id= */ null);
    return Mono.fromFuture(() -> sessionService.createSession(key));
  }

  private Mono<Void> deleteSession(SessionKey key) {
    return Mono.fromFuture(() -> sessionService.deleteSession(key));
  }
}
