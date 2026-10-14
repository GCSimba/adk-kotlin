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
}
