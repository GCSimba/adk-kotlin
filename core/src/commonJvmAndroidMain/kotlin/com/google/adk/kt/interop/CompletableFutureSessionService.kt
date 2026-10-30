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
import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.GetSessionConfig
import com.google.adk.kt.sessions.ListEventsResponse
import com.google.adk.kt.sessions.ListSessionsResponse
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future

/**
 * Java-friendly wrapper around a [SessionService] that it owns: each method calls the `suspend`
 * method of the same name and returns a [CompletableFuture] of its result, so Java callers never
 * pass a `Continuation`. Each call runs in the caller's OpenTelemetry context and in the
 * `coroutineContext` passed to it, such as `MDCContext()` from kotlinx-coroutines-slf4j to carry
 * the caller's MDC. Canceling a future cancels its call, a `Job` in `coroutineContext` is ignored,
 * and [close] cancels every call in flight, then closes the service if it is [AutoCloseable].
 */
@AdkJavaInteropApi
class CompletableFutureSessionService private constructor(private val delegate: SessionService) :
  AutoCloseable {

  // Runs each call as a coroutine that completes its future; canceled by close().
  @Suppress("GlobalCoroutineDispatchers")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** The wrapped session service, for code that has dropped into Kotlin. */
  fun asKotlinSessionService(): SessionService = delegate

  /** Calls [SessionService.createSession]. */
  @JvmOverloads
  fun createSession(
    key: SessionKey,
    state: Map<String, Any>? = null,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ): CompletableFuture<Session> =
    scope.future(callerContext(coroutineContext)) { delegate.createSession(key, state) }

  /** Calls [SessionService.getSession]; the future completes with `null` if there is no session. */
  @JvmOverloads
  fun getSession(
    key: SessionKey,
    config: GetSessionConfig? = null,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ): CompletableFuture<Session?> =
    scope.future(callerContext(coroutineContext)) { delegate.getSession(key, config) }

  /** Calls [SessionService.listSessions]. */
  @JvmOverloads
  fun listSessions(
    appName: String,
    userId: String,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ): CompletableFuture<ListSessionsResponse> =
    scope.future(callerContext(coroutineContext)) { delegate.listSessions(appName, userId) }

  /** Calls [SessionService.deleteSession]. */
  @JvmOverloads
  fun deleteSession(
    key: SessionKey,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ): CompletableFuture<Void?> =
    scope.future(callerContext(coroutineContext)) {
      delegate.deleteSession(key)
      null
    }

  /** Calls [SessionService.listEvents]. */
  @JvmOverloads
  fun listEvents(
    key: SessionKey,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ): CompletableFuture<ListEventsResponse> =
    scope.future(callerContext(coroutineContext)) { delegate.listEvents(key) }

  /** Calls [SessionService.closeSession]. */
  @JvmOverloads
  fun closeSession(
    session: Session,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ): CompletableFuture<Void?> =
    scope.future(callerContext(coroutineContext)) {
      delegate.closeSession(session)
      null
    }

  /**
   * Calls [SessionService.appendEvent], which updates [session] in place on another thread, so
   * leave [session] alone until the future completes.
   */
  @JvmOverloads
  fun appendEvent(
    session: Session,
    event: Event,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ): CompletableFuture<Event> =
    scope.future(callerContext(coroutineContext)) { delegate.appendEvent(session, event) }

  /** Calls [SessionService.flush]. */
  @JvmOverloads
  fun flush(
    key: SessionKey? = null,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ): CompletableFuture<Void?> =
    scope.future(callerContext(coroutineContext)) {
      delegate.flush(key)
      null
    }

  /**
   * Cancels calls in flight, then closes the service if it is [AutoCloseable]. Every later call
   * returns a canceled future.
   */
  override fun close() {
    scope.cancel()
    (delegate as? AutoCloseable)?.close()
  }

  companion object {
    /**
     * Wraps [sessionService] and takes ownership of it: [close] also closes it if it is
     * [AutoCloseable].
     */
    @JvmStatic
    fun of(sessionService: SessionService): CompletableFutureSessionService =
      CompletableFutureSessionService(sessionService)
  }
}
