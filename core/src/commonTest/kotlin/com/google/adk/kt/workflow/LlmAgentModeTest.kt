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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.agents.AgentMode
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Answers each request with the next scripted turn, repeating the last, and records the requests.
 */
private class ModeScriptedModel(private vararg val turns: Content) : Model {
  override val name = "mode-scripted"
  val requests = mutableListOf<LlmRequest>()

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    val turn = turns.getOrElse(requests.size) { turns.last() }
    requests.add(request)
    return flowOf(LlmResponse(content = turn))
  }

  fun declaredToolNames(): List<String> =
    requests
      .first()
      .config
      .tools
      .orEmpty()
      .flatMap { it.functionDeclarations.orEmpty() }
      .map { it.name }
}

/** Records the input it receives and emits it back as its output. */
private class ModeCaptureNode(override val name: String = "capture") : Node {
  var received: Any? = null

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    received = nodeInput
    emit(nodeInput)
  }
}

/** Runs [target] as a child node in the isolation scope [scope]. */
private class ModeScopedDriverNode(private val target: Node, private val scope: String) : Node {
  override val name = "driver"
  override val rerunOnResume = true

  override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
    emit(context.runNode(target, overrideIsolationScope = scope))
  }
}

private fun call(name: String, args: Map<String, Any?>): Content =
  Content(Role.MODEL, listOf(Part(functionCall = FunctionCall(name, args))))

private fun text(text: String): Content = Content(Role.MODEL, listOf(Part(text = text)))

private fun coordinator(
  model: Model,
  subAgents: List<BaseAgent>,
  tools: List<BaseTool> = emptyList(),
) =
  LlmAgent(
    name = "root_agent",
    model = model,
    subAgents = subAgents,
    tools = tools,
    mode = AgentMode.CHAT,
  )

/** Runs [root] as the start node of a workflow, where its chat mode keeps the conversation. */
private fun runnerFor(root: BaseAgent, resumable: Boolean = false) =
  InMemoryRunner(
    app =
      App(
        "app",
        rootNode = Workflow("wf", listOf(Edge(Start, root))),
        resumabilityConfig = if (resumable) ResumabilityConfig(isResumable = true) else null,
      )
  )

private suspend fun InMemoryRunner.send(message: String): List<Event> =
  runAsync("u", "s", newMessage = Content.fromText(Role.USER, message)).toList()

private suspend fun runWorkflow(message: String, vararg edges: Edge): List<Event> =
  InMemoryRunner(app = App("app", rootNode = Workflow("wf", edges.toList())))
    .runAsync("u", "s", newMessage = Content.fromText(Role.USER, message))
    .toList()

private fun List<Event>.contentful() = filter { !it.content?.parts.isNullOrEmpty() }

class LlmAgentModeTest {

  @Test
  fun defaultMode_parentlessAgentWithSubAgents_isChat() {
    // Arrange: as in ADK Python, sub-agents make a parentless agent a chat coordinator.
    val helper = LlmAgent("helper", ModeScriptedModel(), mode = AgentMode.SINGLE_TURN)
    val coordinator = LlmAgent("coordinator", ModeScriptedModel(), subAgents = listOf(helper))

    // Act
    val mode = coordinator.defaultMode()

    // Assert
    assertEquals(AgentMode.CHAT, mode)
  }

  @Test
  fun defaultMode_parentlessAgentWithoutSubAgents_isSingleTurn() {
    // Arrange
    val agent = LlmAgent("solo", ModeScriptedModel())

    // Act
    val mode = agent.defaultMode()

    // Assert
    assertEquals(AgentMode.SINGLE_TURN, mode)
  }

  @Test
  fun subAgentMode_decidesToolOrTransferTarget() = runBlocking {
    // Arrange
    val model = ModeScriptedModel(text("done"))
    val chat = LlmAgent("chat_sub", model, description = "Chats.", mode = AgentMode.CHAT)
    val single =
      LlmAgent("single_sub", model, description = "Answers.", mode = AgentMode.SINGLE_TURN)
    val root = coordinator(model, listOf(chat, single))

    // Act
    val unused = runnerFor(root).send("hi")

    // Assert
    assertEquals(listOf("transfer_to_agent", "single_sub"), model.declaredToolNames())
    val instruction = model.requests.first().config.systemInstruction?.text().orEmpty()
    assertContains(instruction, "`chat_sub`.")
    assertFalse("single_sub`" in instruction)
  }

  @Test
  fun chatMode_runInScope_seesItsOwnToolTurns() = runBlocking {
    // Arrange: a chat agent run in a scope calls a tool, then answers.
    val model = ModeScriptedModel(call("lookup", emptyMap()), text("done"))
    val lookup = DummyTool("lookup", onRun = { _, _ -> mapOf("ok" to true) })
    val chat = LlmAgent("chat_agent", model, mode = AgentMode.CHAT, tools = listOf(lookup))

    // Act
    val unused = runWorkflow("go", Edge(Start, ModeScopedDriverNode(chat, scope = "scope-1")))

    // Assert: its scoped tool call and response reach its next request.
    val second = model.requests[1].contents.flatMap { it.parts }
    assertTrue(second.any { it.functionCall?.name == "lookup" })
    assertTrue(second.any { it.functionResponse?.name == "lookup" })
  }

  @Test
  fun chatMode_singleTurnHelperTurns_stayOutOfLaterRequests() = runBlocking {
    // Arrange: the root calls a single-turn helper as a tool, then answers.
    val rootModel =
      ModeScriptedModel(call("helper", mapOf("request" to "look it up")), text("done"), text("ok"))
    val helper =
      LlmAgent(
        "helper",
        ModeScriptedModel(text("helper says hi")),
        description = "Does helper.",
        mode = AgentMode.SINGLE_TURN,
      )
    val runner = runnerFor(coordinator(rootModel, listOf(helper)))
    val unused = runner.send("go")

    // Act
    val unusedFollowUp = runner.send("again")

    // Assert: as in Python, the root sees the helper's function response but not its own turns.
    val parts = rootModel.requests.last().contents.flatMap { it.parts }
    assertTrue(parts.any { it.functionResponse?.name == "helper" })
    assertTrue(parts.mapNotNull { it.text }.none { "helper says hi" in it })
  }

  @Test
  fun singleTurnTool_parentCall_isAnsweredByChildNode() = runBlocking {
    // Arrange
    val helper =
      LlmAgent(
        "helper",
        ModeScriptedModel(text("helper result")),
        description = "A helper.",
        mode = AgentMode.SINGLE_TURN,
      )
    val root =
      coordinator(
        ModeScriptedModel(call("helper", mapOf("request" to "hi")), text("all done")),
        listOf(helper),
      )

    // Act
    val events = runnerFor(root).send("go").contentful()

    // Assert
    assertEquals(
      listOf("root_agent", "helper", "root_agent", "root_agent"),
      events.map { it.author },
    )
    assertEquals(
      mapOf("result" to "helper result"),
      events[2].functionResponses().single().response,
    )
  }

  @Test
  fun singleTurnTool_helperPausedOnLongRunningCall_resumesThroughTheParentsCall() = runBlocking {
    // Arrange: the helper pauses inside its parent's tool call, with and without resumability.
    for (resumable in listOf(false, true)) {
      val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
      val helper =
        LlmAgent(
          "helper",
          ModeScriptedModel(call("approve", emptyMap()), text("approved")),
          description = "A helper.",
          tools = listOf(approve),
          mode = AgentMode.SINGLE_TURN,
        )
      val runner =
        runnerFor(
          coordinator(
            ModeScriptedModel(call("helper", mapOf("request" to "hi")), text("all done")),
            listOf(helper),
          ),
          resumable = resumable,
        )
      val approvalId =
        runner.send("go").flatMap { it.functionCalls() }.single { it.name == "approve" }.id
      val answer =
        Content(
          Role.USER,
          listOf(
            Part(
              functionResponse =
                FunctionResponse(id = approvalId, name = "approve", response = mapOf("ok" to true))
            )
          ),
        )

      // Act
      val second = runner.runAsync("u", "s", newMessage = answer).toList()

      // Assert: as in Python, the parent replays its helper call, which finishes and answers it.
      assertEquals(
        mapOf("result" to "approved"),
        second.flatMap { it.functionResponses() }.single { it.name == "helper" }.response,
        "resumable=$resumable",
      )
      assertEquals("all done", second.contentful().last().content?.parts?.single()?.text)
    }
  }

  @Test
  fun singleTurnTool_helperPausedBesideAToolCall_answersBothCallsOnResume() = runBlocking {
    // Arrange: one model turn calls a regular tool and the helper, which pauses on an approval.
    val lookup = DummyTool("lookup", onRun = { _, _ -> mapOf("ok" to true) })
    val approve = DummyTool("approve", isLongRunning = true, onRun = { _, _ -> Unit })
    val helper =
      LlmAgent(
        "helper",
        ModeScriptedModel(call("approve", emptyMap()), text("approved")),
        description = "A helper.",
        tools = listOf(approve),
        mode = AgentMode.SINGLE_TURN,
      )
    val bothCalls =
      Content(
        Role.MODEL,
        listOf(
          Part(functionCall = FunctionCall("lookup", emptyMap())),
          Part(functionCall = FunctionCall("helper", mapOf("request" to "hi"))),
        ),
      )
    val rootModel = ModeScriptedModel(bothCalls, text("all done"))
    val runner = runnerFor(coordinator(rootModel, listOf(helper), tools = listOf(lookup)))
    val approvalId =
      runner.send("go").flatMap { it.functionCalls() }.single { it.name == "approve" }.id
    val answer =
      Content(
        Role.USER,
        listOf(
          Part(
            functionResponse =
              FunctionResponse(id = approvalId, name = "approve", response = mapOf("ok" to true))
          )
        ),
      )

    // Act
    val unused = runner.runAsync("u", "s", newMessage = answer).toList()

    // Assert: as in Python, the next request answers both calls, not only the helper's.
    val answered =
      rootModel.requests
        .last()
        .contents
        .flatMap { it.parts }
        .mapNotNull { it.functionResponse?.name }
    assertTrue("lookup" in answered && "helper" in answered, "$answered")
  }

  @Test
  fun singleTurnAgent_outsideANode_getsNoIdentityOrTransferInstructions() = runBlocking {
    // Arrange: a single-turn agent runs as a sequential step, so no node sets its mode.
    val model = ModeScriptedModel(text("done"))
    val step = LlmAgent("step", model, description = "A step.", mode = AgentMode.SINGLE_TURN)
    val runner =
      InMemoryRunner(
        app =
          App(
            "app",
            rootNode =
              Workflow(
                "wf",
                listOf(Edge(Start, SequentialAgent("steps", subAgents = listOf(step)))),
              ),
          )
      )

    // Act
    val unused = runner.send("go")

    // Assert: as in Python, its own mode decides both, so it gets neither.
    val instruction = model.requests.single().config.systemInstruction?.text().orEmpty()
    assertTrue("Your internal name is" !in instruction, instruction)
    assertTrue("other agents to transfer to" !in instruction, instruction)
  }

  @Test
  fun singleTurnAgent_outsideANode_keepsItsHistory() = runBlocking {
    // Arrange: a single-turn agent runs as a sequential step, so no node makes it history-free.
    val model = ModeScriptedModel(text("one"), text("two"))
    val step = LlmAgent("step", model, mode = AgentMode.SINGLE_TURN)
    val runner =
      InMemoryRunner(
        app =
          App(
            "app",
            rootNode =
              Workflow(
                "wf",
                listOf(Edge(Start, SequentialAgent("steps", subAgents = listOf(step)))),
              ),
          )
      )
    val unused = runner.send("first")

    // Act
    val unusedSecond = runner.send("second")

    // Assert: as in Python, only a node placement drops history, so the step sees the first turn.
    val texts = model.requests.last().contents.flatMap { it.parts }.mapNotNull { it.text }
    assertTrue("first" in texts, "$texts")
  }

  @Test
  fun chatAgentMadeByAParentBuiltAfterTheWorkflow_isRejectedWhenTheWorkflowRuns() {
    // Arrange: the agent has no parent while the graph is built, so it passes as single-turn.
    val model = ModeScriptedModel(text("unused"))
    val agent = LlmAgent("late_chat", model)
    val capture = ModeCaptureNode()
    val workflow = Workflow("wf", listOf(Edge(Start, capture), Edge(capture, agent)))
    val unusedParent =
      LlmAgent("parent", ModeScriptedModel(text("unused")), subAgents = listOf(agent))
    val runner = InMemoryRunner(app = App("app", rootNode = workflow))

    // Act
    val error = assertFailsWith<GraphValidationException> { runBlocking { runner.send("go") } }

    // Assert
    assertContains(error.message.orEmpty(), "'late_chat'")
    assertTrue(model.requests.isEmpty())
  }

  @Test
  fun singleTurnTool_inputBreakingSchema_answersWithError() = runBlocking {
    // Arrange: the helper takes an integer count, and the parent passes the count as text.
    val helperModel = ModeScriptedModel(text("unused"))
    val helper =
      LlmAgent(
        "helper",
        helperModel,
        description = "A helper.",
        inputSchema =
          Schema(
            type = Type.OBJECT,
            properties = mapOf("count" to Schema(type = Type.INTEGER)),
            required = listOf("count"),
          ),
        mode = AgentMode.SINGLE_TURN,
      )
    val root =
      coordinator(
        ModeScriptedModel(call("helper", mapOf("count" to "3")), text("all done")),
        listOf(helper),
      )

    // Act
    val events = runnerFor(root).send("go").contentful()

    // Assert: the call is answered with the validation error, and the helper never runs.
    val response = events.flatMap { it.functionResponses() }.single { it.name == "helper" }
    assertContains(response.response["result"].toString(), "Error validating input")
    assertTrue(helperModel.requests.isEmpty())
  }

  @Test
  fun singleTurnTool_outsideAWorkflowNode_answersWithError() = runBlocking {
    // Arrange: a sequential root runs the coordinator in the classic flow, outside any node.
    val helperModel = ModeScriptedModel(text("unused"))
    val helper =
      LlmAgent("helper", helperModel, description = "A helper.", mode = AgentMode.SINGLE_TURN)
    val coordinator =
      LlmAgent(
        name = "root_agent",
        model = ModeScriptedModel(call("helper", mapOf("request" to "hi")), text("all done")),
        subAgents = listOf(helper),
      )
    val root = SequentialAgent(name = "steps", subAgents = listOf(coordinator))

    // Act
    val events = InMemoryRunner(app = App("app", rootAgent = root)).send("go")

    // Assert: the call is answered with an error, the turn goes on, and the helper never runs.
    val response = events.flatMap { it.functionResponses() }.single { it.name == "helper" }
    assertContains(response.response["result"].toString(), "Error running sub-agent")
    assertEquals("all done", events.contentful().last().contentText())
    assertTrue(helperModel.requests.isEmpty())
  }

  @Test
  fun graphOf_subAgentWithoutModeAfterNonStartNode_isRejected() {
    // Arrange: a sub-agent without a mode runs as a chat agent.
    val helper = LlmAgent("helper", ModeScriptedModel(text("unused")))
    val unusedParent = coordinator(ModeScriptedModel(text("unused")), listOf(helper))
    val capture = ModeCaptureNode()

    // Act
    val error =
      assertFailsWith<GraphValidationException> {
        Workflow("bad", listOf(Edge(Start, capture), Edge(capture, helper)))
      }

    // Assert
    assertContains(error.message.orEmpty(), "AgentMode.CHAT following node 'capture'")
  }

  @Test
  fun graphOf_chatAgentAfterNonStartNode_isRejected() {
    // Arrange
    val chatter = LlmAgent("chatter", ModeScriptedModel(text("unused")), mode = AgentMode.CHAT)
    val capture = ModeCaptureNode()

    // Act
    val unused = Workflow("ok", listOf(Edge(Start, chatter)))
    val error =
      assertFailsWith<GraphValidationException> {
        Workflow("bad", listOf(Edge(Start, capture), Edge(capture, chatter)))
      }

    // Assert
    assertContains(error.message.orEmpty(), "AgentMode.CHAT following node 'capture'")
  }
}
