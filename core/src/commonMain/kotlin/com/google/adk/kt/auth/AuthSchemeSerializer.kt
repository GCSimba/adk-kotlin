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

@file:OptIn(ExperimentalAuthApi::class)

package com.google.adk.kt.auth

import com.google.adk.kt.annotations.ExperimentalAuthApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Reads and writes an [AuthScheme] as a JSON object whose `type` picks the kind, such as `apiKey`;
 * an `openIdConnect` scheme with an `openIdConnectUrl` is the discovery URL kind. It rejects a
 * scheme with no `type`, a `type` ADK does not define, or a missing or invalid field its `type`
 * needs.
 */
internal object AuthSchemeSerializer : KSerializer<AuthScheme> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun serialize(encoder: Encoder, value: AuthScheme) {
    val jsonEncoder =
      encoder as? JsonEncoder
        ?: throw SerializationException("An auth scheme supports JSON formats only.")
    val json = jsonEncoder.json
    val fields =
      when (value) {
        is AuthScheme.ApiKey -> json.encodeToJsonElement(ApiKey.serializer(), ApiKey(value))
        is AuthScheme.Http -> json.encodeToJsonElement(Http.serializer(), Http(value))
        is AuthScheme.OAuth2 -> json.encodeToJsonElement(OAuth2.serializer(), OAuth2(value))
        is AuthScheme.OpenIdConnectUrl ->
          json.encodeToJsonElement(OpenIdConnectUrl.serializer(), OpenIdConnectUrl(value))
        is AuthScheme.OpenIdConnect ->
          json.encodeToJsonElement(OpenIdConnect.serializer(), OpenIdConnect(value))
      }
    jsonEncoder.encodeJsonElement(
      JsonObject(mapOf("type" to JsonPrimitive(value.type)) + fields.jsonObject)
    )
  }

  override fun deserialize(decoder: Decoder): AuthScheme {
    val jsonDecoder =
      decoder as? JsonDecoder
        ?: throw SerializationException("An auth scheme supports JSON formats only.")
    val scheme =
      decodeWithoutInput { jsonDecoder.decodeJsonElement() } as? JsonObject
        ?: throw SerializationException("An auth scheme is not a JSON object.")
    val type =
      (scheme["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw SerializationException("An auth scheme has no string type.")
    val fields = JsonObject(scheme - "type")
    fun <W> decode(serializer: KSerializer<W>): W = decodeWithoutInput {
      jsonDecoder.json.decodeFromJsonElement(serializer, fields)
    }
    return when (type) {
      "apiKey" -> decode(ApiKey.serializer()).toApiKey()
      "http" -> decode(Http.serializer()).toHttp()
      "oauth2" -> decode(OAuth2.serializer()).toOAuth2()
      "openIdConnect" ->
        if ("openIdConnectUrl" in fields) {
          decode(OpenIdConnectUrl.serializer()).toOpenIdConnectUrl()
        } else {
          decode(OpenIdConnect.serializer()).toOpenIdConnect()
        }
      else -> throw SerializationException("An auth scheme has an unsupported type.")
    }
  }

  /**
   * Rethrows a decode failure without its cause, whose message can quote secrets in the input. It
   * covers only errors inside the scheme; a caller that decodes a larger document must hide errors
   * around it, such as trailing text.
   */
  private inline fun <T> decodeWithoutInput(decode: () -> T): T =
    try {
      decode()
    } catch (e: IllegalArgumentException) {
      // SerializationException extends IllegalArgumentException, so this catches both.
      throw SerializationException("Malformed auth scheme.")
    }

  @Serializable
  private class ApiKey(
    val location: AuthScheme.ApiKey.Location,
    val name: String,
    val description: String? = null,
  ) {
    constructor(apiKey: AuthScheme.ApiKey) : this(apiKey.location, apiKey.name, apiKey.description)

    fun toApiKey(): AuthScheme.ApiKey = AuthScheme.ApiKey(name, location, description)
  }

  @Serializable
  private class Http(
    val scheme: String,
    val bearerFormat: String? = null,
    val description: String? = null,
  ) {
    constructor(http: AuthScheme.Http) : this(http.scheme, http.bearerFormat, http.description)

    fun toHttp(): AuthScheme.Http = AuthScheme.Http(scheme, bearerFormat, description)
  }

  @Serializable
  private class OAuth2(
    val flows: Flows,
    val issuerUrl: String? = null,
    val description: String? = null,
  ) {
    constructor(
      oauth2: AuthScheme.OAuth2
    ) : this(Flows(oauth2.flows), oauth2.issuerUrl, oauth2.description)

    fun toOAuth2(): AuthScheme.OAuth2 = AuthScheme.OAuth2(flows.toFlows(), issuerUrl, description)

    @Serializable
    class Flows(
      val implicit: Implicit? = null,
      val password: Password? = null,
      val clientCredentials: ClientCredentials? = null,
      val authorizationCode: AuthorizationCode? = null,
    ) {
      constructor(
        flows: AuthScheme.OAuth2.Flows
      ) : this(
        implicit = flows.implicit?.let(::Implicit),
        password = flows.password?.let(::Password),
        clientCredentials = flows.clientCredentials?.let(::ClientCredentials),
        authorizationCode = flows.authorizationCode?.let(::AuthorizationCode),
      )

      fun toFlows(): AuthScheme.OAuth2.Flows =
        AuthScheme.OAuth2.Flows(
          implicit = implicit?.toImplicit(),
          password = password?.toPassword(),
          clientCredentials = clientCredentials?.toClientCredentials(),
          authorizationCode = authorizationCode?.toAuthorizationCode(),
        )
    }

    @Serializable
    class Implicit(
      val authorizationUrl: String,
      val refreshUrl: String? = null,
      val scopes: Map<String, String> = emptyMap(),
    ) {
      constructor(
        flow: AuthScheme.OAuth2.Flow.Implicit
      ) : this(flow.authorizationUrl, flow.refreshUrl, flow.scopes)

      fun toImplicit(): AuthScheme.OAuth2.Flow.Implicit =
        AuthScheme.OAuth2.Flow.Implicit(authorizationUrl, refreshUrl, scopes)
    }

    @Serializable
    class Password(
      val tokenUrl: String,
      val refreshUrl: String? = null,
      val scopes: Map<String, String> = emptyMap(),
    ) {
      constructor(
        flow: AuthScheme.OAuth2.Flow.Password
      ) : this(flow.tokenUrl, flow.refreshUrl, flow.scopes)

      fun toPassword(): AuthScheme.OAuth2.Flow.Password =
        AuthScheme.OAuth2.Flow.Password(tokenUrl, refreshUrl, scopes)
    }

    @Serializable
    class ClientCredentials(
      val tokenUrl: String,
      val refreshUrl: String? = null,
      val scopes: Map<String, String> = emptyMap(),
    ) {
      constructor(
        flow: AuthScheme.OAuth2.Flow.ClientCredentials
      ) : this(flow.tokenUrl, flow.refreshUrl, flow.scopes)

      fun toClientCredentials(): AuthScheme.OAuth2.Flow.ClientCredentials =
        AuthScheme.OAuth2.Flow.ClientCredentials(tokenUrl, refreshUrl, scopes)
    }

    @Serializable
    class AuthorizationCode(
      val authorizationUrl: String,
      val tokenUrl: String,
      val refreshUrl: String? = null,
      val scopes: Map<String, String> = emptyMap(),
    ) {
      constructor(
        flow: AuthScheme.OAuth2.Flow.AuthorizationCode
      ) : this(flow.authorizationUrl, flow.tokenUrl, flow.refreshUrl, flow.scopes)

      fun toAuthorizationCode(): AuthScheme.OAuth2.Flow.AuthorizationCode =
        AuthScheme.OAuth2.Flow.AuthorizationCode(authorizationUrl, tokenUrl, refreshUrl, scopes)
    }
  }

  @Serializable
  private class OpenIdConnectUrl(val openIdConnectUrl: String, val description: String? = null) {
    constructor(
      scheme: AuthScheme.OpenIdConnectUrl
    ) : this(scheme.openIdConnectUrl, scheme.description)

    fun toOpenIdConnectUrl(): AuthScheme.OpenIdConnectUrl =
      AuthScheme.OpenIdConnectUrl(openIdConnectUrl, description)
  }

  @Serializable
  private class OpenIdConnect(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val userinfoEndpoint: String? = null,
    val revocationEndpoint: String? = null,
    val tokenEndpointAuthMethodsSupported: List<String>? = null,
    val grantTypesSupported: List<String>? = null,
    val scopes: List<String>? = null,
    val description: String? = null,
  ) {
    constructor(
      scheme: AuthScheme.OpenIdConnect
    ) : this(
      authorizationEndpoint = scheme.authorizationEndpoint,
      tokenEndpoint = scheme.tokenEndpoint,
      userinfoEndpoint = scheme.userinfoEndpoint,
      revocationEndpoint = scheme.revocationEndpoint,
      tokenEndpointAuthMethodsSupported = scheme.tokenEndpointAuthMethodsSupported,
      grantTypesSupported = scheme.grantTypesSupported,
      scopes = scheme.scopes,
      description = scheme.description,
    )

    fun toOpenIdConnect(): AuthScheme.OpenIdConnect =
      AuthScheme.OpenIdConnect(
        authorizationEndpoint = authorizationEndpoint,
        tokenEndpoint = tokenEndpoint,
        userinfoEndpoint = userinfoEndpoint,
        revocationEndpoint = revocationEndpoint,
        tokenEndpointAuthMethodsSupported = tokenEndpointAuthMethodsSupported,
        grantTypesSupported = grantTypesSupported,
        scopes = scopes,
        description = description,
      )
  }
}
