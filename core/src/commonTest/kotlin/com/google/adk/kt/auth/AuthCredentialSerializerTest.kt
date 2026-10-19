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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

@OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)
class AuthCredentialSerializerTest {

  @Test
  fun decode_apiKeyNotString_throws() {
    assertMalformed("""{"authType": "API_KEY", "apiKey": 123}""")
  }

  @Test
  fun decode_apiKeyNull_readsNoKey() {
    assertEquals(AuthCredential.ApiKey(), decode("""{"authType": "API_KEY", "apiKey": null}"""))
  }

  @Test
  fun decode_apiKeyWithSnakeCaseNames_readsThem() {
    assertEquals(
      AuthCredential.ApiKey(Secret("sk-123")),
      decode("""{"auth_type": "API_KEY", "api_key": "sk-123"}"""),
    )
  }

  @Test
  fun decode_httpHeaderNotString_throws() {
    assertMalformed(
      """{"authType": "HTTP", "http": {"scheme": "bearer", "credentials": {},
        "additionalHeaders": {"X-Count": 1}}}"""
    )
  }

  @Test
  fun decode_httpWithSnakeCaseNames_readsThem() {
    assertEquals(
      AuthCredential.Http.basic("user", "pass")
        .copy(additionalHeaders = mapOf("X-Key" to Secret("value"))),
      decode(
        """{"auth_type": "HTTP", "http": {"scheme": "basic",
          "credentials": {"username": "user", "password": "pass"},
          "additional_headers": {"X-Key": "value"}}}"""
      ),
    )
  }

  @Test
  fun decode_httpWithoutCredentials_throws() {
    assertMalformed("""{"authType": "HTTP", "http": {"scheme": "bearer"}}""")
  }

  @Test
  fun decode_httpWithoutPayload_throws() {
    // An Http credential needs a scheme and credentials.
    val error = assertFailsWith<SerializationException> { decode("""{"authType": "HTTP"}""") }

    assertEquals("An auth credential has no http payload.", error.message)
  }

  @Test
  fun decode_httpWithoutScheme_throws() {
    assertMalformed("""{"authType": "HTTP", "http": {"credentials": {"token": "t"}}}""")
  }

  @Test
  fun decode_malformedInput_throwsWithoutQuotingIt() {
    // The decoder's own message quotes the input, which here holds an API key.
    assertMalformed("""{"authType": "API_KEY", "apiKey": "sk-123", "http": 1}""")
  }

  @Test
  fun decode_missingAuthType_throws() {
    assertMalformed("""{"apiKey": "sk-123"}""")
  }

  @Test
  fun decode_oauth2AccessTokenNotString_throws() {
    assertMalformed("""{"authType": "OAUTH2", "oauth2": {"accessToken": 1}}""")
  }

  @Test
  fun decode_oauth2ExpiresAtNotNumber_throws() {
    assertMalformed("""{"authType": "OAUTH2", "oauth2": {"expiresAt": "soon"}}""")
  }

  @Test
  fun decode_oauth2ExpiresInNotInteger_throws() {
    assertMalformed("""{"authType": "OAUTH2", "oauth2": {"expiresIn": 1.5}}""")
  }

  @Test
  fun decode_oauth2TokenEndpointAuthMethodNull_readsNull() {
    assertEquals(
      AuthCredential.OAuth2(tokenEndpointAuthMethod = null),
      decode("""{"authType": "OAUTH2", "oauth2": {"tokenEndpointAuthMethod": null}}"""),
    )
  }

  @Test
  fun decode_oauth2UnknownTokenEndpointAuthMethod_throws() {
    assertMalformed("""{"authType": "OAUTH2", "oauth2": {"tokenEndpointAuthMethod": "none"}}""")
  }

  @Test
  fun decode_oauth2WithSnakeCaseNames_readsEveryField() {
    assertEquals(
      OAUTH2,
      decode(
        """{"auth_type": "OAUTH2", "oauth2": {"client_id": "client", "client_secret": "secret",
          "auth_uri": "https://example.com/auth", "nonce": "n", "state": "s",
          "redirect_uri": "https://example.com/cb",
          "auth_response_uri": "https://example.com/cb?code=c", "auth_code": "c",
          "access_token": "at", "refresh_token": "rt", "id_token": "it",
          "expires_at": 1700000000, "expires_in": 3600, "audience": "aud",
          "prompt": "consent", "code_verifier": "cv", "code_challenge_method": "S256",
          "token_endpoint_auth_method": "CLIENT_SECRET_POST"}}"""
      ),
    )
  }

  @Test
  fun decode_oauth2WithoutPayload_throws() {
    for (input in listOf("""{"authType": "OAUTH2"}""", """{"authType": "OPEN_ID_CONNECT"}""")) {
      val error = assertFailsWith<SerializationException> { decode(input) }

      assertEquals("An auth credential has no oauth2 payload.", error.message)
    }
  }

  @Test
  fun decode_oauth2WithoutTokenEndpointAuthMethod_readsNull() {
    assertEquals(
      AuthCredential.OAuth2(tokenEndpointAuthMethod = null),
      decode("""{"authType": "OAUTH2", "oauth2": {}}"""),
    )
  }

  @Test
  fun decode_oauth2_readsEveryField() {
    assertEquals(OAUTH2, decode(OAUTH2_JSON))
  }

  @Test
  fun decode_openIdConnect_readsItsVariant() {
    assertEquals(
      AuthCredential.OAuth2(
        variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT,
        clientId = "c",
        tokenEndpointAuthMethod = null,
      ),
      decode("""{"authType": "OPEN_ID_CONNECT", "oauth2": {"clientId": "c"}}"""),
    )
  }

  @Test
  fun decode_serviceAccountIdTokenWithoutAudience_throwsWithItsCheck() {
    val error =
      assertFailsWith<SerializationException> {
        decode(
          """{"authType": "SERVICE_ACCOUNT",
            "serviceAccount": {"useDefaultCredential": true, "useIdToken": true}}"""
        )
      }

    assertEquals("ServiceAccount requires audience when useIdToken is true.", error.message)
  }

  @Test
  fun decode_serviceAccountIncompleteKey_throws() {
    assertMalformed(
      """{"authType": "SERVICE_ACCOUNT", "serviceAccount": {"serviceAccountCredential":
        {"projectId": "p"}}}"""
    )
  }

  @Test
  fun decode_serviceAccountKeyWithoutType_readsAnEmptyType() {
    val keyJson = KEY_JSON.replace("\"type\": \"service_account\", ", "")

    assertEquals(
      AuthCredential.ServiceAccount.fromKey(KEY.copy(type = ""), listOf("s")),
      decode(
        """{"authType": "SERVICE_ACCOUNT", "serviceAccount": {"serviceAccountCredential": $keyJson,
          "scopes": ["s"]}}"""
      ),
    )
  }

  @Test
  fun decode_serviceAccountNullFlags_throws() {
    assertMalformed(
      """{"authType": "SERVICE_ACCOUNT", "serviceAccount": {"serviceAccountCredential": $KEY_JSON,
        "scopes": ["s"], "useDefaultCredential": null, "useIdToken": null}}"""
    )
  }

  @Test
  fun decode_serviceAccountScopesNotList_throws() {
    assertMalformed(
      """{"authType": "SERVICE_ACCOUNT", "serviceAccount": {"useDefaultCredential": true,
        "scopes": "s"}}"""
    )
  }

  @Test
  fun decode_serviceAccountSnakeCaseNames_readsThem() {
    assertEquals(
      SERVICE_ACCOUNT,
      decode(
        """{"auth_type": "SERVICE_ACCOUNT", "service_account": {"service_account_credential": {
          "type": "service_account", "project_id": "p", "private_key_id": "kid",
          "private_key": "pem", "client_email": "sa@p.iam.gserviceaccount.com",
          "client_id": "123", "auth_uri": "https://accounts.google.com/o/oauth2/auth",
          "token_uri": "https://oauth2.googleapis.com/token",
          "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
          "client_x509_cert_url": "https://www.googleapis.com/robot/v1/metadata/x509/sa",
          "universe_domain": "googleapis.com"}, "scopes": ["s"],
          "use_default_credential": true, "use_id_token": true,
          "audience": "https://example.com"}}"""
      ),
    )
  }

  @Test
  fun decode_serviceAccountWithoutKey_throwsWithItsCheck() {
    val error =
      assertFailsWith<SerializationException> {
        decode("""{"authType": "SERVICE_ACCOUNT", "serviceAccount": {}}""")
      }

    assertEquals(
      "ServiceAccount requires serviceAccountCredential when useDefaultCredential is false.",
      error.message,
    )
  }

  @Test
  fun decode_serviceAccountWithoutPayload_throws() {
    // A service account needs a key or Application Default Credentials.
    val error =
      assertFailsWith<SerializationException> { decode("""{"authType": "SERVICE_ACCOUNT"}""") }

    assertEquals("An auth credential has no serviceAccount payload.", error.message)
  }

  @Test
  fun decode_serviceAccount_readsEveryField() {
    assertEquals(SERVICE_ACCOUNT, decode(SERVICE_ACCOUNT_JSON))
  }

  @Test
  fun decode_unknownAuthType_throws() {
    assertMalformed("""{"authType": "bogus"}""")
  }

  @Test
  fun decode_unmodeledFields_dropsThem() {
    assertEquals(
      AuthCredential.Http.bearer("t"),
      decode(
        """{"authType": "HTTP", "apiKey": "k", "resourceRef": "r", "z": 3,
          "http": {"scheme": "bearer", "credentials": {"token": "t", "x": 1}, "y": 2}}"""
      ),
    )
  }

  @Test
  fun encode_apiKeyWithoutKey_writesAuthTypeOnly() {
    assertEquals(json("""{"authType": "API_KEY"}"""), encode(AuthCredential.ApiKey()))
  }

  @Test
  fun encode_apiKey_writesAuthTypeAndKey() {
    assertEquals(
      json("""{"authType": "API_KEY", "apiKey": "sk-123"}"""),
      encode(AuthCredential.ApiKey(Secret("sk-123"))),
    )
  }

  @Test
  fun encode_httpWithHeaders_writesHeaders() {
    val credential =
      AuthCredential.Http.basic("user", "pass")
        .copy(additionalHeaders = mapOf("X-Key" to Secret("value")))

    assertEquals(
      json(
        """{"authType": "HTTP", "http": {"scheme": "basic",
          "credentials": {"username": "user", "password": "pass"},
          "additionalHeaders": {"X-Key": "value"}}}"""
      ),
      encode(credential),
    )
  }

  @Test
  fun encode_http_writesPayloadWithoutNulls() {
    assertEquals(
      json("""{"authType": "HTTP", "http": {"scheme": "bearer", "credentials": {"token": "t"}}}"""),
      encode(AuthCredential.Http.bearer("t")),
    )
  }

  @Test
  fun encode_oauth2Defaults_writesTokenEndpointAuthMethod() {
    assertEquals(
      json(
        """{"authType": "OAUTH2", "oauth2": {"tokenEndpointAuthMethod": "CLIENT_SECRET_BASIC"}}"""
      ),
      encode(AuthCredential.OAuth2()),
    )
  }

  @Test
  fun encode_oauth2SubSecondTimes_dropsTheFraction() {
    val credential =
      AuthCredential.OAuth2(
        expiresAt = Instant.fromEpochMilliseconds(1_700_000_000_900),
        expiresIn = 3599.seconds + 900.milliseconds,
        tokenEndpointAuthMethod = null,
      )

    assertEquals(
      json("""{"authType": "OAUTH2", "oauth2": {"expiresAt": 1700000000, "expiresIn": 3599}}"""),
      encode(credential),
    )
  }

  @Test
  fun encode_oauth2WithoutTokenEndpointAuthMethod_omitsIt() {
    assertEquals(
      json("""{"authType": "OAUTH2", "oauth2": {}}"""),
      encode(AuthCredential.OAuth2(tokenEndpointAuthMethod = null)),
    )
  }

  @Test
  fun encode_oauth2_writesEveryField() {
    assertEquals(json(OAUTH2_JSON), encode(OAUTH2))
  }

  @Test
  fun encode_openIdConnect_writesItsAuthType() {
    val credential =
      AuthCredential.OAuth2(variant = AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT, clientId = "c")

    assertEquals(
      json(
        """{"authType": "OPEN_ID_CONNECT",
          "oauth2": {"clientId": "c", "tokenEndpointAuthMethod": "CLIENT_SECRET_BASIC"}}"""
      ),
      encode(credential),
    )
  }

  @Test
  fun encode_serviceAccountDefaults_omitsThem() {
    val keyJson = KEY_JSON.replace("\"type\": \"service_account\", ", "")
    val credential = AuthCredential.ServiceAccount.fromKey(KEY.copy(type = ""), listOf("s"))
    val written = encode(credential)

    assertEquals(
      json(
        """{"authType": "SERVICE_ACCOUNT", "serviceAccount": {"serviceAccountCredential": $keyJson,
          "scopes": ["s"]}}"""
      ),
      written,
    )
    assertEquals(credential, decode(written.toString()))
  }

  @Test
  fun encode_serviceAccount_writesEveryField() {
    assertEquals(json(SERVICE_ACCOUNT_JSON), encode(SERVICE_ACCOUNT))
  }

  @Test
  fun serviceAccount_applicationDefault_roundTripsWithoutKey() {
    val credential = AuthCredential.ServiceAccount.applicationDefault()
    val written = encode(credential)

    assertEquals(
      json("""{"authType": "SERVICE_ACCOUNT", "serviceAccount": {"useDefaultCredential": true}}"""),
      written,
    )
    assertEquals(credential, decode(written.toString()))
  }

  @Test
  fun serviceAccount_idTokenWithKey_roundTripsEachFlag() {
    val credential = AuthCredential.ServiceAccount.forIdToken("https://example.com", KEY)
    val written = encode(credential)

    assertEquals(
      json(
        """{"authType": "SERVICE_ACCOUNT", "serviceAccount": {"serviceAccountCredential": $KEY_JSON,
          "useIdToken": true, "audience": "https://example.com"}}"""
      ),
      written,
    )
    assertEquals(credential, decode(written.toString()))
  }

  @Test
  fun tokenEndpointAuthMethod_everyValue_roundTripsUnderItsName() {
    for (method in AuthCredential.OAuth2.TokenEndpointAuthMethod.entries) {
      val credential = AuthCredential.OAuth2(tokenEndpointAuthMethod = method)
      val written = encode(credential)

      assertEquals(
        json("""{"authType": "OAUTH2", "oauth2": {"tokenEndpointAuthMethod": "${method.name}"}}"""),
        written,
      )
      assertEquals(credential, decode(written.toString()))
    }
  }

  private companion object {
    val KEY =
      AuthCredential.ServiceAccount.Credential(
        type = "service_account",
        projectId = "p",
        privateKeyId = Secret("kid"),
        privateKey = Secret("pem"),
        clientEmail = "sa@p.iam.gserviceaccount.com",
        clientId = "123",
        authUri = "https://accounts.google.com/o/oauth2/auth",
        tokenUri = "https://oauth2.googleapis.com/token",
        authProviderX509CertUrl = "https://www.googleapis.com/oauth2/v1/certs",
        clientX509CertUrl = "https://www.googleapis.com/robot/v1/metadata/x509/sa",
        universeDomain = "googleapis.com",
      )

    const val KEY_JSON =
      """{"type": "service_account", "projectId": "p", "privateKeyId": "kid",
        "privateKey": "pem", "clientEmail": "sa@p.iam.gserviceaccount.com", "clientId": "123",
        "authUri": "https://accounts.google.com/o/oauth2/auth",
        "tokenUri": "https://oauth2.googleapis.com/token",
        "authProviderX509CertUrl": "https://www.googleapis.com/oauth2/v1/certs",
        "clientX509CertUrl": "https://www.googleapis.com/robot/v1/metadata/x509/sa",
        "universeDomain": "googleapis.com"}"""

    val SERVICE_ACCOUNT =
      AuthCredential.ServiceAccount(
        serviceAccountCredential = KEY,
        scopes = listOf("s"),
        useDefaultCredential = true,
        useIdToken = true,
        audience = "https://example.com",
      )

    val SERVICE_ACCOUNT_JSON =
      """{"authType": "SERVICE_ACCOUNT", "serviceAccount": {"serviceAccountCredential": $KEY_JSON,
        "scopes": ["s"], "useDefaultCredential": true, "useIdToken": true,
        "audience": "https://example.com"}}"""

    val OAUTH2 =
      AuthCredential.OAuth2(
        clientId = "client",
        clientSecret = Secret("secret"),
        authUri = "https://example.com/auth",
        nonce = "n",
        state = "s",
        redirectUri = "https://example.com/cb",
        authResponseUri = Secret("https://example.com/cb?code=c"),
        authCode = Secret("c"),
        accessToken = Secret("at"),
        refreshToken = Secret("rt"),
        idToken = Secret("it"),
        expiresAt = Instant.fromEpochSeconds(1_700_000_000),
        expiresIn = 3600.seconds,
        audience = "aud",
        prompt = "consent",
        codeVerifier = Secret("cv"),
        codeChallengeMethod = "S256",
        tokenEndpointAuthMethod = AuthCredential.OAuth2.TokenEndpointAuthMethod.CLIENT_SECRET_POST,
      )

    const val OAUTH2_JSON =
      """{"authType": "OAUTH2", "oauth2": {"clientId": "client", "clientSecret": "secret",
        "authUri": "https://example.com/auth", "nonce": "n", "state": "s",
        "redirectUri": "https://example.com/cb", "authResponseUri": "https://example.com/cb?code=c",
        "authCode": "c", "accessToken": "at", "refreshToken": "rt", "idToken": "it",
        "expiresAt": 1700000000, "expiresIn": 3600, "audience": "aud", "prompt": "consent",
        "codeVerifier": "cv", "codeChallengeMethod": "S256",
        "tokenEndpointAuthMethod": "CLIENT_SECRET_POST"}}"""

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    fun encode(credential: AuthCredential): JsonElement =
      adkJson.encodeToJsonElement(AuthCredentialSerializer, credential)

    fun decode(text: String): AuthCredential =
      adkJson.decodeFromString(AuthCredentialSerializer, text)

    fun assertMalformed(input: String) {
      val error = assertFailsWith<SerializationException> { decode(input) }

      assertEquals("Malformed auth credential.", error.message)
      assertNull(error.cause)
    }
  }
}
