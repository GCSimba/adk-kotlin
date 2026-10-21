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

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.AdkJavaInteropApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeInfoTest {

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val nodeInfo =
      NodeInfo(path = "wf@1/a@1", outputFor = listOf("wf@1/a@1"), messageAsOutput = true)

    assertEquals(nodeInfo.copy(), nodeInfo.toBuilder().build())
    assertEquals(
      nodeInfo.copy(messageAsOutput = false),
      nodeInfo.toBuilder().messageAsOutput(false).build(),
    )
  }

  @Test
  fun name_isTheLastSegmentWithoutItsRunId() {
    assertEquals("b", NodeInfo(path = "wf@1/a@2/b@3").name)
    assertEquals("b", NodeInfo(path = "wf@1/b").name)
    assertEquals("wf", NodeInfo(path = "wf@1").name)
  }

  @Test
  fun runId_isTheLastSegmentsRunId() {
    assertEquals("3", NodeInfo(path = "wf@1/a@2/b@3").runId)
  }

  @Test
  fun runId_nullWithoutARunId() {
    assertNull(NodeInfo(path = "wf@1/b").runId)
  }

  @Test
  fun parentRunId_isTheSecondToLastSegmentsRunId() {
    assertEquals("2", NodeInfo(path = "wf@1/a@2/b@3").parentRunId)
    assertEquals("1", NodeInfo(path = "wf@1/b").parentRunId)
  }

  @Test
  fun parentRunId_nullWithoutAParentRunId() {
    assertNull(NodeInfo(path = "wf@1").parentRunId)
    assertNull(NodeInfo(path = "wf/b@1").parentRunId)
  }

  @Test
  fun pathHelpers_nullForAnEmptyPath() {
    val nodeInfo = NodeInfo()

    assertNull(nodeInfo.name)
    assertNull(nodeInfo.runId)
    assertNull(nodeInfo.parentRunId)
  }
}
