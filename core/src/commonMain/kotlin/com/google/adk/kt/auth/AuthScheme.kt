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
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * How a service expects a tool to authenticate, such as with an API key or an HTTP credential. Each
 * scheme matches an OpenAPI security scheme.
 */
@ExperimentalAuthApi
sealed interface AuthScheme {
  /** The scheme's OpenAPI `type`, such as `apiKey` or `http`. */
  val type: String

  /**
   * An API key scheme: where the key is sent, and under which name.
   *
   * @property name The name of the header, query parameter or cookie that carries the key.
   * @property location Where the key is sent: OpenAPI's `in`.
   * @property description A description of the scheme.
   */
  @ExperimentalAuthApi
  data class ApiKey(val name: String, val location: Location, val description: String? = null) :
    AuthScheme {
    override val type: String
      get() = "apiKey"

    /** Where an API key is sent. */
    @ExperimentalAuthApi
    enum class Location {
      QUERY,
      HEADER,
      COOKIE,
    }

    /**
     * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
     * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
     * changes whenever a property is added.
     */
    @AdkJavaInteropApi
    fun toBuilder(): Builder = Builder().name(name).location(location).description(description)

    /**
     * Fluent builder for [ApiKey], provided primarily for Java callers. Any property left unset
     * falls back to the same default as the constructor.
     */
    @AdkJavaInteropApi
    @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
    class Builder {
      private var name: String? = null
      private var location: Location? = null
      private var description: String? = null

      fun name(name: String): Builder = apply { this.name = name }

      fun location(location: Location): Builder = apply { this.location = location }

      fun description(description: String?): Builder = apply { this.description = description }

      fun build(): ApiKey =
        ApiKey(
          name = checkNotNull(name) { "AuthScheme.ApiKey.Builder requires name to be set." },
          location =
            checkNotNull(location) { "AuthScheme.ApiKey.Builder requires location to be set." },
          description = description,
        )
    }

    companion object {
      @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
    }
  }

  /**
   * An HTTP authentication scheme, such as `basic` or `bearer`.
   *
   * @property scheme The `Authorization` header scheme (RFC 7235).
   * @property bearerFormat A hint about the bearer token's format, such as `JWT`.
   * @property description A description of the scheme.
   */
  @ExperimentalAuthApi
  data class Http(
    val scheme: String,
    val bearerFormat: String? = null,
    val description: String? = null,
  ) : AuthScheme {
    override val type: String
      get() = "http"

    /**
     * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
     * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
     * changes whenever a property is added.
     */
    @AdkJavaInteropApi
    fun toBuilder(): Builder =
      Builder().scheme(scheme).bearerFormat(bearerFormat).description(description)

    /**
     * Fluent builder for [Http], provided primarily for Java callers. Any property left unset falls
     * back to the same default as the constructor.
     */
    @AdkJavaInteropApi
    @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
    class Builder {
      private var scheme: String? = null
      private var bearerFormat: String? = null
      private var description: String? = null

      fun scheme(scheme: String): Builder = apply { this.scheme = scheme }

      fun bearerFormat(bearerFormat: String?): Builder = apply { this.bearerFormat = bearerFormat }

      fun description(description: String?): Builder = apply { this.description = description }

      fun build(): Http =
        Http(
          scheme = checkNotNull(scheme) { "AuthScheme.Http.Builder requires scheme to be set." },
          bearerFormat = bearerFormat,
          description = description,
        )
    }

    companion object {
      // Factories take only a scheme's defining fields, so adding a field never changes them.

      /** Returns a `bearer` scheme; [bearerFormat] hints at the token's format, such as `JWT`. */
      @JvmStatic
      @JvmOverloads
      fun bearer(bearerFormat: String? = null): Http =
        Http(scheme = "bearer", bearerFormat = bearerFormat)

      /** Returns a `basic` scheme. */
      @JvmStatic fun basic(): Http = Http(scheme = "basic")

      @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
    }
  }

  /**
   * An OAuth2 scheme: the flows the service supports, with their endpoints and scopes.
   *
   * @property flows The OAuth2 flows the service supports.
   * @property issuerUrl The authorization server's issuer URL, from which its endpoints can be
   *   discovered.
   * @property description A description of the scheme.
   */
  @ExperimentalAuthApi
  data class OAuth2(
    val flows: Flows,
    val issuerUrl: String? = null,
    val description: String? = null,
  ) : AuthScheme {
    override val type: String
      get() = "oauth2"

    /**
     * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
     * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
     * changes whenever a property is added.
     */
    @AdkJavaInteropApi
    fun toBuilder(): Builder = Builder().flows(flows).issuerUrl(issuerUrl).description(description)

    /**
     * The OAuth2 flows a service supports. Each property is set when the service supports that
     * flow.
     *
     * @property implicit The implicit flow.
     * @property password The resource owner password flow.
     * @property clientCredentials The client credentials flow.
     * @property authorizationCode The authorization code flow.
     */
    @ExperimentalAuthApi
    data class Flows(
      val implicit: Flow.Implicit? = null,
      val password: Flow.Password? = null,
      val clientCredentials: Flow.ClientCredentials? = null,
      val authorizationCode: Flow.AuthorizationCode? = null,
    ) {
      /**
       * Returns a [Builder] initialized with this instance's properties, primarily for Java
       * callers. Prefer it over `copy` from Java: `copy` takes every property positionally, so its
       * signature changes whenever a property is added.
       */
      @AdkJavaInteropApi
      fun toBuilder(): Builder =
        Builder()
          .implicit(implicit)
          .password(password)
          .clientCredentials(clientCredentials)
          .authorizationCode(authorizationCode)

      /**
       * Fluent builder for [Flows], provided primarily for Java callers. Any property left unset
       * falls back to the same default as the constructor.
       */
      @AdkJavaInteropApi
      @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
      class Builder {
        private var implicit: Flow.Implicit? = null
        private var password: Flow.Password? = null
        private var clientCredentials: Flow.ClientCredentials? = null
        private var authorizationCode: Flow.AuthorizationCode? = null

        fun implicit(implicit: Flow.Implicit?): Builder = apply { this.implicit = implicit }

        fun password(password: Flow.Password?): Builder = apply { this.password = password }

        fun clientCredentials(clientCredentials: Flow.ClientCredentials?): Builder = apply {
          this.clientCredentials = clientCredentials
        }

        fun authorizationCode(authorizationCode: Flow.AuthorizationCode?): Builder = apply {
          this.authorizationCode = authorizationCode
        }

        fun build(): Flows =
          Flows(
            implicit = implicit,
            password = password,
            clientCredentials = clientCredentials,
            authorizationCode = authorizationCode,
          )
      }

      companion object {
        @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
      }
    }

    /** An OAuth2 flow: its endpoints and the scopes to request. */
    @ExperimentalAuthApi
    sealed interface Flow {
      /** The endpoint for refreshing tokens. */
      val refreshUrl: String?

      /** The scopes to request, each mapped to its description. */
      val scopes: Map<String, String>

      /**
       * The implicit flow.
       *
       * @property authorizationUrl The authorization endpoint.
       * @property refreshUrl The endpoint for refreshing tokens.
       * @property scopes The scopes to request, each mapped to its description.
       */
      @ExperimentalAuthApi
      data class Implicit(
        val authorizationUrl: String,
        override val refreshUrl: String? = null,
        override val scopes: Map<String, String> = emptyMap(),
      ) : Flow {
        /**
         * Returns a [Builder] initialized with this instance's properties, primarily for Java
         * callers. Prefer it over `copy` from Java: `copy` takes every property positionally, so
         * its signature changes whenever a property is added.
         */
        @AdkJavaInteropApi
        fun toBuilder(): Builder =
          Builder().authorizationUrl(authorizationUrl).refreshUrl(refreshUrl).scopes(scopes)

        /**
         * Fluent builder for [Implicit], provided primarily for Java callers. Any property left
         * unset falls back to the same default as the constructor.
         */
        @AdkJavaInteropApi
        @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
        class Builder {
          private var authorizationUrl: String? = null
          private var refreshUrl: String? = null
          private var scopes: Map<String, String> = emptyMap()

          fun authorizationUrl(authorizationUrl: String): Builder = apply {
            this.authorizationUrl = authorizationUrl
          }

          fun refreshUrl(refreshUrl: String?): Builder = apply { this.refreshUrl = refreshUrl }

          fun scopes(scopes: Map<String, String>): Builder = apply { this.scopes = scopes }

          fun build(): Implicit =
            Implicit(
              authorizationUrl =
                checkNotNull(authorizationUrl) {
                  "AuthScheme.OAuth2.Flow.Implicit.Builder requires authorizationUrl to be set."
                },
              refreshUrl = refreshUrl,
              scopes = scopes,
            )
        }

        companion object {
          @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
        }
      }

      /**
       * The resource owner password flow.
       *
       * @property tokenUrl The token endpoint.
       * @property refreshUrl The endpoint for refreshing tokens.
       * @property scopes The scopes to request, each mapped to its description.
       */
      @ExperimentalAuthApi
      data class Password(
        val tokenUrl: String,
        override val refreshUrl: String? = null,
        override val scopes: Map<String, String> = emptyMap(),
      ) : Flow {
        /**
         * Returns a [Builder] initialized with this instance's properties, primarily for Java
         * callers. Prefer it over `copy` from Java: `copy` takes every property positionally, so
         * its signature changes whenever a property is added.
         */
        @AdkJavaInteropApi
        fun toBuilder(): Builder =
          Builder().tokenUrl(tokenUrl).refreshUrl(refreshUrl).scopes(scopes)

        /**
         * Fluent builder for [Password], provided primarily for Java callers. Any property left
         * unset falls back to the same default as the constructor.
         */
        @AdkJavaInteropApi
        @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
        class Builder {
          private var tokenUrl: String? = null
          private var refreshUrl: String? = null
          private var scopes: Map<String, String> = emptyMap()

          fun tokenUrl(tokenUrl: String): Builder = apply { this.tokenUrl = tokenUrl }

          fun refreshUrl(refreshUrl: String?): Builder = apply { this.refreshUrl = refreshUrl }

          fun scopes(scopes: Map<String, String>): Builder = apply { this.scopes = scopes }

          fun build(): Password =
            Password(
              tokenUrl =
                checkNotNull(tokenUrl) {
                  "AuthScheme.OAuth2.Flow.Password.Builder requires tokenUrl to be set."
                },
              refreshUrl = refreshUrl,
              scopes = scopes,
            )
        }

        companion object {
          @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
        }
      }

      /**
       * The client credentials flow.
       *
       * @property tokenUrl The token endpoint.
       * @property refreshUrl The endpoint for refreshing tokens.
       * @property scopes The scopes to request, each mapped to its description.
       */
      @ExperimentalAuthApi
      data class ClientCredentials(
        val tokenUrl: String,
        override val refreshUrl: String? = null,
        override val scopes: Map<String, String> = emptyMap(),
      ) : Flow {
        /**
         * Returns a [Builder] initialized with this instance's properties, primarily for Java
         * callers. Prefer it over `copy` from Java: `copy` takes every property positionally, so
         * its signature changes whenever a property is added.
         */
        @AdkJavaInteropApi
        fun toBuilder(): Builder =
          Builder().tokenUrl(tokenUrl).refreshUrl(refreshUrl).scopes(scopes)

        /**
         * Fluent builder for [ClientCredentials], provided primarily for Java callers. Any property
         * left unset falls back to the same default as the constructor.
         */
        @AdkJavaInteropApi
        @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
        class Builder {
          private var tokenUrl: String? = null
          private var refreshUrl: String? = null
          private var scopes: Map<String, String> = emptyMap()

          fun tokenUrl(tokenUrl: String): Builder = apply { this.tokenUrl = tokenUrl }

          fun refreshUrl(refreshUrl: String?): Builder = apply { this.refreshUrl = refreshUrl }

          fun scopes(scopes: Map<String, String>): Builder = apply { this.scopes = scopes }

          fun build(): ClientCredentials =
            ClientCredentials(
              tokenUrl =
                checkNotNull(tokenUrl) {
                  "AuthScheme.OAuth2.Flow.ClientCredentials.Builder requires tokenUrl to be set."
                },
              refreshUrl = refreshUrl,
              scopes = scopes,
            )
        }

        companion object {
          @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
        }
      }

      /**
       * The authorization code flow.
       *
       * @property authorizationUrl The authorization endpoint.
       * @property tokenUrl The token endpoint.
       * @property refreshUrl The endpoint for refreshing tokens.
       * @property scopes The scopes to request, each mapped to its description.
       */
      @ExperimentalAuthApi
      data class AuthorizationCode(
        val authorizationUrl: String,
        val tokenUrl: String,
        override val refreshUrl: String? = null,
        override val scopes: Map<String, String> = emptyMap(),
      ) : Flow {
        /**
         * Returns a [Builder] initialized with this instance's properties, primarily for Java
         * callers. Prefer it over `copy` from Java: `copy` takes every property positionally, so
         * its signature changes whenever a property is added.
         */
        @AdkJavaInteropApi
        fun toBuilder(): Builder =
          Builder()
            .authorizationUrl(authorizationUrl)
            .tokenUrl(tokenUrl)
            .refreshUrl(refreshUrl)
            .scopes(scopes)

        /**
         * Fluent builder for [AuthorizationCode], provided primarily for Java callers. Any property
         * left unset falls back to the same default as the constructor.
         */
        @AdkJavaInteropApi
        @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
        class Builder {
          private var authorizationUrl: String? = null
          private var tokenUrl: String? = null
          private var refreshUrl: String? = null
          private var scopes: Map<String, String> = emptyMap()

          fun authorizationUrl(authorizationUrl: String): Builder = apply {
            this.authorizationUrl = authorizationUrl
          }

          fun tokenUrl(tokenUrl: String): Builder = apply { this.tokenUrl = tokenUrl }

          fun refreshUrl(refreshUrl: String?): Builder = apply { this.refreshUrl = refreshUrl }

          fun scopes(scopes: Map<String, String>): Builder = apply { this.scopes = scopes }

          fun build(): AuthorizationCode =
            AuthorizationCode(
              authorizationUrl =
                checkNotNull(authorizationUrl) {
                  "AuthScheme.OAuth2.Flow.AuthorizationCode.Builder requires authorizationUrl " +
                    "to be set."
                },
              tokenUrl =
                checkNotNull(tokenUrl) {
                  "AuthScheme.OAuth2.Flow.AuthorizationCode.Builder requires tokenUrl to be set."
                },
              refreshUrl = refreshUrl,
              scopes = scopes,
            )
        }

        companion object {
          @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
        }
      }
    }

    /**
     * Fluent builder for [OAuth2], provided primarily for Java callers. Any property left unset
     * falls back to the same default as the constructor.
     */
    @AdkJavaInteropApi
    @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
    class Builder {
      private var flows: Flows? = null
      private var issuerUrl: String? = null
      private var description: String? = null

      fun flows(flows: Flows): Builder = apply { this.flows = flows }

      fun issuerUrl(issuerUrl: String?): Builder = apply { this.issuerUrl = issuerUrl }

      fun description(description: String?): Builder = apply { this.description = description }

      fun build(): OAuth2 =
        OAuth2(
          flows = checkNotNull(flows) { "AuthScheme.OAuth2.Builder requires flows to be set." },
          issuerUrl = issuerUrl,
          description = description,
        )
    }

    companion object {
      /**
       * Returns a scheme with only the authorization code flow, for calls the client makes on a
       * user's behalf after the user consents.
       */
      @JvmStatic
      fun authorizationCode(
        authorizationUrl: String,
        tokenUrl: String,
        scopes: Map<String, String>,
      ): OAuth2 =
        OAuth2(
          flows =
            Flows(
              authorizationCode =
                Flow.AuthorizationCode(
                  authorizationUrl = authorizationUrl,
                  tokenUrl = tokenUrl,
                  scopes = scopes,
                )
            )
        )

      /**
       * Returns a scheme with only the client credentials flow, for calls the client makes on its
       * own behalf, with no user.
       */
      @JvmStatic
      fun clientCredentials(tokenUrl: String, scopes: Map<String, String>): OAuth2 =
        OAuth2(
          flows =
            Flows(clientCredentials = Flow.ClientCredentials(tokenUrl = tokenUrl, scopes = scopes))
        )

      @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
    }
  }
}
