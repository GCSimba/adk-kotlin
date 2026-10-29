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

/** Provides the PKCE (RFC 7636) values of one authorization request. */
internal interface PkceProvider {
  /** Returns the code verifier, the secret sent with the token request. */
  fun getCodeVerifier(): String

  /** Returns the code challenge sent with the authorization request. */
  fun getCodeChallenge(): String

  /** Returns the method that derived the code challenge from the code verifier, such as `S256`. */
  fun getCodeChallengeMethod(): String
}
