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

import com.google.adk.kt.crypto.generateRandomString
import java.security.MessageDigest
import java.util.Base64

/** The length of a new code verifier; RFC 7636 allows 43 to 128. */
private const val CODE_VERIFIER_LENGTH = 48

internal actual fun s256PkceProvider(codeVerifier: String?): PkceProvider =
  S256PkceProvider(codeVerifier?.ifEmpty { null } ?: generateRandomString(CODE_VERIFIER_LENGTH))

/**
 * An `S256` [PkceProvider] that does not use google-auth's `DefaultPKCEProvider`. That class makes
 * a 172-character verifier with `=` padding, but RFC 7636 section 4.1 allows only 43 to 128
 * characters from `[A-Za-z0-9-._~]`. It also cannot take an existing verifier.
 */
private class S256PkceProvider(private val verifier: String) : PkceProvider {
  init {
    require(verifier.all { it.code < 0x80 }) { "PKCE code verifier must be ASCII." }
  }

  /** The URL-safe, unpadded base64 of the verifier's SHA-256 digest (RFC 7636 section 4.2). */
  private val challenge: String =
    Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
      )

  override fun getCodeVerifier(): String = verifier

  override fun getCodeChallenge(): String = challenge

  override fun getCodeChallengeMethod(): String = "S256"
}
