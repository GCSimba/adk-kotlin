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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A credential that a tool uses to call a service: an API key, an HTTP credential, or an OAuth2 or
 * OpenID Connect credential.
 *
 * Secret fields are [Secret]s, so a credential's `toString()` prints them as `<redacted>`. [OAuth2]
 * also redacts its `state`, `nonce`, `authUri` and `redirectUri`, which are plain strings.
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

  /**
   * An OAuth2 or OpenID Connect credential: the client's configuration, the sign-in state and, once
   * the user has signed in, the tokens.
   *
   * @property variant Whether this is an OAuth2 or an OpenID Connect credential.
   * @property clientId The OAuth2 client ID.
   * @property clientSecret The OAuth2 client secret; absent for a public client.
   * @property authUri Where to send the user to sign in.
   * @property nonce The `nonce` parameter of the authorization request, which ties the request to
   *   the user's session.
   * @property state The `state` value that ties the provider's redirect to this sign-in.
   * @property redirectUri Where the provider sends the user after signing in.
   * @property authResponseUri The full redirect URL the provider returned, including the code.
   * @property authCode The authorization code the provider returned, which the token exchange
   *   trades for tokens.
   * @property accessToken The access token that authorizes the tool's calls to the service.
   * @property refreshToken The refresh token, which gets a new access token when [accessToken]
   *   expires.
   * @property idToken The OpenID Connect ID token, which identifies the signed-in user.
   * @property expiresAt When the access token expires.
   * @property expiresIn The access token's lifetime, as the token response gives it.
   * @property audience The audience to request a token for.
   * @property prompt The `prompt` parameter of the authorization request.
   * @property codeVerifier The PKCE code verifier, sent with [authCode] to the token endpoint.
   * @property codeChallengeMethod The PKCE code-challenge method; only `S256` is supported.
   * @property tokenEndpointAuthMethod How the client authenticates to the token endpoint; `null`
   *   leaves the choice to the token exchange.
   */
  @ExperimentalAuthApi
  data class OAuth2(
    val variant: Variant = Variant.OAUTH2,
    val clientId: String? = null,
    val clientSecret: Secret? = null,
    val authUri: String? = null,
    val nonce: String? = null,
    val state: String? = null,
    val redirectUri: String? = null,
    val authResponseUri: Secret? = null,
    val authCode: Secret? = null,
    val accessToken: Secret? = null,
    val refreshToken: Secret? = null,
    val idToken: Secret? = null,
    val expiresAt: Instant? = null,
    val expiresIn: Duration? = null,
    val audience: String? = null,
    val prompt: String? = null,
    val codeVerifier: Secret? = null,
    val codeChallengeMethod: String? = null,
    val tokenEndpointAuthMethod: TokenEndpointAuthMethod? =
      TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
  ) : AuthCredential {
    /**
     * Returns [expiresIn] truncated to whole seconds, or `null` if it is unset. Java callers use it
     * because they can't call the [Duration] getter.
     */
    fun expiresInSeconds(): Long? = expiresIn?.inWholeSeconds

    // The data-class default would print the sign-in URLs, state and nonce, which aren't Secrets.
    override fun toString(): String =
      "OAuth2(variant=$variant, clientId=$clientId, clientSecret=$clientSecret, " +
        "authUri=${authUri.redacted()}, nonce=${nonce.redacted()}, state=${state.redacted()}, " +
        "redirectUri=${redirectUri.redacted()}, authResponseUri=$authResponseUri, " +
        "authCode=$authCode, accessToken=$accessToken, refreshToken=$refreshToken, " +
        "idToken=$idToken, expiresAt=$expiresAt, expiresIn=$expiresIn, audience=$audience, " +
        "prompt=$prompt, codeVerifier=$codeVerifier, codeChallengeMethod=$codeChallengeMethod, " +
        "tokenEndpointAuthMethod=$tokenEndpointAuthMethod)"

    /**
     * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
     * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
     * changes whenever a property is added.
     */
    @AdkJavaInteropApi
    @OptIn(SecretAccess::class)
    fun toBuilder(): Builder =
      Builder()
        .variant(variant)
        .clientId(clientId)
        .clientSecret(clientSecret?.reveal())
        .authUri(authUri)
        .nonce(nonce)
        .state(state)
        .redirectUri(redirectUri)
        .authResponseUri(authResponseUri?.reveal())
        .authCode(authCode?.reveal())
        .accessToken(accessToken?.reveal())
        .refreshToken(refreshToken?.reveal())
        .idToken(idToken?.reveal())
        .expiresAt(expiresAt)
        .expiresIn(expiresIn)
        .audience(audience)
        .prompt(prompt)
        .codeVerifier(codeVerifier?.reveal())
        .codeChallengeMethod(codeChallengeMethod)
        .tokenEndpointAuthMethod(tokenEndpointAuthMethod)

    /**
     * The protocol of an [OAuth2] credential, OAuth2 or OpenID Connect; both carry the same fields.
     */
    @ExperimentalAuthApi
    enum class Variant {
      OAUTH2,
      OPEN_ID_CONNECT,
    }

    /** The way an OAuth2 client authenticates to the token endpoint (RFC 7591 section 2). */
    @ExperimentalAuthApi
    enum class TokenEndpointAuthMethod {
      CLIENT_SECRET_BASIC,
      CLIENT_SECRET_POST,
      CLIENT_SECRET_JWT,
      PRIVATE_KEY_JWT,
    }

    /**
     * Fluent builder for [OAuth2], provided primarily for Java callers. Any property left unset
     * falls back to the same default as the constructor.
     */
    @AdkJavaInteropApi
    @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
    class Builder {
      private var variant: Variant = Variant.OAUTH2
      private var clientId: String? = null
      private var clientSecret: String? = null
      private var authUri: String? = null
      private var nonce: String? = null
      private var state: String? = null
      private var redirectUri: String? = null
      private var authResponseUri: String? = null
      private var authCode: String? = null
      private var accessToken: String? = null
      private var refreshToken: String? = null
      private var idToken: String? = null
      private var expiresAt: Instant? = null
      private var expiresIn: Duration? = null
      private var audience: String? = null
      private var prompt: String? = null
      private var codeVerifier: String? = null
      private var codeChallengeMethod: String? = null
      private var tokenEndpointAuthMethod: TokenEndpointAuthMethod? =
        TokenEndpointAuthMethod.CLIENT_SECRET_BASIC

      fun variant(variant: Variant): Builder = apply { this.variant = variant }

      fun clientId(clientId: String?): Builder = apply { this.clientId = clientId }

      fun clientSecret(clientSecret: String?): Builder = apply { this.clientSecret = clientSecret }

      fun authUri(authUri: String?): Builder = apply { this.authUri = authUri }

      fun nonce(nonce: String?): Builder = apply { this.nonce = nonce }

      fun state(state: String?): Builder = apply { this.state = state }

      fun redirectUri(redirectUri: String?): Builder = apply { this.redirectUri = redirectUri }

      fun authResponseUri(authResponseUri: String?): Builder = apply {
        this.authResponseUri = authResponseUri
      }

      fun authCode(authCode: String?): Builder = apply { this.authCode = authCode }

      fun accessToken(accessToken: String?): Builder = apply { this.accessToken = accessToken }

      fun refreshToken(refreshToken: String?): Builder = apply { this.refreshToken = refreshToken }

      fun idToken(idToken: String?): Builder = apply { this.idToken = idToken }

      fun expiresAt(expiresAt: Instant?): Builder = apply { this.expiresAt = expiresAt }

      /** Sets [OAuth2.expiresAt] from seconds since the epoch, for Java callers. */
      fun expiresAtEpochSeconds(expiresAtEpochSeconds: Long): Builder = apply {
        this.expiresAt = Instant.fromEpochSeconds(expiresAtEpochSeconds)
      }

      // Lets toBuilder copy expiresIn exactly; expiresInSeconds would truncate it.
      internal fun expiresIn(expiresIn: Duration?): Builder = apply { this.expiresIn = expiresIn }

      /**
       * Sets [OAuth2.expiresIn] from a number of seconds, for Java callers, which can't call a
       * function that takes a [Duration].
       */
      fun expiresInSeconds(expiresInSeconds: Long): Builder = apply {
        this.expiresIn = expiresInSeconds.seconds
      }

      /**
       * Sets [OAuth2.expiresIn] in seconds, or clears it when `null`. The non-null overload lets
       * Java pass an `int` literal.
       */
      fun expiresInSeconds(expiresInSeconds: Long?): Builder = apply {
        this.expiresIn = expiresInSeconds?.seconds
      }

      fun audience(audience: String?): Builder = apply { this.audience = audience }

      fun prompt(prompt: String?): Builder = apply { this.prompt = prompt }

      fun codeVerifier(codeVerifier: String?): Builder = apply { this.codeVerifier = codeVerifier }

      fun codeChallengeMethod(codeChallengeMethod: String?): Builder = apply {
        this.codeChallengeMethod = codeChallengeMethod
      }

      fun tokenEndpointAuthMethod(tokenEndpointAuthMethod: TokenEndpointAuthMethod?): Builder =
        apply {
          this.tokenEndpointAuthMethod = tokenEndpointAuthMethod
        }

      fun build(): OAuth2 =
        OAuth2(
          variant = variant,
          clientId = clientId,
          clientSecret = clientSecret?.let(::Secret),
          authUri = authUri,
          nonce = nonce,
          state = state,
          redirectUri = redirectUri,
          authResponseUri = authResponseUri?.let(::Secret),
          authCode = authCode?.let(::Secret),
          accessToken = accessToken?.let(::Secret),
          refreshToken = refreshToken?.let(::Secret),
          idToken = idToken?.let(::Secret),
          expiresAt = expiresAt,
          expiresIn = expiresIn,
          audience = audience,
          prompt = prompt,
          codeVerifier = codeVerifier?.let(::Secret),
          codeChallengeMethod = codeChallengeMethod,
          tokenEndpointAuthMethod = tokenEndpointAuthMethod,
        )
    }

    companion object {
      @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
    }
  }
}
