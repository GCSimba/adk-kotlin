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

package com.google.adk.kt.sessions

import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SessionWriteBufferTest {

  private val parent = Job()
  private val store = FakeStore()
  private val buffer =
    SessionWriteBuffer(Dispatchers.Default + parent, store::persist, maxUnreportedFailures = 2)
  private val storeError = NotImplementedError("store stub")

  @AfterTest
  fun tearDown() {
    parent.cancel()
  }

  @Test
  fun append_storeBlocked_returnsUpdatedEventFirst() =
    runBlocking<Unit> {
      val event = event()
      store.block(event)

      val appended = buffer.append(Session(KEY)) { event }

      assertSame(event, appended)
      assertTrue(store.persisted.isEmpty())
      store.unblockAll()
      buffer.awaitWritesOf(KEY)
      assertEquals(listOf(event.id), store.persistedIds)
    }

  @Test
  fun append_eventChangedAfterward_writesCopyWithVersionItReplaced() =
    runBlocking<Unit> {
      val session = Session(KEY, lastUpdateTime = Instant.fromEpochMilliseconds(42))
      val event = event(mapOf("kept" to "v"))
      store.block(event)

      val appended =
        buffer.append(session) {
          session.lastUpdateTime = Instant.fromEpochMilliseconds(43)
          event
        }
      appended.actions.stateDelta["late"] = "v"
      store.unblockAll()
      buffer.flush(KEY)

      val written = store.persisted.single()
      assertEquals(Instant.fromEpochMilliseconds(42), written.expectedUpdateTime)
      assertEquals(mapOf<String, Any>("kept" to "v"), written.event.actions.stateDelta)
    }

  @Test
  fun append_sharedScopeChangedInTwoSessions_persistsChangesInAppendOrder() =
    runBlocking<Unit> {
      val first = event(mapOf("user:theme" to "light"))
      val second = event(mapOf("user:theme" to "dark"))
      store.block(first)

      append(KEY, first)
      append(KEY.copy(id = "second"), second)
      delay(SETTLE)

      assertFalse(second.id in store.attemptedIds, "the second change did not wait")
      store.unblockAll()
      buffer.flushAll()
      assertEquals(listOf(first.id, second.id), store.persistedIds)
    }

  @Test
  fun append_differentSharedScopes_doNotWaitForEachOther() =
    runBlocking<Unit> {
      val blocked = event(mapOf("user:theme" to "light", "count" to 1))
      store.block(blocked)
      append(KEY, blocked)

      append(KEY.copy(id = "other"), event(mapOf("app:flag" to "on")))
      append(OTHER_USER_KEY, event(mapOf("user:theme" to "dark")))

      withTimeout(5.seconds) {
        buffer.awaitWritesOf(KEY.copy(id = "other"))
        buffer.awaitWritesOf(OTHER_USER_KEY)
      }
    }

  @Test
  fun append_sessionHasFailureToReport_throwsWithoutUpdatingThenStartsFreshWriter() =
    runBlocking<Unit> {
      val failing = event()
      val failure = IllegalStateException("store unavailable")
      store.fail(failing, cause = failure)
      append(KEY, failing)
      // Waits for the failing write without reporting it.
      buffer.awaitWritesOf(KEY)
      var updated = false

      val thrown =
        assertFailsWith<SessionException> {
          buffer.append(Session(KEY)) {
            updated = true
            event()
          }
        }
      val kept = event()
      append(KEY, kept)
      buffer.flush(KEY)

      assertSame(failure, thrown.cause)
      assertFalse(updated)
      assertEquals(listOf(kept.id), store.persistedIds)
    }

  @Test
  fun append_earlierWriteFailedWhileLastWriteWaits_throwsWithoutUpdating() =
    runBlocking<Unit> {
      val othersChange = event(mapOf("user:theme" to "light"))
      val failing = event(mapOf("app:flag" to "on"))
      val failure = IllegalStateException("store unavailable")
      store.block(othersChange, failing)
      store.fail(failing, cause = failure)
      append(KEY.copy(id = "other"), othersChange)
      append(KEY, failing)
      // Changes user: state, so it waits for the other session's change and is still pending.
      append(KEY, event(mapOf("user:theme" to "dark")))
      store.unblock(failing)
      // Waits for the failing write, the app's last change, without reporting it.
      buffer.awaitWritesSeenBy(OTHER_USER_KEY)
      var updated = false

      val thrown =
        assertFailsWith<SessionException> {
          buffer.append(Session(KEY)) {
            updated = true
            event()
          }
        }

      assertSame(failure, thrown.cause)
      assertFalse(updated)
    }

  @Test
  fun append_failureForgottenByCap_isNotReported() =
    runBlocking<Unit> {
      val failing = event()
      val others = List(2) { event() }
      (others + failing).forEach { store.fail(it) }
      append(KEY, failing)
      buffer.awaitWritesOf(KEY)
      others.forEachIndexed { i, event ->
        append(KEY.copy(id = "other-$i"), event)
        buffer.awaitWritesOf(KEY.copy(id = "other-$i"))
      }

      val kept = event()
      append(KEY, kept)
      buffer.flush(KEY)

      assertEquals(listOf(kept.id), store.persistedIds)
    }

  @Test
  fun append_afterClose_throws() =
    runBlocking<Unit> {
      buffer.close()

      assertFailsWith<IllegalStateException> { append(KEY, event()) }
    }

  @Test
  fun append_whileClosing_throws() =
    runBlocking<Unit> {
      // The scope is still active while close flushes, so only the closed check rejects this.
      buffer.close { assertFailsWith<IllegalStateException> { append(KEY, event()) } }
    }

  @Test
  fun append_scopeCanceled_throws() =
    runBlocking<Unit> {
      parent.cancel()

      assertFailsWith<IllegalStateException> { append(KEY, event()) }
    }

  @Test
  fun append_persistThrewError_throwsSinceBufferStopped() =
    runBlocking<Unit> {
      val stopped = bufferStoppedByError()

      assertFailsWith<IllegalStateException> {
        stopped.append(Session(KEY.copy(id = "other"))) { event() }
      }
    }

  @Test
  fun awaitWritesOf_waitsOnlyForThatSessionsWrites() =
    runBlocking<Unit> {
      val blocked = event()
      store.block(blocked)
      append(KEY, blocked)

      val own = async { buffer.awaitWritesOf(KEY) }
      delay(SETTLE)

      withTimeout(5.seconds) { buffer.awaitWritesOf(KEY.copy(id = "other")) }
      assertFalse(own.isCompleted, "the wait did not cover the session's write")
      store.unblockAll()
      own.await()
    }

  @Test
  fun awaitWritesSeenBy_sessionChange_waitsForIt() =
    runBlocking<Unit> {
      val sessionChange = event(mapOf("count" to 1))
      store.block(sessionChange)
      append(KEY, sessionChange)

      val read = async { buffer.awaitWritesSeenBy(KEY) }
      delay(SETTLE)

      assertFalse(read.isCompleted, "the read did not wait for its session's write")
      store.unblockAll()
      read.await()
    }

  @Test
  fun awaitWritesSeenBy_userScopeChange_waitsOnlyForSameUser() =
    runBlocking<Unit> {
      val userChange = event(mapOf("user:theme" to "dark"))
      store.block(userChange)
      append(KEY, userChange)

      val sameUser = async { buffer.awaitWritesSeenBy(KEY.copy(id = "reader")) }
      delay(SETTLE)

      withTimeout(5.seconds) { buffer.awaitWritesSeenBy(OTHER_USER_KEY) }
      assertFalse(sameUser.isCompleted, "the read did not wait for its user's shared state")
      store.unblockAll()
      sameUser.await()
    }

  @Test
  fun awaitWritesSeenBy_appScopeChangeByAnotherUser_waitsForIt() =
    runBlocking<Unit> {
      val appChange = event(mapOf("app:flag" to "on"))
      store.block(appChange)
      append(OTHER_USER_KEY, appChange)

      val read = async { buffer.awaitWritesSeenBy(KEY) }
      delay(SETTLE)

      assertFalse(read.isCompleted, "the read did not wait for its app's shared state")
      store.unblockAll()
      read.await()
    }

  @Test
  fun awaitWritesSeenBy_olderSharedScopeChangeCompleted_stillWaitsForNewerOne() =
    runBlocking<Unit> {
      val older = event(mapOf("user:theme" to "light"))
      val newer = event(mapOf("user:theme" to "dark"))
      store.block(newer)
      append(KEY, older)
      append(KEY.copy(id = "second"), newer)
      buffer.awaitWritesOf(KEY)

      val read = async { buffer.awaitWritesSeenBy(KEY.copy(id = "reader")) }
      delay(SETTLE)

      assertFalse(read.isCompleted, "the read did not wait for the newer shared-state change")
      store.unblockAll()
      read.await()
    }

  @Test
  fun awaitWritesListedFor_sessionChange_waitsOnlyForListingOfItsUserAndApp() =
    runBlocking<Unit> {
      val sessionChange = event(mapOf("count" to 1))
      store.block(sessionChange)
      append(KEY, sessionChange)

      val ownListing = async { buffer.awaitWritesListedFor(APP, USER) }
      delay(SETTLE)

      withTimeout(5.seconds) {
        buffer.awaitWritesListedFor(APP, OTHER_USER)
        buffer.awaitWritesListedFor("other-app", USER)
      }
      assertFalse(ownListing.isCompleted, "the listing did not wait for its user's session")
      store.unblockAll()
      ownListing.await()
    }

  @Test
  fun awaitWritesListedFor_appScopeChangeByAnotherUser_waitsForIt() =
    runBlocking<Unit> {
      val appChange = event(mapOf("app:flag" to "on"))
      store.block(appChange)
      append(OTHER_USER_KEY, appChange)

      val listing = async { buffer.awaitWritesListedFor(APP, USER) }
      delay(SETTLE)

      assertFalse(listing.isCompleted, "the listing did not wait for its app's shared state")
      store.unblockAll()
      listing.await()
    }

  @Test
  fun flush_writeFailed_runsBeforeReportThenThrowsOnce() =
    runBlocking<Unit> {
      val failing = event()
      val failure = IllegalStateException("store unavailable")
      store.fail(failing, cause = failure)
      append(KEY, failing)
      var reported = 0

      val thrown = assertFailsWith<SessionException> { buffer.flush(KEY) { reported++ } }
      buffer.flush(KEY) { reported++ }

      assertEquals(SessionException.SESSION_PERSISTENCE_FAILED, thrown.message)
      assertSame(failure, thrown.cause)
      assertEquals(2, reported)
    }

  @Test
  fun flush_sessionWithoutWrites_runsBeforeReport() =
    runBlocking<Unit> {
      var reported = false

      buffer.flush(KEY) { reported = true }

      assertTrue(reported)
    }

  @Test
  fun flush_laterWriteFailedBeforeReport_reportsIt() =
    runBlocking<Unit> {
      val waited = event()
      val failing = event()
      val failure = IllegalStateException("store unavailable")
      store.block(waited)
      store.fail(failing, cause = failure)
      append(KEY, waited)
      val failingCompleted = CompletableDeferred<Unit>()
      val flush =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<SessionException> { buffer.flush(KEY) { failingCompleted.await() } }
        }

      append(KEY, failing)
      store.unblockAll()
      buffer.awaitWritesOf(KEY)
      failingCompleted.complete(Unit)

      assertSame(failure, flush.await().cause)
    }

  @Test
  fun flush_newWriterFailedBeforeReport_reportsIt() =
    runBlocking<Unit> {
      val waited = event()
      val failing = event()
      val failure = IllegalStateException("store unavailable")
      store.block(waited)
      store.fail(failing, cause = failure)
      append(KEY, waited)
      val reachedReport = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      // Undispatched, so the flush waits on the session's last write.
      val flush =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<SessionException> {
            buffer.flush(KEY) {
              reachedReport.complete(Unit)
              release.await()
            }
          }
        }
      store.unblockAll()
      // That write is persisted, so the next append starts a new writer.
      reachedReport.await()
      append(KEY, failing)
      buffer.awaitWritesOf(KEY)
      release.complete(Unit)

      assertSame(failure, flush.await().cause)
      buffer.flush(KEY)
    }

  @Test
  fun flush_laterWriteFailedWhileLastWriteWaits_throws() =
    runBlocking<Unit> {
      val othersChange = event(mapOf("user:theme" to "light"))
      val waited = event()
      val failing = event(mapOf("app:flag" to "on"))
      val failure = IllegalStateException("store unavailable")
      store.block(othersChange, waited)
      store.fail(failing, cause = failure)
      append(KEY.copy(id = "other"), othersChange)
      append(KEY, waited)
      val release = CompletableDeferred<Unit>()
      // Undispatched, so the flush waits on the first write only.
      val flush =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<SessionException> { buffer.flush(KEY) { release.await() } }
        }
      append(KEY, failing)
      // Changes user: state, so it waits for the other session's change and is still pending.
      append(KEY, event(mapOf("user:theme" to "dark")))
      store.unblock(waited)
      // Waits for the failing write, the app's last change.
      buffer.awaitWritesSeenBy(OTHER_USER_KEY)
      release.complete(Unit)

      assertSame(failure, flush.await().cause)
    }

  @Test
  fun flush_waitedWriteFailedWhileNewerWriteStillWaits_throws() =
    runBlocking<Unit> {
      val othersChange = event(mapOf("user:theme" to "light"))
      val failing = event()
      store.block(othersChange, failing)
      store.fail(failing)
      append(KEY.copy(id = "other"), othersChange)
      append(KEY, failing)
      // Undispatched, so the flush waits on the failing write before the next append.
      val flush =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<SessionException> { buffer.flush(KEY) }
        }
      // Changes user: state, so it waits for the other session's change and is still pending.
      append(KEY, event(mapOf("user:theme" to "dark")))

      store.unblock(failing)
      val unusedFailure = flush.await()
    }

  @Test
  fun flush_failureReportedMeanwhileByAppend_isNotReportedAgain() =
    runBlocking<Unit> {
      val waited = event()
      val failing = event()
      store.block(waited)
      store.fail(failing)
      append(KEY, waited)
      val release = CompletableDeferred<Unit>()
      // Undispatched, so the flush waits on the first write only.
      val flush =
        async(start = CoroutineStart.UNDISPATCHED) { buffer.flush(KEY) { release.await() } }
      append(KEY, failing)
      store.unblockAll()
      buffer.awaitWritesOf(KEY)

      assertFailsWith<SessionException> { append(KEY, event()) }
      release.complete(Unit)

      flush.await()
    }

  @Test
  fun flush_failureReported_writesDroppedBehindItAreNotReportedAgain() =
    runBlocking<Unit> {
      val failing = event()
      store.block(failing)
      store.fail(failing)
      append(KEY, failing)
      // Undispatched, so the flush waits on the failing write before the next append.
      val flush =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<SessionException> { buffer.flush(KEY) }
        }
      val dropped = event()
      append(KEY, dropped)

      store.unblockAll()
      val unusedFailure = flush.await()
      val kept = event()
      append(KEY, kept)
      buffer.flush(KEY)

      assertEquals(listOf(kept.id), store.persistedIds)
      assertFalse(dropped.id in store.attemptedIds)
    }

  @Test
  fun flush_waitedWriteEvictedByCap_stillThrows() =
    runBlocking<Unit> {
      val failure = IllegalStateException("store unavailable")
      val failing = event()
      val others = List(2) { event() }
      (others + failing).forEach { store.fail(it, cause = failure) }
      append(KEY, failing)
      val reachedReport = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val flush = async {
        assertFailsWith<SessionException> {
          buffer.flush(KEY) {
            reachedReport.complete(Unit)
            release.await()
          }
        }
      }
      reachedReport.await()

      others.forEachIndexed { i, event -> append(KEY.copy(id = "other-$i"), event) }
      val othersFailed = assertFailsWith<SessionException> { buffer.flushAll() }
      release.complete(Unit)

      // Only the two newer failures are kept, so this session's was evicted.
      assertEquals(1, othersFailed.suppressedExceptions.size)
      assertSame(failure, flush.await().cause)
    }

  @Test
  fun flush_writeQueuedWhenBufferStops_waitsForEarlierWriteStillRunning() =
    runBlocking<Unit> {
      val executor = Executors.newSingleThreadExecutor()
      val oneThread = executor.asCoroutineDispatcher()
      val stopJob = Job(parent)
      val first = event()
      store.block(first)
      val stopping =
        SessionWriteBuffer(
          oneThread + stopJob,
          persist = { write ->
            // Keeps running after the stop, like a store call that ignores cancellation.
            if (write.event.id == first.id) {
              withContext(NonCancellable) { store.persist(write) }
            } else {
              store.persist(write)
            }
          },
        )
      try {
        val unusedFirst = stopping.append(Session(KEY)) { first }
        store.awaitAttempt(first)
        // Holds the only thread, so the next write is still queued when the stop lands.
        val threadFree = CountDownLatch(1)
        executor.execute { threadFree.await() }
        val unusedSecond = stopping.append(Session(KEY)) { event() }
        stopJob.cancel()
        threadFree.countDown()

        val flush = async { assertFailsWith<SessionException> { stopping.flush(KEY) } }
        delay(SETTLE)

        assertFalse(flush.isCompleted, "flush returned while the first write was still running")
        store.unblockAll()
        val unusedFailure = flush.await()
      } finally {
        store.unblockAll()
        oneThread.close()
      }
    }

  @Test
  fun flushAll_droppedWriteOfRetiredWriterFailed_keepsOtherFailures() =
    runBlocking<Unit> {
      val firstFailing = event()
      val othersChange = event(mapOf("user:theme" to "light"))
      val failing = event()
      val lastFailing = event()
      store.block(othersChange, failing)
      store.fail(firstFailing, failing, lastFailing)
      append(KEY.copy(id = "first"), firstFailing)
      buffer.awaitWritesOf(KEY.copy(id = "first"))
      append(KEY.copy(id = "other"), othersChange)
      append(KEY, failing)
      // Undispatched, so the flush waits on the failing write before the next append.
      val flush =
        async(start = CoroutineStart.UNDISPATCHED) {
          assertFailsWith<SessionException> { buffer.flush(KEY) }
        }
      // Changes user: state, so it waits for the other session's change and is still pending.
      append(KEY, event(mapOf("user:theme" to "dark")))
      store.unblock(failing)
      val unusedFailure = flush.await()
      // The flush retired the writer; its dropped write completes once the other change persists.
      store.unblockAll()
      buffer.awaitWritesSeenBy(KEY)
      append(KEY.copy(id = "last"), lastFailing)
      buffer.awaitWritesOf(KEY.copy(id = "last"))

      val failed = assertFailsWith<SessionException> { buffer.flushAll() }

      // Both failures fit the cap of two, so the retired writer took no slot.
      assertEquals(1, failed.suppressedExceptions.size)
    }

  @Test
  fun flushAll_twoSessionsFailed_reportsBothUntilEachSessionReportsItsOwn() =
    runBlocking<Unit> {
      val first = event()
      val second = event()
      store.fail(first, second)
      append(KEY, first)
      append(KEY.copy(id = "second"), second)

      val both = assertFailsWith<SessionException> { buffer.flushAll() }
      val again = assertFailsWith<SessionException> { buffer.flushAll() }
      assertFailsWith<SessionException> { buffer.flush(KEY) }
      val rest = assertFailsWith<SessionException> { buffer.flushAll() }

      assertEquals(1, both.suppressedExceptions.size)
      assertEquals(1, again.suppressedExceptions.size)
      assertEquals(0, rest.suppressedExceptions.size)
    }

  @Test
  fun flushAll_persistThrewError_reportsItAndLeavesItForTheSession() =
    runBlocking<Unit> {
      val stopped = bufferStoppedByError()

      val all = assertFailsWith<SessionException> { stopped.flushAll() }
      val own = assertFailsWith<SessionException> { stopped.flush(KEY) }
      stopped.flush(KEY)

      assertSame(storeError, all.cause)
      assertSame(storeError, own.cause)
    }

  @Test
  fun flushAll_errorStopsBufferWithMoreSessionsInFlightThanTheCap_reportsTheError() =
    runBlocking<Unit> {
      // Held in the store, so the stop cancels them after the Error is recorded.
      val held = List(3) { event() }
      store.block(*held.toTypedArray())
      val stopped =
        bufferStoppedByError(maxUnreportedFailures = 2) { stopping ->
          held.forEachIndexed { i, event ->
            val unused = stopping.append(Session(KEY.copy(id = "held-$i"))) { event }
          }
        }

      val failure = assertFailsWith<SessionException> { stopped.flushAll() }

      assertSame(storeError, failure.cause)
      assertEquals(3, failure.suppressedExceptions.size)
    }

  @Test
  fun flushAll_nothingFailed_runsBeforeReportOnceEveryWriteCompleted() =
    runBlocking<Unit> {
      store.persistDelay = 20.milliseconds
      val events = List(3) { event() }
      events.forEachIndexed { i, event -> append(KEY.copy(id = "session-$i"), event) }
      var persistedAtReport = emptyList<String>()

      buffer.flushAll { persistedAtReport = store.persistedIds }

      assertEquals(events.map { it.id }.toSet(), persistedAtReport.toSet())
    }

  @Test
  fun flushAll_afterWritesCompleted_leavesNoCoroutineRunning() =
    runBlocking<Unit> {
      append(KEY, event())
      append(KEY, event(mapOf("app:flag" to "on")))

      buffer.flushAll()

      val bufferJob = parent.children.single()
      withTimeout(5.seconds) { bufferJob.children.toList().joinAll() }
    }

  @Test
  fun close_calledTwice_flushesOnceAndRejectsLaterAppends() =
    runBlocking<Unit> {
      val event = event()
      store.persistDelay = 20.milliseconds
      append(KEY, event)
      var flushes = 0

      buffer.close { flushes++ }
      buffer.close { flushes++ }

      assertEquals(1, flushes)
      assertEquals(listOf(event.id), store.persistedIds)
      assertFailsWith<IllegalStateException> { append(KEY, event()) }
    }

  @Test
  fun close_failureOutstanding_throwsAndStillStops() =
    runBlocking<Unit> {
      val failing = event()
      store.fail(failing)
      append(KEY, failing)

      assertFailsWith<SessionException> { buffer.close() }

      assertTrue(parent.children.none(), "the buffer's coroutine scope is still active")
    }

  /** Appends [event] to a session under [key], where the test ignores the result. */
  private suspend fun append(key: SessionKey, event: Event) {
    val unused = buffer.append(Session(key)) { event }
  }

  /**
   * Returns a buffer stopped by [storeError], which a write of the session [KEY] threw after
   * [beforeError] ran on the new buffer.
   */
  private suspend fun bufferStoppedByError(
    maxUnreportedFailures: Int = Int.MAX_VALUE,
    beforeError: suspend (SessionWriteBuffer) -> Unit = {},
  ): SessionWriteBuffer {
    // Catches the Error, which would otherwise reach the global handler.
    val escaped = CompletableDeferred<Throwable>()
    val handler = CoroutineExceptionHandler { _, error -> escaped.complete(error) }
    val stopped =
      SessionWriteBuffer(
        Dispatchers.Default + parent + handler,
        store::persist,
        maxUnreportedFailures,
      )
    beforeError(stopped)
    val failing = event()
    store.fail(failing, cause = storeError)
    val unused = stopped.append(Session(KEY)) { failing }
    assertSame(storeError, escaped.await())
    return stopped
  }

  private companion object {
    const val APP = "app"
    const val USER = "user"
    const val OTHER_USER = "other-user"
    val KEY = SessionKey(APP, USER, "session")
    val OTHER_USER_KEY = SessionKey(APP, OTHER_USER, "other")

    // Long enough for a wait that does not cover a write to finish first.
    val SETTLE: Duration = 100.milliseconds

    fun event(stateDelta: Map<String, Any> = emptyMap()): Event =
      Event(
        author = "agent",
        invocationId = "inv",
        actions = EventActions(stateDelta = stateDelta.toMutableMap()),
      )
  }
}
