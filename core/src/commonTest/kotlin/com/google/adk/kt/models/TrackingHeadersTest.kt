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

import com.google.adk.kt.annotations.FrameworkInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(FrameworkInternalApi::class)
class TrackingHeadersTest {

  @Test
  fun mergeInto_addsOnlyTheTagsACallerValueLacks() {
    val merged =
      TrackingHeaders.mergeInto(
        mapOf(
          "User-Agent" to "my-app/1.0 gl-kotlin/1.9.0",
          "X-Goog-Api-Client" to "my-lib/2.0 google-adk/0.0.1",
          "X-Custom" to "v",
        )
      )

    assertEquals(
      mapOf(
        "X-Custom" to "v",
        "x-goog-api-client" to "my-lib/2.0 google-adk/0.0.1 gl-kotlin/${KotlinVersion.CURRENT}",
        "user-agent" to "my-app/1.0 gl-kotlin/1.9.0 ${TrackingHeaders.ADK_TAG}",
      ),
      merged,
    )
  }

  @Test
  fun mergeInto_noCallerValues_addsTrackingHeaders() {
    assertEquals(TrackingHeaders.HEADERS, TrackingHeaders.mergeInto(emptyMap()))
  }
}
