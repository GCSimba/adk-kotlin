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

package com.google.adk.kt.agents

import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class ReadonlyContextImplTest {

  @Test
  fun readonlyContext_creation_setsDefaultValues() {
    val context =
      testInvocationContext(userContent = Content(role = Role.USER), invocationId = "invocation-id")
    val readonlyContext = context.toReadonlyContext()

    assertEquals("test_session_id", readonlyContext.session.key.id)
    assertEquals("test-agent", readonlyContext.agentName)
  }

  @Test
  fun readonlyContext_session_isDefensiveCopy() {
    val context = testInvocationContext(agent = DummyAgent("agent"), invocationId = "test-id")
    val readonlyContext = context.toReadonlyContext()

    assertNotSame(context.session, readonlyContext.session)
  }

  @Test
  fun readonlyContext_state_isDefensiveCopy() {
    val context = testInvocationContext(agent = DummyAgent("agent"), invocationId = "test-id")
    val readonlyContext = context.toReadonlyContext()

    assertNotSame(context.session.state, readonlyContext.state)
  }

  @Test
  fun readonlyContext_userContent_isDefensiveCopy() {
    val userContent = userMessage("test")
    val context =
      testInvocationContext(
        agent = DummyAgent("agent"),
        userContent = userContent,
        invocationId = "test-id",
      )
    val readonlyContext = context.toReadonlyContext()

    assertNotSame(context.userContent, readonlyContext.userContent)
    assertNotSame(context.userContent?.parts, readonlyContext.userContent?.parts)
  }
}
