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

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
class AuthConfigSerializerTest {

  @Test
  fun decode_authConfig_readsEveryField() {
    assertEquals(CONFIG, decode(CONFIG_JSON))
  }

  @Test
  fun decode_invalidSchemeOrCredential_throws() {
    val inputs =
      listOf(
        """{"credentialKey": "k"}""",
        """{"authScheme": {"scheme": "bearer"}, "credentialKey": "k"}""",
        """{"authScheme": {"type": "http", "scheme": "bearer"},
          "exchangedAuthCredential": {"authType": "HTTP"}, "credentialKey": "k"}""",
      )

    inputs.forEachIndexed { i, input ->
      val error = assertFailsWith<SerializationException>("input #$i") { decode(input) }

      assertEquals("Malformed auth config.", error.message, "input #$i")
      assertNull(error.cause, "input #$i")
    }
  }

  @Test
  fun decode_malformedInput_throwsWithoutQuotingIt() {
    // The decoder's own message quotes the input, which here holds an API key.
    val error =
      assertFailsWith<SerializationException> {
        decode(
          """{"authScheme": {"type": "http", "scheme": "bearer"},
            "rawAuthCredential": {"authType": "API_KEY", "apiKey": "sk-123"}, "credentialKey": 1}"""
        )
      }

    assertEquals("Malformed auth config.", error.message)
    assertNull(error.cause)
  }

  @Test
  fun decode_snakeCaseNames_readsThem() {
    assertEquals(
      CONFIG,
      decode(
        """{"auth_scheme": $SCHEME_JSON, "raw_auth_credential": $RAW_JSON,
          "exchanged_auth_credential": $EXCHANGED_JSON, "credential_key": "$KEY"}"""
      ),
    )
  }

  @Test
  fun decode_withoutCredentialKey_throws() {
    val inputs =
      listOf(
        """{"authScheme": {"type": "http", "scheme": "bearer"}}""",
        """{"authScheme": {"type": "http", "scheme": "bearer"}, "credentialKey": null}""",
        """{"authScheme": {"type": "http", "scheme": "bearer"}, "credentialKey": ""}""",
      )

    inputs.forEachIndexed { i, input ->
      val error = assertFailsWith<SerializationException>("input #$i") { decode(input) }

      assertEquals("An auth config has no credential key.", error.message, "input #$i")
    }
  }

  @Test
  fun decode_withoutCredentials_readsSchemeAndKeyOnly() {
    val inputs =
      listOf(
        """{"authScheme": {"type": "http", "scheme": "bearer"}, "credentialKey": "k"}""",
        """{"authScheme": {"type": "http", "scheme": "bearer"}, "rawAuthCredential": null,
          "exchangedAuthCredential": null, "credentialKey": "k"}""",
      )

    inputs.forEachIndexed { i, input ->
      assertEquals(
        AuthConfig(AuthScheme.Http.bearer(), credentialKey = "k"),
        decode(input),
        "input #$i",
      )
    }
  }

  @Test
  fun encode_authConfig_writesEveryField() {
    assertEquals(json(CONFIG_JSON), encode(CONFIG))
  }

  @Test
  fun encode_withoutCredentials_writesSchemeAndKeyOnly() {
    assertEquals(
      json("""{"authScheme": {"type": "http", "scheme": "bearer"}, "credentialKey": "k"}"""),
      encode(AuthConfig(AuthScheme.Http.bearer(), credentialKey = "k")),
    )
  }

  private companion object {
    const val KEY = "adk_openIdConnect_48b0685081cd6506_openIdConnect_d62d4f4b945e72e7"

    // An OpenID Connect config after ADK set the authorization URL.
    val CONFIG =
      AuthConfig(
        authScheme =
          AuthScheme.OpenIdConnect(
            authorizationEndpoint = "https://a/auth",
            tokenEndpoint = "https://a/token",
            scopes = listOf("openid"),
          ),
        rawAuthCredential =
          AuthCredential.OAuth2(
            variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
            clientId = "cid",
            clientSecret = Secret("sec"),
            redirectUri = "https://app/cb",
          ),
        exchangedAuthCredential =
          AuthCredential.OAuth2(
            variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
            clientId = "cid",
            authUri = "https://a/auth?state=s",
            state = "s",
            redirectUri = "https://app/cb",
          ),
        credentialKey = KEY,
      )

    const val SCHEME_JSON =
      """{"type": "openIdConnect", "authorizationEndpoint": "https://a/auth",
        "tokenEndpoint": "https://a/token", "scopes": ["openid"]}"""

    const val RAW_JSON =
      """{"authType": "OPEN_ID_CONNECT", "oauth2": {"clientId": "cid", "clientSecret": "sec",
        "redirectUri": "https://app/cb", "tokenEndpointAuthMethod": "CLIENT_SECRET_BASIC"}}"""

    const val EXCHANGED_JSON =
      """{"authType": "OPEN_ID_CONNECT", "oauth2": {"clientId": "cid",
        "authUri": "https://a/auth?state=s", "state": "s", "redirectUri": "https://app/cb",
        "tokenEndpointAuthMethod": "CLIENT_SECRET_BASIC"}}"""

    val CONFIG_JSON =
      """{"authScheme": $SCHEME_JSON, "rawAuthCredential": $RAW_JSON,
        "exchangedAuthCredential": $EXCHANGED_JSON, "credentialKey": "$KEY"}"""

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    fun encode(config: AuthConfig): JsonElement =
      adkJson.encodeToJsonElement(AuthConfigSerializer, config)

    fun decode(text: String): AuthConfig = adkJson.decodeFromString(AuthConfigSerializer, text)
  }
}
