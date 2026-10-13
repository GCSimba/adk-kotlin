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
import com.google.adk.kt.annotations.SecretAccess
import kotlin.jvm.JvmStatic

/**
 * A credential that a tool uses to call a service, such as an API key or an HTTP credential.
 *
 * Secret fields are [Secret]s, so a credential's `toString()` prints them as `<redacted>`.
 */
@ExperimentalAuthApi
sealed interface AuthCredential {

  /**
   * An API key, which the tool sends where the service's auth scheme says, such as in a header.
   *
   * @property apiKey The key, if the credential carries one.
   */
  @ExperimentalAuthApi
  data class ApiKey(val apiKey: Secret? = null) : AuthCredential {
    /**
     * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
     * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
     * changes whenever a property is added.
     */
    @AdkJavaInteropApi
    @OptIn(SecretAccess::class)
    fun toBuilder(): Builder = Builder().apiKey(apiKey?.reveal())

    /**
     * Fluent builder for [ApiKey], provided primarily for Java callers. Any property left unset
     * falls back to the same default as the constructor.
     */
    @AdkJavaInteropApi
    @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
    class Builder {
      private var apiKey: String? = null

      fun apiKey(apiKey: String?): Builder = apply { this.apiKey = apiKey }

      fun build(): ApiKey = ApiKey(apiKey = apiKey?.let(::Secret))
    }

    companion object {
      @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
    }
  }

  /**
   * An HTTP authentication credential, usually sent in the `Authorization` header.
   *
   * @property scheme The `Authorization` header scheme (RFC 7235), such as `basic` or `bearer`.
   * @property credentials The user name and password, or the token.
   * @property additionalHeaders Extra headers to send with the request. Their values are secret.
   */
  @ExperimentalAuthApi
  data class Http(
    val scheme: String,
    val credentials: Credentials,
    val additionalHeaders: Map<String, Secret>? = null,
  ) : AuthCredential {
    /**
     * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
     * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
     * changes whenever a property is added.
     */
    @AdkJavaInteropApi
    @OptIn(SecretAccess::class)
    fun toBuilder(): Builder =
      Builder()
        .scheme(scheme)
        .credentials(credentials)
        .additionalHeaders(additionalHeaders?.mapValues { (_, value) -> value.reveal() })

    /**
     * What an HTTP credential authenticates with: a user name and password, or a token.
     *
     * @property username The user name, for the `basic` scheme.
     * @property password The password, for the `basic` scheme.
     * @property token The token, for schemes such as `bearer`.
     */
    @ExperimentalAuthApi
    data class Credentials(
      val username: String? = null,
      val password: Secret? = null,
      val token: Secret? = null,
    ) {
      /**
       * Returns a [Builder] initialized with this instance's properties, primarily for Java
       * callers. Prefer it over `copy` from Java: `copy` takes every property positionally, so its
       * signature changes whenever a property is added.
       */
      @AdkJavaInteropApi
      @OptIn(SecretAccess::class)
      fun toBuilder(): Builder =
        Builder().username(username).password(password?.reveal()).token(token?.reveal())

      /**
       * Fluent builder for [Credentials], provided primarily for Java callers. Any property left
       * unset falls back to the same default as the constructor.
       */
      @AdkJavaInteropApi
      @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
      class Builder {
        private var username: String? = null
        private var password: String? = null
        private var token: String? = null

        fun username(username: String?): Builder = apply { this.username = username }

        fun password(password: String?): Builder = apply { this.password = password }

        fun token(token: String?): Builder = apply { this.token = token }

        fun build(): Credentials =
          Credentials(
            username = username,
            password = password?.let(::Secret),
            token = token?.let(::Secret),
          )
      }

      companion object {
        @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
      }
    }

    /**
     * Fluent builder for [Http], provided primarily for Java callers. Any property left unset falls
     * back to the same default as the constructor.
     */
    @AdkJavaInteropApi
    @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
    class Builder {
      private var scheme: String? = null
      private var credentials: Credentials? = null
      private var additionalHeaders: Map<String, String>? = null

      fun scheme(scheme: String): Builder = apply { this.scheme = scheme }

      fun credentials(credentials: Credentials): Builder = apply { this.credentials = credentials }

      fun additionalHeaders(additionalHeaders: Map<String, String>?): Builder = apply {
        this.additionalHeaders = additionalHeaders
      }

      fun build(): Http =
        Http(
          scheme =
            checkNotNull(scheme) { "AuthCredential.Http.Builder requires scheme to be set." },
          credentials =
            checkNotNull(credentials) {
              "AuthCredential.Http.Builder requires credentials to be set."
            },
          additionalHeaders = additionalHeaders?.mapValues { (_, value) -> Secret(value) },
        )
    }

    companion object {
      // Factories take only a credential's defining fields, so adding a field never changes them.

      /** Returns a `bearer` credential carrying [token]. */
      @JvmStatic
      fun bearer(token: String): Http =
        Http(scheme = "bearer", credentials = Credentials(token = Secret(token)))

      /** Returns a `basic` credential for [username] and [password]. */
      @JvmStatic
      fun basic(username: String, password: String): Http =
        Http(
          scheme = "basic",
          credentials = Credentials(username = username, password = Secret(password)),
        )

      @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
    }
  }
}
