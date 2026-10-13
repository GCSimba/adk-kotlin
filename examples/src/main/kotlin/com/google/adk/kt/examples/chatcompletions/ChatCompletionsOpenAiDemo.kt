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

package com.google.adk.kt.examples.chatcompletions

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.models.openai.ChatCompletions
import kotlin.math.sqrt

/**
 * Example agent on an OpenAI model through OpenAI's API, ported from adk-python's
 * `hello_world_openai` sample.
 *
 * [ChatCompletions] needs `OPENAI_API_KEY` unless `OPENAI_BASE_URL` points at a server that takes
 * no key. Set `OPENAI_MODEL` for another model.
 */
object ChatCompletionsOpenAiDemo {
  private const val DEFAULT_MODEL = "gpt-4.1"

  @JvmField
  val rootAgent: LlmAgent =
    LlmAgent(
      name = "openai_agent",
      model = ChatCompletions(name = System.getenv("OPENAI_MODEL") ?: DEFAULT_MODEL),
      description =
        "A hello-world agent powered by an OpenAI model that rolls dice and checks whether " +
          "numbers are prime.",
      instruction =
        Instruction(
          """
          You are a helpful assistant powered by OpenAI that can roll dice and check whether
          numbers are prime.
          When asked to roll a die, call the rollDie tool with the integer number of sides. Never
          roll a die yourself.
          When asked to check primes, call the checkPrime tool with a list of integers. Never
          decide primality yourself.
          When asked to roll a die and then check the result, first call rollDie, wait for its
          result, then call checkPrime with that result. Always report the number you rolled.
          """
            .trimIndent()
        ),
      tools = DiceTools().generatedTools(),
    )
}

/** Dice and prime tools for [ChatCompletionsOpenAiDemo]. */
class DiceTools {

  /** Rolls a die and returns the result. */
  @Tool fun rollDie(@Param("The number of sides the die has") sides: Int): Int = (1..sides).random()

  /** Checks which of the given numbers are prime. */
  @Tool
  fun checkPrime(@Param("The numbers to check") nums: List<Int>): String {
    val primes =
      nums.filter { n -> n > 1 && (2..sqrt(n.toDouble()).toInt()).none { n % it == 0 } }.toSet()
    return if (primes.isEmpty()) {
      "No prime numbers found."
    } else {
      "${primes.joinToString(", ")} are prime numbers."
    }
  }
}
