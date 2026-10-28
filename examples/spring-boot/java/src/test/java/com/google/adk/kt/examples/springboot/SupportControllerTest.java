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

import static com.google.common.truth.Truth.assertThat;
import static java.util.stream.Collectors.joining;

import com.google.adk.kt.examples.springboot.SupportService.AgentMessage;
import com.google.adk.kt.examples.springboot.SupportService.AgentMessage.Type;
import com.google.adk.kt.examples.springboot.SupportService.SupportReply;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.interop.CompletableFutureSessionService;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FinishReason;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Offline tests of both support endpoints, with a scripted model in place of Gemini. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SupportControllerTest {

  private static final String WHERE_IS_1042 = "Where is my order 1042?";
  private static final String TRACKING_REPLY = "Tracking says: Out for delivery";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String REQUEST_ID = "requestId";

  @TestBean Model model;

  @Autowired WebTestClient client;

  @Autowired SupportService supportService;

  @Autowired PublisherRunner runner;

  @Autowired CompletableFutureSessionService sessionService;

  @Autowired OrderService orderService;

  @Value("${local.server.port}")
  int port;

  /**
   * Supplies the scripted model in place of Gemini; {@link TestBean} finds this method by the
   * field's name.
   */
  static Model model() {
    return new ScriptedModel();
  }

  @Test
  void answer_sendsADeliveryQuestionToTheShippingAgent_whichTracksTheOrder() {
    assertThat(answer(WHERE_IS_1042)).isEqualTo(new SupportReply(TRACKING_REPLY));
  }

  @Test
  void answer_sendsAnApprovedRefundToTheRefundsAgent_whichCreatesALabel() {
    assertThat(answer("Please refund order 1043."))
        .isEqualTo(new SupportReply("Refund approved. Return label RL-1043."));
  }

  @Test
  void answer_deniesARefundInCode() {
    // The refund_denied node writes these replies; the refunds agent never runs.
    assertThat(answer("Please refund order 1044."))
        .isEqualTo(
            new SupportReply(
                "Your refund for order 1044 (running shoes) was denied, because the order was"
                    + " delivered more than 30 days ago."));
    assertThat(answer("Please refund order 1042."))
        .isEqualTo(
            new SupportReply(
                "Your refund for order 1042 (noise-cancelling headphones) was denied, because the"
                    + " order has not been delivered yet."));
  }

  @Test
  void createReturnLabel_refusesAnOrderThePolicyDenies() {
    assertThat(orderService.createReturnLabel("1043")).containsEntry("labelNumber", "RL-1043");
    assertThat(orderService.createReturnLabel("1044")).containsKey("error");
    assertThat(orderService.createReturnLabel("1042")).containsKey("error");
    assertThat(orderService.createReturnLabel("9999")).containsKey("error");
  }

  @Test
  void answer_withoutAnOrderToActOn_asksForTheOrderNumber() {
    for (String message :
        List.of(
            "Hi, can you help me?",
            "Where is my order 9999?",
            "Can I change the address on order 1042?")) {
      assertThat(answer(message).text()).contains("order number");
    }
  }

  @Test
  void streamAnswer_withoutAnOrderToActOn_sendsOnlyTheReply() {
    for (String message :
        List.of(
            "Hi, can you help me?",
            "Where is my order 9999?",
            "Can I change the address on order 1042?")) {
      List<AgentMessage> messages = streamMessages(message);

      // Triage's answer stays internal, so the stream carries only the reply.
      assertThat(messages.stream().map(AgentMessage::type).toList()).containsExactly(Type.REPLY);
      assertThat(messages.get(0).text()).contains("order number");
    }
  }

  @Test
  void streamAnswer_sendsTheReply() {
    // The Java nodes publish no progress, so the stream carries only the reply.
    assertThat(streamMessages(WHERE_IS_1042))
        .containsExactly(new AgentMessage(Type.REPLY, TRACKING_REPLY));
  }

  @Test
  void answer_deletesItsSessionWhenTheRunEnds() {
    answer(WHERE_IS_1042);

    assertThat(storedSessions()).isEmpty();
  }

  @Test
  void answer_whenTheReplyIsBlocked_answers502AndDeletesItsSession() {
    post("/support", WHERE_IS_1042 + " [blocked]").expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

    assertThat(storedSessions()).isEmpty();
  }

  @Test
  void answer_whenTheReplyIsEmpty_answers502() {
    post("/support", WHERE_IS_1042 + " [silent]").expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
  }

  @Test
  void answer_whenTheModelCallThrows_answers502() {
    post("/support", WHERE_IS_1042 + " [failing]").expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
  }

  @Test
  void streamAnswer_whenTheReplyIsBlocked_endsWithAnErrorEvent() {
    List<ServerSentEvent<String>> events = streamEvents(WHERE_IS_1042 + " [blocked]");

    ServerSentEvent<String> last = events.get(events.size() - 1);
    assertThat(last.event()).isEqualTo("error");
    assertThat(last.data()).contains(SupportWorkflowConfiguration.SHIPPING);
    assertThat(storedSessions()).isEmpty();
  }

  @Test
  void streamAnswer_whenTheReplyIsEmpty_endsWithAnErrorEvent() {
    List<ServerSentEvent<String>> events = streamEvents(WHERE_IS_1042 + " [silent]");

    ServerSentEvent<String> last = events.get(events.size() - 1);
    assertThat(last.event()).isEqualTo("error");
    assertThat(last.data()).contains("without a reply");
  }

  @Test
  void streamAnswer_whenTheModelCallThrows_endsWithAnErrorEvent() {
    List<ServerSentEvent<String>> events = streamEvents(WHERE_IS_1042 + " [failing]");

    ServerSentEvent<String> last = events.get(events.size() - 1);
    assertThat(last.event()).isEqualTo("error");
    assertThat(last.data()).contains(SupportWorkflowConfiguration.SHIPPING);
    // Only the error code reaches the client, not the exception's message.
    assertThat(last.data()).doesNotContain(ScriptedModel.QUOTA_ERROR);
  }

  @Test
  void streamAnswer_whenTheClientDisconnects_deletesItsSession() throws InterruptedException {
    // No message precedes the reply, so the client disconnects once the run has its session.
    Disposable request =
        WebClient.create("http://localhost:" + port)
            .post()
            .uri("/support/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(Map.of("message", WHERE_IS_1042 + " [endless]"))
            .retrieve()
            .bodyToFlux(AgentMessage.class)
            .subscribe();
    awaitStoredSession();
    request.dispose();

    assertThat(storedSessionsAfterCleanup()).isEmpty();
  }

  @Test
  void answer_keepsTheCallersMdcInModelCalls() {
    MDC.put(REQUEST_ID, "request-42");
    Mono<SupportReply> reply;
    try {
      reply = supportService.answer(WHERE_IS_1042);
    } finally {
      MDC.remove(REQUEST_ID);
    }

    // Runs on another thread, which lacks that MDC, so the model sees it only through the copy.
    reply.subscribeOn(Schedulers.boundedElastic()).block(TIMEOUT);

    assertThat(((ScriptedModel) model).requestIdAtLastCall).isEqualTo("request-42");
  }

  @Test
  void streamAnswer_keepsTheCallersMdcInModelCalls() {
    MDC.put(REQUEST_ID, "request-43");
    Flux<AgentMessage> messages;
    try {
      messages = supportService.streamAnswer(WHERE_IS_1042);
    } finally {
      MDC.remove(REQUEST_ID);
    }

    messages.subscribeOn(Schedulers.boundedElastic()).collectList().block(TIMEOUT);

    assertThat(((ScriptedModel) model).requestIdAtLastCall).isEqualTo("request-43");
  }

  @Test
  void bothEndpoints_rejectABlankMessage() {
    post("/support", " ").expectStatus().isBadRequest();
    post("/support/stream", " ").expectStatus().isBadRequest();
  }

  private SupportReply answer(String message) {
    return Objects.requireNonNull(
        post("/support", message)
            .expectStatus()
            .isOk()
            .expectBody(SupportReply.class)
            .returnResult()
            .getResponseBody(),
        "The response has no body.");
  }

  private WebTestClient.ResponseSpec post(String path, String message) {
    return client.post().uri(path).bodyValue(Map.of("message", message)).exchange();
  }

  private Flux<AgentMessage> stream(String message) {
    return postStream(message).returnResult(AgentMessage.class).getResponseBody();
  }

  private List<AgentMessage> streamMessages(String message) {
    return stream(message).collectList().block(TIMEOUT);
  }

  private List<ServerSentEvent<String>> streamEvents(String message) {
    return postStream(message)
        .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
        .getResponseBody()
        .collectList()
        .block(TIMEOUT);
  }

  private WebTestClient.ResponseSpec postStream(String message) {
    return client
        .post()
        .uri("/support/stream")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .bodyValue(Map.of("message", message))
        .exchange()
        .expectStatus()
        .isOk();
  }

  private List<Session> storedSessions() {
    return sessionService
        .listSessions(runner.getAppName(), SupportService.USER_ID)
        .join()
        .getSessions();
  }

  /** Waits up to five seconds for the server to store a session, and fails if it does not. */
  private void awaitStoredSession() throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (storedSessions().isEmpty() && Instant.now().isBefore(deadline)) {
      Thread.sleep(50);
    }
    assertThat(storedSessions()).isNotEmpty();
  }

  /** Returns the stored sessions, waiting up to five seconds for the server to delete them. */
  private List<Session> storedSessionsAfterCleanup() throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    List<Session> sessions = storedSessions();
    while (!sessions.isEmpty() && Instant.now().isBefore(deadline)) {
      Thread.sleep(50);
      sessions = storedSessions();
    }
    return sessions;
  }

  /**
   * A test model that picks its behavior from each agent's instruction: triage classifies the
   * message, and the shipping and refunds agents call their tools, then reply from the results. A
   * message marked {@code [blocked]} makes the reply fail with an error code, {@code [failing]}
   * makes the model call throw, {@code [silent]} makes the reply come back empty after the tool
   * call, and {@code [endless]} makes it never finish. It records the {@code requestId} MDC value
   * that each call sees.
   */
  private static final class ScriptedModel extends BasePublisherModel {
    static final String QUOTA_ERROR = "quota exceeded";
    private static final Pattern ORDER_ID = Pattern.compile("\\d{4}");

    volatile @Nullable String requestIdAtLastCall;

    ScriptedModel() {
      super("scripted-model");
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      requestIdAtLastCall = MDC.get(REQUEST_ID);
      Content systemInstruction = request.getConfig().getSystemInstruction();
      String instruction = systemInstruction == null ? "" : systemInstruction.text();
      String message =
          request.getContents().stream()
              .filter(content -> Role.USER.equals(content.getRole()))
              .map(Content::text)
              .collect(joining("\n"));
      if (instruction.contains("Classify the customer's message")) {
        return reply(triage(message));
      }
      if (message.contains("[blocked]")) {
        return Mono.just(
            LlmResponse.builder().finishReason(FinishReason.SAFETY).errorCode("SAFETY").build());
      }
      if (message.contains("[failing]")) {
        return Mono.error(new IllegalStateException(QUOTA_ERROR));
      }
      if (message.contains("[endless]")) {
        return Flux.never();
      }
      Map<?, ?> toolResult = lastToolResult(request);
      if (message.contains("[silent]") && toolResult != null) {
        return reply("");
      }
      if (instruction.contains("delivery questions")) {
        return toolResult == null
            ? callTool("trackShipment", message)
            : reply("Tracking says: " + toolResult.get("status"));
      }
      return toolResult == null
          ? callTool("createReturnLabel", message)
          : reply("Refund approved. Return label " + toolResult.get("labelNumber") + ".");
    }

    /** Returns the triage agent's structured answer for {@code message}. */
    private static String triage(String message) {
      String intent =
          message.contains("refund") ? "refund" : message.contains("Where") ? "tracking" : "other";
      Matcher orderId = ORDER_ID.matcher(message);
      String id = orderId.find() ? orderId.group() : "";
      return "{\"intent\": \"" + intent + "\", \"orderId\": \"" + id + "\"}";
    }

    /**
     * Calls {@code tool} for the order in the agent's input, after writing a line of text as models
     * often do.
     */
    private static Publisher<LlmResponse> callTool(String tool, String message) {
      Matcher orderId = ORDER_ID.matcher(message);
      FunctionCall call =
          FunctionCall.builder()
              .name(tool)
              .args(Map.of("orderId", orderId.find() ? orderId.group() : ""))
              .build();
      List<Part> parts =
          List.of(
              Part.builder().text("Let me check.").build(),
              Part.builder().functionCall(call).build());
      return Mono.just(LlmResponse.builder().content(new Content(Role.MODEL, parts)).build());
    }

    /** Returns the tool's result if {@code request} ends with a tool response, or null. */
    private static @Nullable Map<?, ?> lastToolResult(LlmRequest request) {
      List<Content> contents = request.getContents();
      for (Part part : contents.get(contents.size() - 1).getParts()) {
        if (part.getFunctionResponse() != null
            && part.getFunctionResponse().getResponse().get("result") instanceof Map<?, ?> result) {
          return result;
        }
      }
      return null;
    }

    private static Publisher<LlmResponse> reply(String text) {
      return Mono.just(LlmResponse.builder().content(Content.fromText(Role.MODEL, text)).build());
    }
  }
}
