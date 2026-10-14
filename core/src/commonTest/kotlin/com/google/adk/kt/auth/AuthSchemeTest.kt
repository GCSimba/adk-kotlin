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
class AuthSchemeTest {

  @Test
  fun type_eachScheme_isOpenApiType() {
    assertEquals("apiKey", API_KEY.type)
    assertEquals("http", HTTP.type)
    assertEquals("oauth2", OAUTH2.type)
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
  fun toBuilder_oauth2_matchesCopy() {
    assertEquals(OAUTH2.copy(), OAUTH2.toBuilder().build())
  }

  @Test
  fun toBuilder_flows_matchesCopy() {
    assertEquals(OAUTH2.flows.copy(), OAUTH2.flows.toBuilder().build())
  }

  @Test
  fun toBuilder_eachFlow_matchesCopy() {
    assertEquals(IMPLICIT.copy(), IMPLICIT.toBuilder().build())
    assertEquals(PASSWORD.copy(), PASSWORD.toBuilder().build())
    assertEquals(CLIENT_CREDENTIALS.copy(), CLIENT_CREDENTIALS.toBuilder().build())
    assertEquals(AUTHORIZATION_CODE.copy(), AUTHORIZATION_CODE.toBuilder().build())
  }

  @Test
  fun builder_requiredFieldsOnly_matchesConstructorDefaults() {
    assertEquals(
      AuthScheme.ApiKey(name = "key", location = AuthScheme.ApiKey.Location.QUERY),
      AuthScheme.ApiKey.builder().name("key").location(AuthScheme.ApiKey.Location.QUERY).build(),
    )
    assertEquals(
      AuthScheme.Http(scheme = "basic"),
      AuthScheme.Http.builder().scheme("basic").build(),
    )
    assertEquals(
      AuthScheme.OAuth2(flows = AuthScheme.OAuth2.Flows()),
      AuthScheme.OAuth2.builder().flows(AuthScheme.OAuth2.Flows()).build(),
    )
    assertEquals(AuthScheme.OAuth2.Flows(), AuthScheme.OAuth2.Flows.builder().build())
  }

  @Test
  fun builder_eachFlowRequiredFieldsOnly_matchesConstructorDefaults() {
    assertEquals(
      AuthScheme.OAuth2.Flow.Implicit(authorizationUrl = AUTHORIZATION_URL),
      AuthScheme.OAuth2.Flow.Implicit.builder().authorizationUrl(AUTHORIZATION_URL).build(),
    )
    assertEquals(
      AuthScheme.OAuth2.Flow.Password(tokenUrl = TOKEN_URL),
      AuthScheme.OAuth2.Flow.Password.builder().tokenUrl(TOKEN_URL).build(),
    )
    assertEquals(
      AuthScheme.OAuth2.Flow.ClientCredentials(tokenUrl = TOKEN_URL),
      AuthScheme.OAuth2.Flow.ClientCredentials.builder().tokenUrl(TOKEN_URL).build(),
    )
    assertEquals(
      AuthScheme.OAuth2.Flow.AuthorizationCode(
        authorizationUrl = AUTHORIZATION_URL,
        tokenUrl = TOKEN_URL,
      ),
      AuthScheme.OAuth2.Flow.AuthorizationCode.builder()
        .authorizationUrl(AUTHORIZATION_URL)
        .tokenUrl(TOKEN_URL)
        .build(),
    )
  }

  @Test
  fun builder_apiKeyWithoutName_throws() {
    assertFailsWith<IllegalStateException> {
      AuthScheme.ApiKey.builder().location(AuthScheme.ApiKey.Location.HEADER).build()
    }
  }

  @Test
  fun builder_apiKeyWithoutLocation_throws() {
    assertFailsWith<IllegalStateException> { AuthScheme.ApiKey.builder().name("key").build() }
  }

  @Test
  fun builder_httpWithoutScheme_throws() {
    assertFailsWith<IllegalStateException> { AuthScheme.Http.builder().build() }
  }

  @Test
  fun bearer_format_buildsBearerScheme() {
    assertEquals(AuthScheme.Http(scheme = "bearer"), AuthScheme.Http.bearer())
    assertEquals(
      AuthScheme.Http(scheme = "bearer", bearerFormat = "JWT"),
      AuthScheme.Http.bearer(bearerFormat = "JWT"),
    )
  }

  @Test
  fun basic_always_buildsBasicScheme() {
    assertEquals(AuthScheme.Http(scheme = "basic"), AuthScheme.Http.basic())
  }

  @Test
  fun builder_oauth2WithoutFlows_throws() {
    assertFailsWith<IllegalStateException> { AuthScheme.OAuth2.builder().build() }
  }

  @Test
  fun builder_implicitWithoutAuthorizationUrl_throws() {
    assertFailsWith<IllegalStateException> { AuthScheme.OAuth2.Flow.Implicit.builder().build() }
  }

  @Test
  fun builder_passwordWithoutTokenUrl_throws() {
    assertFailsWith<IllegalStateException> { AuthScheme.OAuth2.Flow.Password.builder().build() }
  }

  @Test
  fun builder_clientCredentialsWithoutTokenUrl_throws() {
    assertFailsWith<IllegalStateException> {
      AuthScheme.OAuth2.Flow.ClientCredentials.builder().build()
    }
  }

  @Test
  fun builder_authorizationCodeWithoutAuthorizationUrl_throws() {
    assertFailsWith<IllegalStateException> {
      AuthScheme.OAuth2.Flow.AuthorizationCode.builder().tokenUrl(TOKEN_URL).build()
    }
  }

  @Test
  fun builder_authorizationCodeWithoutTokenUrl_throws() {
    assertFailsWith<IllegalStateException> {
      AuthScheme.OAuth2.Flow.AuthorizationCode.builder().authorizationUrl(AUTHORIZATION_URL).build()
    }
  }

  @Test
  fun authorizationCode_urlsAndScopes_setsOnlyThatFlow() {
    assertEquals(
      AuthScheme.OAuth2(
        flows =
          AuthScheme.OAuth2.Flows(
            authorizationCode =
              AuthScheme.OAuth2.Flow.AuthorizationCode(
                authorizationUrl = AUTHORIZATION_URL,
                tokenUrl = TOKEN_URL,
                scopes = SCOPES,
              )
          )
      ),
      AuthScheme.OAuth2.authorizationCode(
        authorizationUrl = AUTHORIZATION_URL,
        tokenUrl = TOKEN_URL,
        scopes = SCOPES,
      ),
    )
  }

  @Test
  fun clientCredentials_urlAndScopes_setsOnlyThatFlow() {
    assertEquals(
      AuthScheme.OAuth2(
        flows =
          AuthScheme.OAuth2.Flows(
            clientCredentials =
              AuthScheme.OAuth2.Flow.ClientCredentials(tokenUrl = TOKEN_URL, scopes = SCOPES)
          )
      ),
      AuthScheme.OAuth2.clientCredentials(tokenUrl = TOKEN_URL, scopes = SCOPES),
    )
  }

  private companion object {
    const val AUTHORIZATION_URL = "https://auth.example.com/authorize"
    const val TOKEN_URL = "https://auth.example.com/token"
    const val REFRESH_URL = "https://auth.example.com/refresh"
    val SCOPES = mapOf("openid" to "Sign in", "email" to "Read your email address")

    val IMPLICIT =
      AuthScheme.OAuth2.Flow.Implicit(
        authorizationUrl = AUTHORIZATION_URL,
        refreshUrl = REFRESH_URL,
        scopes = SCOPES,
      )

    val PASSWORD =
      AuthScheme.OAuth2.Flow.Password(
        tokenUrl = TOKEN_URL,
        refreshUrl = REFRESH_URL,
        scopes = SCOPES,
      )

    val CLIENT_CREDENTIALS =
      AuthScheme.OAuth2.Flow.ClientCredentials(
        tokenUrl = TOKEN_URL,
        refreshUrl = REFRESH_URL,
        scopes = SCOPES,
      )

    val AUTHORIZATION_CODE =
      AuthScheme.OAuth2.Flow.AuthorizationCode(
        authorizationUrl = AUTHORIZATION_URL,
        tokenUrl = TOKEN_URL,
        refreshUrl = REFRESH_URL,
        scopes = SCOPES,
      )

    val OAUTH2 =
      AuthScheme.OAuth2(
        flows =
          AuthScheme.OAuth2.Flows(
            implicit = IMPLICIT,
            password = PASSWORD,
            clientCredentials = CLIENT_CREDENTIALS,
            authorizationCode = AUTHORIZATION_CODE,
          ),
        issuerUrl = "https://auth.example.com",
        description = "OAuth2 sign-in",
      )

    val API_KEY =
      AuthScheme.ApiKey(
        name = "X-Api-Key",
        location = AuthScheme.ApiKey.Location.COOKIE,
        description = "API key in a cookie",
      )

    val HTTP =
      AuthScheme.Http(scheme = "bearer", bearerFormat = "JWT", description = "Bearer token")
  }
}
