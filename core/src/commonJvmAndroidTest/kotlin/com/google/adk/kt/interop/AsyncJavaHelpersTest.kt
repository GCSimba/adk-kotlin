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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.common.truth.Truth.assertThat
import com.google.errorprone.annotations.MustBeClosed
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(AdkJavaInteropApi::class)
@RunWith(JUnit4::class)
class AsyncJavaHelpersTest {

  private val scope = CoroutineScope(Dispatchers.Default)

  /** Emits the trace value and coroutine name it runs with, from another dispatcher. */
  private val tracedFlow = flow { emit(CallerTrace.traceAndName()) }.flowOn(Dispatchers.IO)

  @After
  fun cancelScope() {
    scope.cancel()
  }

  @Test
  fun await_switchingDispatchers_keepsTheCallersTraceContext() {
    val seen =
      CallerTrace.CONTEXT.makeCurrent().use {
        AsyncJavaHelpers.await { withContext(Dispatchers.IO) { CallerTrace.traceAndName() } }
      }

    assertThat(seen).isEqualTo("caller/null")
  }

  @Test
  fun await_withCoroutineContext_runsInIt() {
    assertThat(AsyncJavaHelpers.await(CoroutineName("call")) { CallerTrace.traceAndName() })
      .isEqualTo("null/call")
  }

  @Test
  fun await_canceledJobInTheCoroutineContext_isIgnored() {
    val canceled = Job().apply { cancel() }

    assertThat(AsyncJavaHelpers.await(canceled) { "ran" }).isEqualTo("ran")
  }

  @Test
  fun async_callerHasATraceContext_runsInIt() {
    val seen =
      CallerTrace.CONTEXT.makeCurrent().use {
        AsyncJavaHelpers.async(scope) { CallerTrace.traceAndName() }.get(5, SECONDS)
      }

    assertThat(seen).isEqualTo("caller/null")
  }

  @Test
  fun async_scopeCarriesATraceContext_runsInItInsteadOfTheCallers() {
    val traced = CoroutineScope(Dispatchers.Default + OtelContextElement(CallerTrace.CONTEXT))

    try {
      assertThat(AsyncJavaHelpers.async(traced) { CallerTrace.traceAndName() }.get(5, SECONDS))
        .isEqualTo("caller/null")
    } finally {
      traced.cancel()
    }
  }

  @Test
  fun collect_flowOnAnotherDispatcher_runsInTheCallersTraceContext() {
    val seen = CallerTrace.CONTEXT.makeCurrent().use { AsyncJavaHelpers.collect(tracedFlow) }

    assertThat(seen).containsExactly("caller/null")
  }

  @Test
  fun collect_withCoroutineContext_runsInIt() {
    assertThat(AsyncJavaHelpers.collect(tracedFlow, CoroutineName("call")))
      .containsExactly("null/call")
  }

  @Test
  fun forEach_callerHasATraceContext_runsInIt() {
    val seen = mutableListOf<String>()

    CallerTrace.CONTEXT.makeCurrent().use { AsyncJavaHelpers.forEach(tracedFlow) { seen += it } }

    assertThat(seen).containsExactly("caller/null")
  }

  @Test
  fun forEach_withCoroutineContext_runsInItAndTheCallersTraceContext() {
    val seen = mutableListOf<String>()

    CallerTrace.CONTEXT.makeCurrent().use {
      AsyncJavaHelpers.forEach(tracedFlow, CoroutineName("call")) { seen += it }
    }

    assertThat(seen).containsExactly("caller/call")
  }
}

/** Makes [otelContext] current while a coroutine runs, as opentelemetry-extension-kotlin does. */
private class OtelContextElement(private val otelContext: Context) : ThreadContextElement<Scope> {
  companion object Key : CoroutineContext.Key<OtelContextElement>

  override val key: CoroutineContext.Key<OtelContextElement>
    get() = Key

  @MustBeClosed
  override fun updateThreadContext(context: CoroutineContext): Scope = otelContext.makeCurrent()

  override fun restoreThreadContext(context: CoroutineContext, oldState: Scope) {
    oldState.close()
  }
}
