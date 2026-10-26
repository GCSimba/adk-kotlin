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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.userFunctionResponse
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Runs [workflow] through [InMemoryRunner] on the user [message]: its events and final session. */
private fun runApp(workflow: Workflow, message: String = "go"): Pair<List<Event>, Session> =
  runBlocking {
    val runner = InMemoryRunner(App(appName = "relaxed", rootNode = workflow))
    val newMessage = Content.fromText(Role.USER, message)
    val events = runner.runAsync(userId = "u", sessionId = "s", newMessage = newMessage).toList()
    events to checkNotNull(runner.sessionService.getSession(SessionKey("relaxed", "u", "s")))
  }

/** The last output [workflow] produces through a runner. */
private fun lastOutput(workflow: Workflow, message: String = "go"): Any? =
  runApp(workflow, message).first.last { it.output != null }.output

private fun chain(vararg nodes: Node): Workflow =
  Workflow(name = "wf", edges = (listOf(Start) + nodes).zipWithNext { a, b -> Edge(a, b) })

/** How values are read where they enter a schema, end to end through a runner. */
class RelaxedInputsRunnerTest {

  @Test
  fun stateSchema_quotedInteger_storesTheCheckedValue() {
    // Arrange
    val emitted = mutableMapOf<String, Any>("total" to "4")
    val write =
      node<Any?, Unit>("write") { ctx, _ ->
        ctx.updateState("count", "3")
        emit(Event(author = "write", actions = EventActions(stateDelta = emitted)))
      }
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, write)),
        stateSchema =
          Schema(
            type = Type.OBJECT,
            properties =
              mapOf("count" to Schema(type = Type.INTEGER), "total" to Schema(type = Type.INTEGER)),
          ),
      )

    // Act
    val session = runApp(workflow).second

    // Assert
    assertEquals(3L, session.state["count"])
    assertEquals(4L, session.state["total"])
    assertEquals(mapOf<String, Any>("total" to "4"), emitted)
  }

  @Test
  fun outputSchemas_valueThatFits_outputTheCheckedValue() {
    // Arrange
    val countSchema =
      Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))
    val colorSchema = Schema(type = Type.STRING, enum = listOf("red"))
    val customNode = Emitter("emit", mapOf("count" to 4.0))
    // A next node shows the checked output a successor receives.
    fun workflowOutput(value: Any, schema: Schema): Workflow {
      val inner =
        Workflow(
          name = "inner",
          edges = listOf(Edge(Start, node<Any?, Any?>("n") { _, _ -> value })),
          outputSchema = schema,
        )
      return chain(inner, node<Any?, Any?>("next") { _, v -> v })
    }

    // Act
    val fromCustom = lastOutput(chain(CheckedEmitter(customNode, countSchema)))
    val fromWorkflow = lastOutput(workflowOutput(mapOf("count" to "5"), countSchema))
    val inEnum = lastOutput(workflowOutput("red", colorSchema))
    val outOfEnum =
      assertFailsWith<IllegalArgumentException> { runApp(workflowOutput("blue", colorSchema)) }

    // Assert
    assertEquals(mapOf("count" to 4L), fromCustom)
    assertEquals(mapOf("count" to 5L), fromWorkflow)
    assertEquals("red", inEnum)
    assertContains(outOfEnum.message.orEmpty(), "output of workflow 'inner' does not match")
  }

  /** A workflow's checked output reaches a node that delegated to it the same, live and resumed. */
  @Test
  fun delegatedWorkflowOutput_afterAResume_isTheCheckedValueAsLive() {
    // Arrange: d delegates its output to a workflow whose schema reads "5" as 5; a sibling of d
    // finishes at once (live) or asks for input (resumed).
    val countSchema =
      Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))
    fun graph(sibling: Node): Workflow {
      val inner =
        Workflow(
          name = "inner",
          edges = listOf(Edge(Start, Emitter("n", mapOf("count" to "5")))),
          outputSchema = countSchema,
        )
      val d = Delegator("d", inner)
      val join = JoinNode("j")
      return Workflow(
        name = "wf",
        edges = listOf(Edge(Start, d), Edge(Start, sibling), Edge(d, join), Edge(sibling, join)),
      )
    }
    val session = ResumableSession(graph(Asker("ask")))
    val first = session.turn(Content.fromText(Role.USER, "go"))

    // Act
    val live = runApp(graph(Emitter("ask", mapOf("ok" to true)))).first
    val resumed =
      session.turn(
        userFunctionResponse(RequestInput.FUNCTION_CALL_NAME, "ask_1", mapOf("ok" to true))
      )

    // Assert
    fun fromD(events: List<Event>) =
      (events.single { it.nodeInfo?.path == "wf@1/j@1" && it.output != null }.output as Map<*, *>)[
        "d"]
    assertEquals(mapOf("count" to 5L), fromD(live))
    assertEquals(mapOf("count" to 5L), fromD(resumed))
    // The workflow sends the checked value before it marks its own end.
    val innerOutput = first.indexOfFirst {
      it.output != null && it.nodeInfo?.path.orEmpty().endsWith("/inner@c")
    }
    val innerEnd = first.indexOfFirst { it.author == "inner" && it.actions.endOfAgent == true }
    assertTrue(innerOutput in 0 until innerEnd)
  }

  /** A non-resumable workflow's in-turn retry replays a delegating node with the checked value. */
  @Test
  fun delegatedWorkflowOutput_afterAnInTurnRetry_isTheCheckedValue() {
    // Arrange: d delegates its output to a workflow whose schema reads "5" as 5. Its successor
    // fails once, so wf retries in the same turn and replays d from the session.
    val inner =
      Workflow(
        name = "inner",
        edges = listOf(Edge(Start, Emitter("n", "5"))),
        outputSchema = Schema(type = Type.INTEGER),
      )
    val d = Delegator("d", inner)
    var runs = 0
    val next = node<Any?, Any?>("next") { _, value -> if (++runs == 1) error("flaky") else value }
    val retry = RetryConfig(maxAttempts = 2, initialDelay = Duration.ZERO, jitter = 0.0)
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, d), Edge(d, next)),
        config = NodeConfig(retryConfig = retry),
      )

    // Act
    val events = runApp(workflow).first

    // Assert: n ran once, so d replayed, and next got the checked value rather than the raw one.
    fun outputsAt(path: String) =
      events.filter { it.nodeInfo?.path == path }.mapNotNull { it.output }
    assertEquals(listOf<Any>("5"), outputsAt("wf@1/d@1/inner@c/n@1"))
    assertEquals(listOf<Any>(5L), outputsAt("wf@1/next@1"))
  }

  /** A root workflow's schema changes its output without an output event of the workflow's own. */
  @Test
  fun workflowOutputSchema_rootWorkflow_sendsNoOutputEventOfItsOwn() {
    // Arrange
    val countSchema =
      Schema(type = Type.OBJECT, properties = mapOf("count" to Schema(type = Type.INTEGER)))
    val workflow =
      Workflow(
        name = "wf",
        edges = listOf(Edge(Start, Emitter("n", mapOf("count" to "5")))),
        outputSchema = countSchema,
      )

    // Act
    val events = runApp(workflow).first

    // Assert
    assertEquals(listOf("wf@1/n@1"), events.filter { it.output != null }.map { it.nodeInfo?.path })
  }
}

/** Delegates its output to [child] with `useAsOutput`. */
private class Delegator(override val name: String, private val child: Node) : Node {
  override val rerunOnResume: Boolean = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    val unused = context.runNode(child, runId = "c", useAsOutput = true)
  }
}

/** Asks for input under the interrupt id `ask_1`. */
private class Asker(override val name: String) : Node {
  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(RequestInput(interruptId = "ask_1", message = "?"))
  }
}

/** A runner over a resumable app rooted on [root], driven a turn at a time. */
private class ResumableSession(root: Node) {
  private val runner =
    InMemoryRunner(
      App(
        appName = "relaxed",
        rootNode = root,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )
    )

  fun turn(message: Content): List<Event> = runBlocking {
    runner.runAsync(userId = "u", sessionId = "s", newMessage = message).toList()
  }
}

/** Declares [outputSchema] on a node that emits what [emitter] emits. */
private class CheckedEmitter(private val emitter: Emitter, override val outputSchema: Schema) :
  Node by emitter
