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
package com.google.adk.kt.events

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import kotlin.jvm.JvmStatic
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Represents a tool confirmation configuration. */
@Serializable
data class ToolConfirmation(
  /** Whether the tool execution is confirmed. */
  val confirmed: Boolean,
  /** The confirmation payload. */
  @Contextual val payload: Any? = null,
  /** The hint for the confirmation. */
  val hint: String? = null,
) {
  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder = Builder().confirmed(confirmed).payload(payload).hint(hint)

  /**
   * Fluent builder for [ToolConfirmation], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var confirmed: Boolean? = null
    private var payload: Any? = null
    private var hint: String? = null

    fun confirmed(confirmed: Boolean): Builder = apply { this.confirmed = confirmed }

    fun payload(payload: Any?): Builder = apply { this.payload = payload }

    fun hint(hint: String?): Builder = apply { this.hint = hint }

    fun build(): ToolConfirmation =
      ToolConfirmation(
        confirmed =
          checkNotNull(confirmed) { "ToolConfirmation.Builder requires confirmed to be set." },
        payload = payload,
        hint = hint,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()

    /** Key for the 'confirmed' field in the serialized map. */
    const val CONFIRMED_KEY = "confirmed"

    /** Key for the 'payload' field in the serialized map. */
    const val PAYLOAD_KEY = "payload"

    /** Key for the 'hint' field in the serialized map. */
    const val HINT_KEY = "hint"
  }
}

/** The single key under which the ADK client nests a [ToolConfirmation] as a JSON string. */
private const val WRAPPED_RESPONSE_KEY = "response"

/**
 * Parses a user's answer to a confirmation request, either the [ToolConfirmation] fields themselves
 * or the ADK client's `{"response": "<json>"}` wrapper. Returns null if [response] is neither.
 */
@OptIn(FrameworkInternalApi::class)
internal fun parseToolConfirmation(response: Map<*, *>?): ToolConfirmation? {
  if (response == null) return null
  val unwrapped =
    if (response.size == 1) {
      (response[WRAPPED_RESPONSE_KEY] as? String)?.let { jsonString ->
        try {
          val element = adkJson.parseToJsonElement(jsonString) as? JsonObject ?: return null
          adkJson.decodeFromJsonElement<ToolConfirmation>(element)
        } catch (e: SerializationException) {
          null
        } catch (e: IllegalArgumentException) {
          null
        }
      }
    } else {
      null
    }
  if (unwrapped != null) return unwrapped
  val confirmed = response[ToolConfirmation.CONFIRMED_KEY] as? Boolean ?: return null
  return ToolConfirmation(
    confirmed = confirmed,
    payload = response[ToolConfirmation.PAYLOAD_KEY],
    hint = response[ToolConfirmation.HINT_KEY] as? String,
  )
}
