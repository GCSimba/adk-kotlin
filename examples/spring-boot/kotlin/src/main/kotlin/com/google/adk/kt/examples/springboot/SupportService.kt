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

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.adk.kt.events.Event
import com.google.adk.kt.examples.springboot.SupportService.AgentMessage.Type
import com.google.adk.kt.examples.springboot.SupportWorkflowConfiguration.Companion.REPLY
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

/**
 * The service that answers customers with the ADK. [answer] returns the finished reply, and
 * [streamAnswer] streams progress updates and the reply while the workflow runs.
 */
@Service
class SupportService(private val runner: Runner, private val sessionService: SessionService) {

  /** The reply to a customer. */
  data class SupportReply(val text: String)

  /** A streamed message: a progress update from the workflow, or the reply. */
  data class AgentMessage(val type: Type, val text: String) {
    /** What a streamed message carries. */
    enum class Type {
      @JsonProperty("progress") PROGRESS,
      @JsonProperty("reply") REPLY,
    }
  }

  /** Exception for a run that ends without a reply, such as when the model blocks one. */
  class AgentFailedException(message: String) : RuntimeException(message)

  /** Runs the workflow on [message] and returns the reply, the output of its `reply` node. */
  suspend fun answer(message: String): SupportReply {
    val reply =
      run(message).mapNotNull { it.replyText }.lastOrNull()
        ?: throw AgentFailedException("The run ended without a reply.")
    return SupportReply(reply)
  }

  /**
   * Runs the workflow on [message], emitting the progress messages its nodes publish and then the
   * `reply` node's output as the reply.
   */
  fun streamAnswer(message: String): Flow<AgentMessage> = flow {
    var replied = false
    run(message).collect { event ->
      val reply = event.replyText
      if (reply != null) {
        emit(AgentMessage(Type.REPLY, reply))
        replied = true
      } else if (event.partial && event.contentText().isNotEmpty()) {
        // Only node progress is partial: nodes publish partial messages, and no model streams.
        emit(AgentMessage(Type.PROGRESS, event.contentText()))
      }
    }
    // Without the reply node's output there is no reply, even after progress.
    if (!replied) throw AgentFailedException("The run ended without a reply.")
  }

  /**
   * Runs the workflow on [message] in a new session that is deleted once the run ends. Fails the
   * run on an event with an error code.
   */
  private fun run(message: String): Flow<Event> = flow {
    val session = sessionService.createSession(SessionKey(runner.appName, USER_ID, id = null))
    try {
      runner
        .runAsync(
          userId = USER_ID,
          sessionId = checkNotNull(session.key.id) { "The new session has no ID." },
          newMessage = Content.fromText(Role.USER, message),
        )
        .collect { event ->
          // The ADK emits an error event for a failed model call or node before any exception, so
          // fail on the first one.
          event.errorCode?.let {
            throw AgentFailedException("The ${event.nodeName.orEmpty()} node failed with $it.")
          }
          emit(event)
        }
    } finally {
      // Deletes the session even if the run fails or the client disconnects.
      withContext(NonCancellable) { sessionService.deleteSession(session.key) }
    }
  }

  /** The reply on the `reply` node's output event, or null on any other event. */
  private val Event.replyText: String?
    get() = if (nodeName == REPLY) output as? String else null

  companion object {
    /** The user ID of every session; an app with sign-in would use the caller's ID instead. */
    const val USER_ID = "anonymous"
  }
}
