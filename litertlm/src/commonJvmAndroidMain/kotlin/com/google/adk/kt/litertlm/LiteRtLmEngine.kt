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

package com.google.adk.kt.litertlm

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message as LiteRtLmMessage
import com.google.ai.edge.litertlm.MessageCallback

/** Interface wrapping the LiteRT-LM Engine to enable mockability. */
interface LiteRtLmEngine : AutoCloseable {
  fun isInitialized(): Boolean

  fun initialize()

  fun createConversation(config: ConversationConfig): LiteRtLmConversation
}

/** Interface wrapping the LiteRT-LM Conversation to enable mockability. */
interface LiteRtLmConversation : AutoCloseable {
  fun sendMessage(message: LiteRtLmMessage): LiteRtLmMessage

  fun sendMessageAsync(message: LiteRtLmMessage, callback: MessageCallback)

  /**
   * Stops generation started by [sendMessageAsync], so that closing does not block until it
   * finishes. The conversation cannot be reused afterwards and must be closed.
   */
  fun cancelProcess() {}
}

/** Default implementation of [LiteRtLmEngine] delegating to the native [Engine]. */
class DefaultLiteRtLmEngine(private val delegate: Engine) : LiteRtLmEngine {
  override fun isInitialized(): Boolean = delegate.isInitialized()

  override fun initialize() = delegate.initialize()

  override fun createConversation(config: ConversationConfig): LiteRtLmConversation {
    return DefaultLiteRtLmConversation(delegate.createConversation(config))
  }

  override fun close() = delegate.close()
}

/** Default implementation of [LiteRtLmConversation] delegating to the native [Conversation]. */
class DefaultLiteRtLmConversation(private val delegate: Conversation) : LiteRtLmConversation {
  override fun sendMessage(message: LiteRtLmMessage): LiteRtLmMessage =
    // A call that omits defaulted parameters fails to link on newer LiteRT-LM releases.
    delegate.sendMessage(
      message,
      extraContext = emptyMap(),
      repetitionPenaltyConfig = null,
      noRepeatNgramConfig = null,
      suppressTokensConfig = null,
      maxOutputToken = null,
      thinkingConfig = null,
      responseFormat = null,
    )

  override fun sendMessageAsync(message: LiteRtLmMessage, callback: MessageCallback) {
    // A call that omits defaulted parameters fails to link on newer LiteRT-LM releases.
    delegate.sendMessageAsync(
      message,
      callback,
      extraContext = emptyMap(),
      repetitionPenaltyConfig = null,
      noRepeatNgramConfig = null,
      suppressTokensConfig = null,
      maxOutputToken = null,
      thinkingConfig = null,
      responseFormat = null,
    )
  }

  override fun cancelProcess() = delegate.cancelProcess()

  override fun close() = delegate.close()
}
