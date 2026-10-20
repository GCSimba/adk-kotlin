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

package com.google.adk.firebase.models

import com.google.adk.firebase.utils.Conversions
import com.google.adk.kt.types.FinishReason
import com.google.common.truth.Truth.assertThat
import com.google.firebase.ai.type.BlockReason
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.PromptFeedback
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Tests how Firebase responses with no candidate are converted to ADK responses, both individually
 * and when streamed.
 *
 * firebase-ai keeps the response constructors internal, so these tests mock the final response
 * classes, which requires Mockito's inline mock maker.
 */
@RunWith(JUnit4::class)
class FirebaseResponseConversionTest {

  /** An empty response (no candidate) is not flagged as an error. */
  @Test
  fun convertResponse_noCandidate_hasNoError() {
    val llmResponse = Conversions().convertResponse(emptyResponse())

    assertThat(llmResponse.errorCode).isNull()
    assertThat(llmResponse.errorMessage).isNull()
  }

  /**
   * A blocked response with no candidate derives a finish reason and error from the block reason.
   */
  @Test
  fun convertResponse_blockedNoCandidate_hasError() {
    val llmResponse = Conversions().convertResponse(blockedResponse())

    assertThat(llmResponse.finishReason).isEqualTo(FinishReason.SAFETY)
    assertThat(llmResponse.errorCode).isEqualTo(FinishReason.SAFETY.name)
    assertThat(llmResponse.errorMessage).isEqualTo("blocked")
  }

  /**
   * A prompt-block chunk is emitted as a partial, then a terminal response flagged with the error.
   */
  @Test
  fun blockFeedbackChunk_emitsPartialThenErrorTerminal() {
    val responses = runBlocking { streamToLlmResponses(flowOf(blockedResponse())).toList() }

    assertThat(responses).hasSize(2)

    // First: the block chunk itself, surfaced as a partial.
    assertThat(responses[0].partial).isTrue()
    assertThat(responses[0].errorCode).isEqualTo(FinishReason.SAFETY.name)

    // Then: the aggregated terminal response, carrying the block as a non-partial error.
    val terminal = responses[1]
    assertThat(terminal.partial).isFalse()
    assertThat(terminal.finishReason).isEqualTo(FinishReason.SAFETY)
    assertThat(terminal.errorCode).isEqualTo(FinishReason.SAFETY.name)
    assertThat(terminal.errorMessage).isEqualTo("blocked")
  }

  /** An empty chunk yields an empty partial, then an empty, error-free final response. */
  @Test
  fun contentlessChunk_emitsPartialThenEmptyTerminal() {
    val responses = runBlocking { streamToLlmResponses(flowOf(emptyResponse())).toList() }

    assertThat(responses).hasSize(2)

    // First: the contentless chunk, surfaced as a partial.
    assertThat(responses[0].partial).isTrue()
    assertThat(responses[0].errorCode).isNull()

    // Then: the aggregated non-partial terminal frame — empty and error-free.
    val terminal = responses[1]
    assertThat(terminal.partial).isFalse()
    assertThat(terminal.content).isNull()
    assertThat(terminal.finishReason).isNull()
    assertThat(terminal.errorCode).isNull()
    assertThat(terminal.errorMessage).isNull()
  }

  /** A response with no candidate and no prompt feedback. */
  private fun emptyResponse(): GenerateContentResponse = mock {
    on { candidates } doReturn emptyList()
  }

  /**
   * A response with no candidate whose prompt was blocked for safety with the message "blocked".
   */
  private fun blockedResponse(): GenerateContentResponse {
    val feedback =
      mock<PromptFeedback> {
        on { blockReason } doReturn BlockReason.SAFETY
        on { blockReasonMessage } doReturn "blocked"
      }
    return mock {
      on { candidates } doReturn emptyList()
      on { promptFeedback } doReturn feedback
    }
  }
}
