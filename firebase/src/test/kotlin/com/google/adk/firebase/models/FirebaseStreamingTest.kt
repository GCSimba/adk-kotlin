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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for [streamToLlmResponses].
 *
 * [FirebaseResponseConversionTest] streams mocked chunks, and `FirebaseIntegrationTest` covers the
 * live error path where the SDK throws mid-stream.
 */
@RunWith(JUnit4::class)
class FirebaseStreamingTest {

  /** An empty chunk stream yields no responses. */
  @Test
  fun emptyStream_emitsNothing() {
    val responses = runBlocking { streamToLlmResponses(emptyFlow()).toList() }

    assertThat(responses).isEmpty()
  }
}
