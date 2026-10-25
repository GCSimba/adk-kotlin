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

package com.google.adk.kt.agents

import com.google.adk.kt.SchemaUtils
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.agentToolDeclaration
import com.google.adk.kt.tools.inputSchemaOf
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.workflow.DynamicNodeFailedException
import kotlinx.coroutines.CancellationException

/**
 * Offers a [AgentMode.SINGLE_TURN] sub-agent to its parent as a tool: the call runs the sub-agent
 * as a child node of the parent's node on its own sub-branch and answers with its output.
 */
internal class SingleTurnAgentTool(private val agent: BaseAgent) :
  BaseTool(agent.name, agent.description) {

  private val inputSchema: Schema? by lazy { inputSchemaOf(agent) }

  override fun declaration(): FunctionDeclaration = agentToolDeclaration(inputSchema)

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val nodeInput =
      when (val schema = inputSchema) {
        null -> args[AgentTool.REQUEST_KEY]
        else ->
          SchemaUtils.validateValue(args, schema).getOrElse { e ->
            return "Error validating input: ${e.message}"
          }
      }
    // The tool runs inside its parent's node, which is where the sub-agent runs as a child.
    val parentNode =
      context.invocationContext.frameworkData.nodeContext
        ?: return "Error running sub-agent: single-turn agent '${agent.name}' can only run as a" +
          " tool of an agent in a workflow node."
    return try {
      parentNode.runNodeUnchecked(
        agent,
        nodeInput = nodeInput,
        runId = context.functionCallId,
        useSubBranch = true,
      ) ?: mapOf(BaseTool.RESULT_KEY to null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val cause = (e as? DynamicNodeFailedException)?.error ?: e
      "Error running sub-agent: ${cause.message}"
    }
  }
}
