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

package com.google.adk.kt.examples.springboot

import com.google.adk.kt.examples.springboot.SupportService.AgentFailedException
import com.google.adk.kt.examples.springboot.SupportService.SupportReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** The HTTP endpoints of the order-support service, backed by [SupportService]. */
@RestController
@RequestMapping("/support")
class SupportController(private val supportService: SupportService) {

  /** The JSON body of both endpoints: the customer's message. */
  data class SupportRequest(val message: String?)

  /** Answers a request/response call with the finished reply as JSON. */
  @PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
  suspend fun answer(@RequestBody request: SupportRequest): SupportReply =
    supportService.answer(requireMessage(request))

  /** Streams progress updates and the reply as server-sent events while the workflow runs. */
  @PostMapping(path = ["/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
  fun streamAnswer(@RequestBody request: SupportRequest): Flow<ServerSentEvent<Any>> =
    supportService
      .streamAnswer(requireMessage(request))
      .map { ServerSentEvent.builder<Any>(it).build() }
      // The 200 status is already sent, so a failed run ends the stream with an error event.
      .catch { e ->
        if (e !is AgentFailedException) throw e
        emit(ServerSentEvent.builder<Any>(e.message.orEmpty()).event("error").build())
      }

  /** Returns 502 Bad Gateway when a request/response call ends without a reply. */
  @ExceptionHandler(AgentFailedException::class)
  fun agentFailed(e: AgentFailedException): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.message)

  private fun requireMessage(request: SupportRequest): String {
    val message = request.message
    if (message.isNullOrBlank()) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The message must not be blank.")
    }
    return message
  }
}
