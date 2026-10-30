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

package com.google.adk.kt.interop

import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext

/** Stands in for what a Java caller's thread holds: an OpenTelemetry context only it sees. */
internal object CallerTrace {
  private val KEY: ContextKey<String> = ContextKey.named("trace")

  /** A context whose trace value is `caller`; make it current on the calling thread. */
  val CONTEXT: Context = Context.root().with(KEY, "caller")

  /** The trace value of the calling thread's current context. */
  fun trace(): String? = Context.current().get(KEY)

  /** The trace value and coroutine name the calling coroutine runs with, as `trace/name`. */
  suspend fun traceAndName(): String =
    "${trace()}/${currentCoroutineContext()[CoroutineName]?.name}"
}
