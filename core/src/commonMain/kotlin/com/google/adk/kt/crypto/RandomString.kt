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

private val LETTERS_AND_DIGITS: String = (('A'..'Z') + ('a'..'z') + ('0'..'9')).joinToString("")

/**
 * Returns [length] characters, each drawn from [alphabet] by a secure random generator. The default
 * alphabet is the ASCII letters and digits.
 */
internal fun generateRandomString(
  length: Int,
  alphabet: CharSequence = LETTERS_AND_DIGITS,
): String = CharArray(length) { alphabet.random(secureRandom) }.concatToString()
