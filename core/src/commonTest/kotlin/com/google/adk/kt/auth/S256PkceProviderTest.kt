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

package com.google.adk.kt.auth

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class S256PkceProviderTest {

  @Test
  fun s256PkceProvider_called_returnsS256Method() {
    assertEquals("S256", s256PkceProvider().getCodeChallengeMethod())
  }

  @Test
  fun s256PkceProvider_emptyVerifier_makesANewOne() {
    assertEquals(48, s256PkceProvider("").getCodeVerifier().length)
  }

  @Test
  fun s256PkceProvider_givenVerifier_keepsIt() {
    assertEquals("w".repeat(43), s256PkceProvider("w".repeat(43)).getCodeVerifier())
  }

  @Test
  fun s256PkceProvider_noVerifier_makes48LettersAndDigits() {
    val verifier = s256PkceProvider().getCodeVerifier()

    assertEquals(48, verifier.length)
    assertTrue(
      verifier.matches(Regex("[A-Za-z0-9]+")),
      "verifier has a character outside [A-Za-z0-9]",
    )
  }

  @Test
  fun s256PkceProvider_nonAsciiVerifier_throwsWithoutTheVerifier() {
    val verifier = "verifier-\u00e9"

    val e = assertFailsWith<IllegalArgumentException> { s256PkceProvider(verifier) }

    assertFalse(verifier in e.message.orEmpty())
  }

  @Test
  fun s256PkceProvider_rfc7636AppendixBVerifier_returnsRfcChallenge() {
    val provider = s256PkceProvider("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")

    assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", provider.getCodeChallenge())
  }

  @Test
  fun s256PkceProvider_twoCalls_makeDifferentVerifiers() {
    assertNotEquals(s256PkceProvider().getCodeVerifier(), s256PkceProvider().getCodeVerifier())
  }
}
