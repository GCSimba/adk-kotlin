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

package com.google.adk.kt.examples.chatcompletions;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.openai.ChatCompletions;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Java port of the Chat Completions OpenAI demo, itself ported from adk-python's {@code
 * hello_world_openai} sample, with the tools built by {@link ReflectiveTools}. {@link
 * ChatCompletions} needs {@code OPENAI_API_KEY} unless {@code OPENAI_BASE_URL} points at a server
 * that takes no key; set {@code OPENAI_MODEL} for another model.
 */
public final class ChatCompletionsOpenAiDemoJava {

  private static final String DEFAULT_MODEL = "gpt-4.1";

  /**
   * Dice and prime tools. {@link ReflectiveTools} takes no list parameters, so {@code checkPrime}
   * checks one number.
   */
  public static final class DiceTools {
    @Tool(description = "Rolls a die and returns the result.")
    public int rollDie(
        @Param(name = "sides", description = "The number of sides the die has") int sides) {
      return ThreadLocalRandom.current().nextInt(1, sides + 1);
    }

    @Tool(description = "Checks whether a number is prime.")
    public String checkPrime(@Param(name = "num", description = "The number to check") int num) {
      boolean prime = num > 1;
      for (int i = 2; prime && (long) i * i <= num; i++) {
        prime = num % i != 0;
      }
      return num + (prime ? " is a prime number." : " is not a prime number.");
    }
  }

  public static final BaseAgent rootAgent = rootAgent();

  private static BaseAgent rootAgent() {
    String model = System.getenv("OPENAI_MODEL");
    DiceTools tools = new DiceTools();
    return LlmAgent.builder()
        .name("openai_agent")
        .model(new ChatCompletions(model == null ? DEFAULT_MODEL : model))
        .description(
            "A hello-world agent powered by an OpenAI model that rolls dice and checks whether"
                + " numbers are prime.")
        .instruction(
            """
            You are a helpful assistant powered by OpenAI that can roll dice and check whether
            numbers are prime.
            When asked to roll a die, call the rollDie tool with the integer number of sides. Never
            roll a die yourself.
            When asked to check primes, call the checkPrime tool once for each number. Never
            decide primality yourself.
            When asked to roll a die and then check the result, first call rollDie, wait for its
            result, then call checkPrime with that result. Always report the number you rolled.\
            """)
        .tools(
            ReflectiveTools.fromMethod(tools, "rollDie"),
            ReflectiveTools.fromMethod(tools, "checkPrime"))
        .build();
  }

  private ChatCompletionsOpenAiDemoJava() {}
}
