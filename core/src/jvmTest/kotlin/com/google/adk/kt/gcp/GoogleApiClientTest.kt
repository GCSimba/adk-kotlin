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

package com.google.adk.kt.gcp

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for the HTTP client that [GoogleApiClient.defaultHttpClient] builds. */
@RunWith(JUnit4::class)
class GoogleApiClientTest {

  private val server = MockWebServer()
  private val client = GoogleApiClient.defaultHttpClient()

  @Before
  fun setUp() {
    server.start()
  }

  @After
  fun tearDown() {
    client.close()
    server.close()
  }

  @Test
  fun defaultHttpClient_closesTheConnectionOfACancelledCall() {
    // A raw socket, since the test has to see the client close the connection.
    ServerSocket(0).use { listener ->
      val requestReceived = CompletableFuture<Socket>()
      val closed = CompletableFuture<Unit>()
      thread(isDaemon = true) {
        runCatching {
          listener.accept().use { socket ->
            // Never answers, so the call is still waiting when it is cancelled.
            readHead(socket.getInputStream())
            requestReceived.complete(socket)
            // Returns, or fails with a reset, once either side closes the connection.
            runCatching {
              val unused = socket.getInputStream().readAllBytes()
            }
          }
        }
        closed.complete(Unit)
      }

      runBlocking {
        val call = launch {
          // Cancelling closes the socket, so the call can end with a SocketException instead.
          val unusedResult = runCatching { client.get("http://localhost:${listener.localPort}/") }
        }
        val unused = withTimeout(5.seconds) { requestReceived.await() }
        call.cancelAndJoin()
      }

      // The Java engine left the connection open until the server answered.
      val closedByClient = runCatching { closed.get(5, TimeUnit.SECONDS) }.isSuccess
      requestReceived.getNow(null)?.close()
      assertThat(closedByClient).isTrue()
    }
  }

  @Test
  fun defaultHttpClient_leavesNoThreadThatKeepsTheJvmAlive() {
    server.enqueue(MockResponse())
    val before = nonDaemonThreads()

    val unused = runBlocking { client.get(server.url("/").toString()) }

    // OkHttp's default dispatcher threads would hold the JVM open for a minute after the call.
    val added = (nonDaemonThreads() - before).map { it.name }
    assertThat(added.filter { it.startsWith("OkHttp") || it.startsWith("ADK") }).isEmpty()
  }

  @Test
  fun defaultHttpClient_doesNotResendAPostTheServerRead() {
    val url = server.url("/").toString()
    server.enqueue(MockResponse())
    // Reads the whole POST on the reused connection, then drops it without answering.
    server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
    server.enqueue(MockResponse())

    runBlocking {
      // Leaves the first connection in the pool for the POST to reuse.
      val unused = client.get(url)
      val unusedResult = runCatching { client.post(url) { setBody("{}") } }
    }

    // The POST has to reuse the pooled connection, the path where OkHttp resent it.
    val get = server.takeRequest()
    assertThat(server.takeRequest().connectionIndex).isEqualTo(get.connectionIndex)
    // A resend would run the same write twice.
    assertThat(server.requestCount).isEqualTo(2)
  }

  /** Reads a request up to the blank line that ends its headers. */
  private fun readHead(input: InputStream) {
    val reader = input.bufferedReader()
    while (reader.readLine()?.isNotEmpty() == true) {}
  }

  private fun nonDaemonThreads(): Set<Thread> =
    Thread.getAllStackTraces().keys.filter { it.isAlive && !it.isDaemon }.toSet()
}
