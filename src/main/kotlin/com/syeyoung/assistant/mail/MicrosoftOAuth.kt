package com.syeyoung.assistant.mail

import com.syeyoung.assistant.config.AppProperties
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Base64

data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresIn: Long, val interval: Long)

/** Tokens from Microsoft; [email] comes from the ID token (the mailbox to log in to). */
data class Tokens(val accessToken: String, val refreshToken: String, val expiresIn: Long, val email: String?)

sealed interface PollResult {
    data object Pending : PollResult
    data object SlowDown : PollResult
    data class Done(val tokens: Tokens) : PollResult
    data class Failed(val error: String, val description: String) : PollResult
}

class OAuthException(val error: String, message: String) : RuntimeException(message)

/**
 * The OAuth 2.0 device authorization grant (RFC 8628) against the Microsoft identity platform:
 * the user signs in on another device with a short code, so this server needs no redirect URL.
 */
@Component
class MicrosoftOAuth(props: AppProperties, private val mapper: ObjectMapper, web: WebClient.Builder) {
    private val config = props.sources.mail
    private val http = web.build()
    private val base get() = "${config.authority.trimEnd('/')}/${config.tenant}/oauth2/v2.0"

    suspend fun startDeviceFlow(): DeviceCode {
        val json = post("$base/devicecode", mapOf("client_id" to config.clientId, "scope" to SCOPES))
        json.path("error").asString(null)?.let { throw OAuthException(it, json.path("error_description").asString(it)) }
        return DeviceCode(
            deviceCode = json.path("device_code").asString(),
            userCode = json.path("user_code").asString(),
            verificationUri = json.path("verification_uri").asString(),
            expiresIn = json.path("expires_in").asLong(900),
            interval = json.path("interval").asLong(5),
        )
    }

    suspend fun poll(deviceCode: String): PollResult {
        val json = post(
            "$base/token",
            mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to config.clientId, "device_code" to deviceCode),
        )
        return when (val error = json.path("error").asString(null)) {
            null -> PollResult.Done(tokens(json))
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            else -> PollResult.Failed(error, json.path("error_description").asString(error))
        }
    }

    /** New tokens for a refresh token; Microsoft rotates the refresh token, so store the new one. */
    suspend fun refresh(refreshToken: String): Tokens {
        val json = post(
            "$base/token",
            mapOf("grant_type" to "refresh_token", "client_id" to config.clientId, "refresh_token" to refreshToken, "scope" to SCOPES),
        )
        json.path("error").asString(null)?.let { throw OAuthException(it, json.path("error_description").asString(it)) }
        return tokens(json).let { if (it.refreshToken.isEmpty()) it.copy(refreshToken = refreshToken) else it }
    }

    private fun tokens(json: JsonNode) = Tokens(
        accessToken = json.path("access_token").asString(),
        refreshToken = json.path("refresh_token").asString(""),
        expiresIn = json.path("expires_in").asLong(3600),
        email = json.path("id_token").asString(null)?.let(::emailOf),
    )

    /** The signed-in mailbox from the ID token's claims (it came straight from Microsoft over TLS). */
    private fun emailOf(idToken: String): String? = runCatching {
        val claims = mapper.readTree(Base64.getUrlDecoder().decode(idToken.split('.')[1]))
        listOf("preferred_username", "email", "upn").firstNotNullOfOrNull { claims.path(it).asString(null)?.takeIf(String::isNotBlank) }
    }.getOrNull()

    /** A form POST; errors come back as JSON too (with a 4xx), so the body is read whatever the status. */
    private suspend fun post(url: String, form: Map<String, String>): JsonNode =
        http.post().uri(url).body(BodyInserters.fromFormData(LinkedMultiValueMap<String, String>().apply { form.forEach { (k, v) -> add(k, v) } }))
            .awaitExchange { r ->
                runCatching { r.awaitBody<JsonNode>() }.getOrElse { throw OAuthException("bad_response", "Microsoft sign-in answered HTTP ${r.statusCode().value()}") }
            }

    companion object {
        /** IMAP on Exchange Online, a refresh token, and the ID token that names the mailbox. */
        const val SCOPES = "https://outlook.office.com/IMAP.AccessAsUser.All offline_access openid profile email"
    }
}
