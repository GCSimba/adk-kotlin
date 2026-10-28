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

package com.google.adk.kt.crypto

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test

class RandomStringTest {

  @Test
  fun generateRandomString_givenAlphabet_drawsOnlyFromIt() {
    // 200 draws miss one of two characters with probability 2^-199.
    val randomString = generateRandomString(200, alphabet = "ab")

    assertEquals(setOf('a', 'b'), randomString.toSet())
  }

  @Test
  fun generateRandomString_givenLength_returnsThatManyCharacters() {
    assertEquals(listOf(0, 1, 48), listOf(0, 1, 48).map { generateRandomString(it).length })
  }

  @Test
  fun generateRandomString_manyCharacters_coversWholeAlphabet() {
    // 6,200 draws miss a given character with probability (61/62)^6200, about 1e-44.
    val randomString = generateRandomString(6_200)

    assertEquals((('A'..'Z') + ('a'..'z') + ('0'..'9')).toSet(), randomString.toSet())
  }

  @Test
  fun generateRandomString_twoCalls_differ() {
    assertNotEquals(generateRandomString(48), generateRandomString(48))
  }
}
