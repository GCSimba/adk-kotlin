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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalAuthApi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, AdkJavaInteropApi::class)
class AuthCredentialTest {

  @Test
  fun toString_apiKey_redactsKey() {
    assertEquals("ApiKey(apiKey=<redacted>)", API_KEY.toString())
  }

  @Test
  fun toString_http_redactsSecretFields() {
    assertEquals(
      "Http(scheme=basic, " +
        "credentials=Credentials(username=user, password=<redacted>, token=<redacted>), " +
        "additionalHeaders={X-Key=<redacted>})",
      HTTP.toString(),
    )
  }

  @Test
  fun toString_httpCredentials_redactsPasswordAndToken() {
    assertEquals(
      "Credentials(username=user, password=<redacted>, token=<redacted>)",
      HTTP_CREDENTIALS.toString(),
    )
  }

  @Test
  fun toBuilder_apiKey_matchesCopy() {
    assertEquals(API_KEY.copy(), API_KEY.toBuilder().build())
  }

  @Test
  fun toBuilder_http_matchesCopy() {
    assertEquals(HTTP.copy(), HTTP.toBuilder().build())
  }

  @Test
  fun toBuilder_httpCredentials_matchesCopy() {
    assertEquals(HTTP_CREDENTIALS.copy(), HTTP_CREDENTIALS.toBuilder().build())
  }

  @Test
  fun builder_apiKeyNothingSet_matchesConstructorDefaults() {
    assertEquals(AuthCredential.ApiKey(), AuthCredential.ApiKey.builder().build())
  }

  @Test
  fun builder_httpOnlyRequiredFieldsSet_matchesConstructorDefaults() {
    assertEquals(
      AuthCredential.Http(scheme = "bearer", credentials = AuthCredential.Http.Credentials()),
      AuthCredential.Http.builder()
        .scheme("bearer")
        .credentials(AuthCredential.Http.Credentials())
        .build(),
    )
  }

  @Test
  fun builder_httpCredentialsNothingSet_matchesConstructorDefaults() {
    assertEquals(
      AuthCredential.Http.Credentials(),
      AuthCredential.Http.Credentials.builder().build(),
    )
  }

  @Test
  fun builder_apiKeySecretSetter_wrapsValueInSecret() {
    assertEquals(Secret("key"), AuthCredential.ApiKey.builder().apiKey("key").build().apiKey)
  }

  @Test
  fun builder_httpCredentialsSecretSetters_wrapValuesInSecret() {
    assertEquals(
      AuthCredential.Http.Credentials(password = Secret("password"), token = Secret("token")),
      AuthCredential.Http.Credentials.builder().password("password").token("token").build(),
    )
  }

  @Test
  fun builder_httpAdditionalHeaders_wrapsValuesInSecret() {
    assertEquals(
      mapOf("X-Key" to Secret("value")),
      AuthCredential.Http.builder()
        .scheme("basic")
        .credentials(AuthCredential.Http.Credentials())
        .additionalHeaders(mapOf("X-Key" to "value"))
        .build()
        .additionalHeaders,
    )
  }

  @Test
  fun builder_httpWithoutScheme_throws() {
    assertFailsWith<IllegalStateException> {
      AuthCredential.Http.builder().credentials(AuthCredential.Http.Credentials()).build()
    }
  }

  @Test
  fun builder_httpWithoutCredentials_throws() {
    assertFailsWith<IllegalStateException> { AuthCredential.Http.builder().scheme("basic").build() }
  }

  @Test
  fun bearer_token_buildsBearerCredential() {
    assertEquals(
      AuthCredential.Http(
        scheme = "bearer",
        credentials = AuthCredential.Http.Credentials(token = Secret("token")),
      ),
      AuthCredential.Http.bearer("token"),
    )
  }

  @Test
  fun basic_usernameAndPassword_buildsBasicCredential() {
    assertEquals(
      AuthCredential.Http(
        scheme = "basic",
        credentials =
          AuthCredential.Http.Credentials(username = "user", password = Secret("password")),
      ),
      AuthCredential.Http.basic(username = "user", password = "password"),
    )
  }

  private companion object {
    val API_KEY = AuthCredential.ApiKey(apiKey = Secret("sentinel-api-key"))

    val HTTP_CREDENTIALS =
      AuthCredential.Http.Credentials(
        username = "user",
        password = Secret("sentinel-password"),
        token = Secret("sentinel-token"),
      )

    val HTTP =
      AuthCredential.Http(
        scheme = "basic",
        credentials = HTTP_CREDENTIALS,
        additionalHeaders = mapOf("X-Key" to Secret("sentinel-header")),
      )
  }
}
