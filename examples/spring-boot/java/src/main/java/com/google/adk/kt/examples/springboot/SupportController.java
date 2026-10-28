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

import com.google.adk.kt.examples.springboot.SupportService.AgentFailedException;
import com.google.adk.kt.examples.springboot.SupportService.SupportReply;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The HTTP endpoints of the order-support service, backed by {@link SupportService}. */
@RestController
@RequestMapping("/support")
class SupportController {

  private final SupportService supportService;

  SupportController(SupportService supportService) {
    this.supportService = supportService;
  }

  /** The JSON body of both endpoints: the customer's message. */
  record SupportRequest(@Nullable String message) {}

  /** Answers a request/response call with the finished reply as JSON. */
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  Mono<SupportReply> answer(@RequestBody SupportRequest request) {
    return supportService.answer(requireMessage(request));
  }

  /** Streams progress updates and the reply as server-sent events while the workflow runs. */
  @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  Flux<ServerSentEvent<Object>> streamAnswer(@RequestBody SupportRequest request) {
    return supportService
        .streamAnswer(requireMessage(request))
        .map(message -> ServerSentEvent.<Object>builder(message).build())
        // The 200 status is already sent, so a failed run ends the stream with an error event.
        .onErrorResume(
            AgentFailedException.class,
            e -> Mono.just(ServerSentEvent.<Object>builder(e.getMessage()).event("error").build()));
  }

  /** Returns 502 Bad Gateway when a request/response call ends without a reply. */
  @ExceptionHandler(AgentFailedException.class)
  ProblemDetail agentFailed(AgentFailedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
  }

  private static String requireMessage(SupportRequest request) {
    if (request.message() == null || request.message().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The message must not be blank.");
    }
    return request.message();
  }
}
