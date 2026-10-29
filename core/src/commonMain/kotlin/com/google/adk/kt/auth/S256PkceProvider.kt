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

/**
 * Returns a [PkceProvider] with the `S256` method for [codeVerifier], or for a new code verifier of
 * 48 letters and digits when that is null or empty.
 *
 * @throws IllegalArgumentException if [codeVerifier] is not ASCII. The rest of the RFC 7636 grammar
 *   (43 to 128 characters from `[A-Za-z0-9-._~]`) is not checked.
 */
internal expect fun s256PkceProvider(codeVerifier: String? = null): PkceProvider
