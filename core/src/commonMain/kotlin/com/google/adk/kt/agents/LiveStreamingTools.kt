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

@file:OptIn(ExperimentalLiveApi::class, ExperimentalAtomicApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmSynthetic
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wraps [value] in a user [Content] carrying a [FunctionResponse] for [toolName] and [callId]. A
 * [FunctionResponse] [value] contributes only its payload, since the call it answers is not the
 * tool's to choose, and `Unit` becomes `{"result": null}`.
 */
private fun functionResponseContent(toolName: String, callId: String, value: Any?): Content {
  val payload = if (value is FunctionResponse) value.response else value
  return Content(
    role = Role.USER,
    parts =
      listOf(
        Part(
          functionResponse =
            FunctionResponse(
              name = toolName,
              response = toFinalResponseMap(payload.takeUnless { it === Unit }),
              id = callId,
            )
        )
      ),
  )
}

/** Replaces the value with [transform] of it, retrying when another thread got there first. */
@JvmSynthetic
internal inline fun <T> AtomicReference<T>.mutate(transform: (T) -> T) {
  while (true) {
    val current = load()
    if (compareAndSet(current, transform(current))) return
  }
}

/**
 * The streaming tools one agent's live run started, each collected in the background until it ends
 * or the run ends or hands the conversation over.
 *
 * Each value a tool emits reaches the model as a function response to the call that started it.
 */
internal class LiveStreamingTools(
  private val queue: LiveRequestQueue,
  private val agentName: String,
  private val invocationId: String,
  coroutineContext: CoroutineContext,
) {
  // No parent job, so a stuck tool is left behind; supervised, so one failure spares the others.
  private val scope =
    CoroutineScope(
      coroutineContext.minusKey(Job) +
        SupervisorJob() +
        CoroutineExceptionHandler { _, e ->
          logger.warn { "A streaming tool failed with ${e::class.simpleName}, not reported." }
        }
    )

  // Each call leaves the list when its job completes, however it ends, started or not.
  private val active = AtomicReference<List<StreamingCall>>(emptyList())

  // Calls that finished since the last stop, whose last values may still be queued.
  private val finished = AtomicReference<List<String>>(emptyList())

  /** One function call to a streaming tool, and the coroutine collecting what it emits. */
  private class StreamingCall(val toolName: String, val callId: String) {
    lateinit var job: Job
  }

  /**
   * Runs a live tool call, collecting a returned [Flow] in the background and answering the call as
   * pending.
   */
  suspend fun run(tool: BaseTool, context: ToolContext, args: Map<String, Any?>): Any {
    val result = tool.run(context, args)
    val stream = result as? Flow<*> ?: return result
    // Every live call has an id: the turn fills in a missing one before any tool runs.
    val callId = checkNotNull(context.functionCallId) { "A live tool call has no id." }
    start(tool, callId, stream)
    return mapOf(STATUS_KEY to PENDING_STATUS)
  }

  /**
   * Starts the streams whose pending answers [content] carries, now that those answers are queued
   * ahead of anything the streams send. Cancels them instead when the queue is closed, since nobody
   * would hear them.
   */
  fun onAnswersQueued(content: Content) {
    val ids = content.parts.mapNotNullTo(mutableSetOf()) { it.functionResponse?.id }
    for (call in active.load()) {
      if (call.callId !in ids) continue
      if (!queue.isClosed) call.job.start() else call.job.cancel()
    }
  }

  /**
   * Cancels every streaming call this run started and waits up to [STOP_TIMEOUT] for them when the
   * live run ends or hands over, even if the caller is cancelled.
   */
  suspend fun stopAll(): Unit =
    withContext(NonCancellable) {
      val running = active.exchange(emptyList())
      // A call that ignores cancellation is already warned about and left behind.
      if (running.isNotEmpty()) {
        val unused = cancelAndAwait(running)
      }
      // Their values may still be queued, behind an answer whose write dropped the connection.
      queue.discardAnswersTo(finished.exchange(emptyList()) + running.map { it.callId })
    }

  /** Stops every streaming call and releases the scope. */
  suspend fun close() {
    stopAll()
    scope.cancel()
  }

  /** Starts streaming [stream] for [callId] once its pending answer is queued. */
  @Suppress("UnsafeCoroutineCrossing") // Only the launched coroutine touches the stream.
  private fun start(tool: BaseTool, callId: String, stream: Flow<*>) {
    val call = StreamingCall(tool.name, callId)
    // Waits for its pending answer to be queued, so the model hears that first.
    call.job = scope.launch(start = CoroutineStart.LAZY) { relay(tool, call, stream) }
    active.mutate { calls -> calls + call }
    call.job.invokeOnCompletion {
      finished.mutate { ids -> ids + call.callId }
      active.mutate { calls -> calls - call }
    }
  }

  private suspend fun relay(tool: BaseTool, call: StreamingCall, stream: Flow<*>) {
    try {
      stream.collect { value ->
        // A flow that never suspends would otherwise outlive a stop or a handover.
        currentCoroutineContext().ensureActive()
        // Not partial: Kotlin never sends a partial tool answer, and the wire ignores it anyway.
        queue.sendContent(functionResponseContent(tool.name, call.callId, value))
      }
    } catch (e: Throwable) {
      // Exceptions and TODO()'s NotImplementedError reach the model unless stopped.
      if ((e is Error && e !is NotImplementedError) || !currentCoroutineContext().isActive) throw e
      reportFailure(tool, call, e)
    }
  }

  private fun reportFailure(tool: BaseTool, call: StreamingCall, e: Throwable) {
    // The exception's text is left out: it can carry the user's or the model's data.
    logger.warn { "Streaming tool ${tool.name} failed (${e::class.simpleName})." }
    val error = mapOf(ERROR_KEY to "Invoking `${tool.name}()` failed with an internal error.")
    queue.sendContent(functionResponseContent(tool.name, call.callId, error))
  }

  /**
   * Cancels [calls] and waits up to [STOP_TIMEOUT] for them, warning about any that ignore it. The
   * caller's own cancellation still propagates out of the wait.
   *
   * @return whether all of them stopped in time.
   */
  private suspend fun cancelAndAwait(calls: List<StreamingCall>): Boolean {
    for (call in calls) {
      call.job.cancel()
    }
    val stopped = withTimeoutOrNull(STOP_TIMEOUT) { calls.map { it.job }.joinAll() } != null
    for (call in calls) {
      if (!call.job.isCompleted) {
        logger.warn { "Streaming tool ${call.toolName} ignored cancellation." }
      }
    }
    return stopped
  }

  companion object {
    private val logger = LoggerFactory.getLogger(LiveStreamingTools::class)
    private val STOP_TIMEOUT = 1.seconds
    private const val STATUS_KEY = "status"
    private const val ERROR_KEY = "error"

    /** The answer a call that starts a streaming tool gets at once. */
    private const val PENDING_STATUS =
      "The function is running asynchronously and the results are pending."
  }
}
