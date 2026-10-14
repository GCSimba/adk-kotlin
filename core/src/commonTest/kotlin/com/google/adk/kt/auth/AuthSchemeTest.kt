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
  fun builder_requiredFieldsOnly_matchesConstructorDefaults() {
    assertEquals(
      AuthScheme.ApiKey(name = "key", location = AuthScheme.ApiKey.Location.QUERY),
      AuthScheme.ApiKey.builder().name("key").location(AuthScheme.ApiKey.Location.QUERY).build(),
    )
    assertEquals(
      AuthScheme.Http(scheme = "basic"),
      AuthScheme.Http.builder().scheme("basic").build(),
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

  private companion object {
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
