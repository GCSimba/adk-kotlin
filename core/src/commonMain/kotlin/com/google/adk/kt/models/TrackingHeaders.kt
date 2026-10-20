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

  /**
   * Headers for a GenAI SDK client, which puts its own `gl-kotlin` tag first: both set to [ADK_TAG]
   * alone.
   */
  internal val GENAI_SDK_HEADERS: Map<String, String> =
    mapOf("x-goog-api-client" to ADK_TAG, "user-agent" to ADK_TAG)
}
