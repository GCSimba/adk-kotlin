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
package com.google.adk.kt.models

import com.google.adk.kt.VERSION
import com.google.adk.kt.annotations.FrameworkInternalApi

/**
 * Headers that let Google APIs attribute model calls to ADK, in the format all ADK SDKs use. A
 * header value is a space-separated list of `name/version` tags.
 */
@FrameworkInternalApi
object TrackingHeaders {

  /** ADK's own tag: `google-adk/<ADK version>`. */
  const val ADK_TAG: String = "google-adk/$VERSION"

  /** [ADK_TAG] followed by `gl-kotlin/<Kotlin version>`, for clients that send no language tag. */
  val VALUE: String = "$ADK_TAG gl-kotlin/${KotlinVersion.CURRENT}"

  /** The `x-goog-api-client` and `user-agent` headers, both set to [VALUE]. */
  val HEADERS: Map<String, String> = mapOf("x-goog-api-client" to VALUE, "user-agent" to VALUE)

  /**
   * Headers for a GenAI SDK client, which puts its own `gl-kotlin` tag first: both set to [ADK_TAG]
   * alone.
   */
  internal val GENAI_SDK_HEADERS: Map<String, String> =
    mapOf("x-goog-api-client" to ADK_TAG, "user-agent" to ADK_TAG)

  /**
   * Returns [headers] with each tracking header, matched case-insensitively, followed by every tag
   * of [VALUE] whose name it lacks.
   */
  fun mergeInto(headers: Map<String, String>): Map<String, String> {
    val callerValues = mutableMapOf<String, String>()
    return buildMap {
      for ((name, value) in headers) {
        val trackingName = name.lowercase()
        if (trackingName in HEADERS) {
          callerValues[trackingName] = callerValues[trackingName]?.let { "$it $value" } ?: value
        } else {
          put(name, value)
        }
      }
      for (name in HEADERS.keys) put(name, extend(callerValues[name].orEmpty()))
    }
  }

  /** Returns [callerValue] followed by each tag of [VALUE] whose name it lacks. */
  private fun extend(callerValue: String): String {
    val tags = callerValue.split(' ').filter { it.isNotEmpty() }
    val names = tags.mapTo(mutableSetOf()) { it.substringBefore('/') }
    val missing = VALUE.split(' ').filter { it.substringBefore('/') !in names }
    return (tags + missing).joinToString(" ")
  }
}
