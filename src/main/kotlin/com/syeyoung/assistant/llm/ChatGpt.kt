package com.syeyoung.assistant.llm

import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.TokenCipher
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.config.AppProperties
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

// ---- sign-in (OAuth 2.0 authorization code with PKCE, as the Codex CLI signs in) ------------

/** Tokens from OpenAI; the account, email and plan come from the ID token's claims. */
data class ChatGptTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant,
    val accountId: String?,
    val email: String?,
    val plan: String?,
)

class ChatGptAuthException(val error: String, message: String) : RuntimeException(message)

/**
 * OpenAI's sign-in with Codex's public client. Its only allowed redirect is the CLI's
 * `http://localhost:1455/auth/callback`, so the browser lands on a page that doesn't load and the
 * user pastes that address back; the code in it is exchanged here, with the PKCE verifier.
 */
@Component
class ChatGptOAuth(props: AppProperties, private val mapper: ObjectMapper, private val clock: Clock, web: WebClient.Builder) {
    private val config = props.llm
    private val http = web.baseUrl(config.issuer.trimEnd('/')).build()

    fun authorizeUrl(state: String, verifier: String): String {
        val params = mapOf(
            "response_type" to "code",
            "client_id" to config.clientId,
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES,
            "code_challenge" to challenge(verifier),
            "code_challenge_method" to "S256",
            "state" to state,
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to "codex_cli_rs",
        )
        return UriComponentsBuilder.fromUriString("${config.issuer.trimEnd('/')}/oauth/authorize")
            .apply { params.forEach { (k, v) -> queryParam(k, v) } }.encode().build().toUriString()
    }

    suspend fun exchange(code: String, verifier: String): ChatGptTokens = tokens(
        post(mapOf("grant_type" to "authorization_code", "client_id" to config.clientId, "code" to code, "redirect_uri" to REDIRECT_URI, "code_verifier" to verifier)),
    )

    suspend fun refresh(refreshToken: String): ChatGptTokens = tokens(
        post(mapOf("grant_type" to "refresh_token", "client_id" to config.clientId, "refresh_token" to refreshToken, "scope" to "openid profile email")),
    )

    private fun tokens(json: JsonNode): ChatGptTokens {
        val access = json.path("access_token").asString("").ifEmpty { throw ChatGptAuthException("bad_response", "OpenAI sent no access token") }
        val id = json.path("id_token").asString(null)?.let(::claims)
        val auth = id?.path(AUTH_CLAIM)
        // When the access token expires: its own "exp" claim, else expires_in, else an hour.
        val expiresAt = claims(access)?.path("exp")?.asLong(0)?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)
            ?: Instant.now(clock).plusSeconds(json.path("expires_in").asLong(3600))
        return ChatGptTokens(
            accessToken = access,
            refreshToken = json.path("refresh_token").asString(null)?.takeIf(String::isNotBlank),
            expiresAt = expiresAt,
            accountId = auth?.path("chatgpt_account_id")?.asString(null) ?: id?.path("organizations")?.path(0)?.path("id")?.asString(null),
            email = id?.path("email")?.asString(null),
            plan = auth?.path("chatgpt_plan_type")?.asString(null),
        )
    }

    /** A JWT's claims, unverified: the token came straight from OpenAI over TLS. */
    private fun claims(jwt: String): JsonNode? = runCatching {
        mapper.readTree(Base64.getUrlDecoder().decode(jwt.split('.')[1]))
    }.getOrNull()

    private suspend fun post(params: Map<String, String>): JsonNode =
        http.post().uri("/oauth/token").body(BodyInserters.fromFormData(LinkedMultiValueMap<String, String>().apply { params.forEach { (k, v) -> add(k, v) } }))
            .awaitExchange { r ->
                val status = r.statusCode()
                val json = runCatching { r.awaitBody<JsonNode>() }.getOrElse { throw ChatGptAuthException("bad_response", "OpenAI sign-in answered HTTP ${status.value()}") }
                if (!status.is2xxSuccessful) {
                    val error = json.path("error").let { if (it.isObject) it.path("code").asString("error") else it.asString("error") }
                    val message = json.path("error_description").asString(null) ?: json.path("error").path("message").asString(null) ?: "HTTP ${status.value()}"
                    throw ChatGptAuthException(error, message)
                }
                json
            }

    companion object {
        const val REDIRECT_URI = "http://localhost:1455/auth/callback"
        const val SCOPES = "openid profile email offline_access"
        const val AUTH_CLAIM = "https://api.openai.com/auth"

        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    }
}

// ---- the connected account -------------------------------------------------------------

/** Use of one of the Codex quota's windows (e.g. the 5-hour and the weekly one), from the last reply's headers. */
data class QuotaWindow(val name: String, val usedPercent: Double, val resetsAt: Instant?)

data class ChatGptStatus(
    /** The server can keep a sign-in (it has the token key). */
    val configured: Boolean,
    /** disconnected · pending (sign-in link made, waiting for the pasted address) · connected · error */
    val state: String,
    val email: String? = null,
    val plan: String? = null,
    val model: String,
    val quota: List<QuotaWindow> = emptyList(),
    val error: String? = null,
)

data class ChatGptConnect(
    /** Open this, sign in, then paste the address the browser ends up on (it won't load) into complete. */
    val authorizeUrl: String,
)

data class ChatGptComplete(
    /** The `http://localhost:1455/auth/callback?code=…&state=…` address, or just the code. */
    val redirectUrl: String,
)

class ChatGptNotConnected(message: String) : RuntimeException(message)

/** Credentials for one model call. */
data class ChatGptCredentials(val accessToken: String, val accountId: String)

/**
 * The ChatGPT account the model runs on: the sign-in, the encrypted refresh token, fresh access
 * tokens (refreshed a minute before they expire), and the quota last reported.
 */
@Service
class ChatGptAccount(
    private val db: DatabaseClient,
    private val oauth: ChatGptOAuth,
    private val cipher: TokenCipher,
    private val clock: Clock,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Mutex()

    private data class Pending(val state: String, val verifier: String, val expiresAt: Instant)

    @Volatile private var pending: Pending? = null
    @Volatile private var cached: Pair<ChatGptCredentials, Instant>? = null
    @Volatile private var quota: List<QuotaWindow> = emptyList()

    suspend fun connected() = cipher.configured && account()?.let { it.lastError == null } == true

    suspend fun status(): ChatGptStatus {
        val model = props.llm.model
        if (!cipher.configured) return ChatGptStatus(false, "disconnected", model = model, error = "The server has no token key (MAIL_TOKEN_KEY)")
        val account = account()
        val waiting = pending?.takeIf { it.expiresAt.isAfter(Instant.now(clock)) } != null
        return when {
            account == null -> ChatGptStatus(true, if (waiting) "pending" else "disconnected", model = model)
            account.lastError != null -> ChatGptStatus(true, "error", account.email, account.plan, model, error = account.lastError)
            else -> ChatGptStatus(true, "connected", account.email, account.plan, model, quota)
        }
    }

    /** Makes a sign-in link; the sign-in is finished by [complete] with the address it ends on. */
    fun connect(): ChatGptConnect {
        if (!cipher.configured) throw ConflictException("not_configured", "The server has no token key (MAIL_TOKEN_KEY)")
        val state = random(16)
        val verifier = random(48)
        pending = Pending(state, verifier, Instant.now(clock).plus(PENDING_FOR))
        return ChatGptConnect(oauth.authorizeUrl(state, verifier))
    }

    suspend fun complete(req: ChatGptComplete): ChatGptStatus {
        val p = pending?.takeIf { it.expiresAt.isAfter(Instant.now(clock)) }
            ?: throw ConflictException("no_signin", "The sign-in link expired; connect again")
        val input = req.redirectUrl.trim()
        val query = if ("?" in input) query(input.substringAfter('?').substringBefore('#')) else mapOf("code" to input)
        query["error"]?.let { throw ValidationException("OpenAI refused the sign-in: ${query["error_description"] ?: it}") }
        val code = query["code"]?.takeIf(String::isNotBlank) ?: throw ValidationException("That address has no code in it")
        if (query["state"] != null && query["state"] != p.state) throw ValidationException("That address is from a different sign-in; use the latest link")
        val tokens = try {
            oauth.exchange(code, p.verifier)
        } catch (e: ChatGptAuthException) {
            throw ConflictException("signin_failed", "OpenAI refused the sign-in: ${e.message}")
        }
        val email = tokens.email ?: throw ConflictException("signin_failed", "OpenAI didn't say which account signed in")
        val accountId = tokens.accountId ?: throw ConflictException("signin_failed", "That account has no ChatGPT workspace")
        val refresh = tokens.refreshToken ?: throw ConflictException("signin_failed", "OpenAI sent no refresh token")
        lock.withLock {
            db.sql("delete from chatgpt_accounts").fetch().awaitRowsUpdated()
            db.sql("insert into chatgpt_accounts (email, account_id, plan, refresh_token, connected_at) values (:email, :account, :plan, :token, :now)")
                .bind("email", email).bind("account", accountId).bind("token", java.nio.ByteBuffer.wrap(cipher.encrypt(refresh))).bind("now", Instant.now(clock))
                .let { s -> tokens.plan?.let { s.bind("plan", it) } ?: s.bindNull("plan", String::class.java) }
                .fetch().awaitRowsUpdated()
            cached = ChatGptCredentials(tokens.accessToken, accountId) to tokens.expiresAt
            quota = emptyList()
        }
        pending = null
        log.info("ChatGPT connected: {} ({})", email, tokens.plan)
        return status()
    }

    suspend fun disconnect() = lock.withLock {
        pending = null
        cached = null
        quota = emptyList()
        db.sql("delete from chatgpt_accounts").fetch().awaitRowsUpdated()
    }

    /** An access token and the account to send with it; refreshes (storing the rotated refresh token) as needed. */
    suspend fun credentials(): ChatGptCredentials = lock.withLock {
        val now = Instant.now(clock)
        cached?.let { (c, until) -> if (now.isBefore(until.minus(REFRESH_EARLY))) return c }
        val account = account() ?: throw ChatGptNotConnected("Connect ChatGPT in Sources to analyze")
        if (account.lastError != null) throw ChatGptNotConnected("The ChatGPT sign-in stopped working; reconnect it in Sources")
        val tokens = try {
            oauth.refresh(cipher.decrypt(account.refreshToken))
        } catch (e: ChatGptAuthException) {
            // A rejected refresh token (expired, revoked, reused) only comes back by signing in again.
            if (e.error != "bad_response") markBroken(account.email, e.message ?: e.error)
            throw ChatGptNotConnected("ChatGPT sign-in failed: ${e.message}")
        }
        tokens.refreshToken?.let { r ->
            db.sql("update chatgpt_accounts set refresh_token = :token where email = :email")
                .bind("token", java.nio.ByteBuffer.wrap(cipher.encrypt(r))).bind("email", account.email).fetch().awaitRowsUpdated()
        }
        ChatGptCredentials(tokens.accessToken, tokens.accountId ?: account.accountId).also { cached = it to tokens.expiresAt }
    }

    /** After a 401: the next call refreshes. */
    fun forgetAccessToken() {
        cached = null
    }

    /** Until when the backend asked us to stop sending (after an HTTP 429); null when it hasn't. */
    @Volatile private var limited: Instant? = null
    val limitedUntil: Instant? get() = limited

    /** A 429 ("too many requests"): wait as long as its Retry-After says (seconds or a date), else a minute. */
    fun recordLimit(headers: HttpHeaders) {
        val after = headers.getFirst(HttpHeaders.RETRY_AFTER)?.trim()
        val now = Instant.now(clock)
        val until = after?.toLongOrNull()?.let { now.plusSeconds(it) }
            ?: after?.let { runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
            ?: now.plus(Duration.ofMinutes(1))
        limited = maxOf(until, limited ?: until)
        log.warn("Codex backend asked to slow down until {}", until)
    }

    /** For tests: as if the wait were over. */
    internal fun clearLimit() {
        limited = null
    }

    /** Keeps the quota use the Codex backend reports with each reply (x-codex-primary-used-percent and the like). */
    fun recordQuota(headers: HttpHeaders) {
        val windows = listOf("primary", "secondary", "tertiary").mapNotNull { w ->
            val used = headers.getFirst("x-codex-$w-used-percent")?.toDoubleOrNull() ?: return@mapNotNull null
            val minutes = headers.getFirst("x-codex-$w-window-minutes")?.toLongOrNull()
            QuotaWindow(windowName(minutes) ?: w, used, headers.getFirst("x-codex-$w-reset-at")?.let(::instant))
        }
        if (windows.isNotEmpty()) quota = windows
    }

    private data class Account(val email: String, val accountId: String, val plan: String?, val refreshToken: ByteArray, val lastError: String?)

    private suspend fun account(): Account? =
        db.sql("select * from chatgpt_accounts order by connected_at limit 1").map { r ->
            Account(
                r.req("email"), r.req("account_id"), r.opt("plan"),
                r.req<java.nio.ByteBuffer>("refresh_token").let { b -> ByteArray(b.remaining()).also { b.get(it) } }, r.opt("last_error"),
            )
        }.awaitOneOrNull()

    private suspend fun markBroken(email: String, error: String) {
        cached = null
        db.sql("update chatgpt_accounts set last_error = :error where email = :email").bind("email", email).bind("error", error.take(500))
            .fetch().awaitRowsUpdated()
    }

    private fun query(q: String) = q.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }

    private fun random(bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private fun windowName(minutes: Long?) = when {
        minutes == null -> null
        minutes == 10080L -> "Weekly"
        minutes % 1440 == 0L && minutes >= 1440 -> "${minutes / 1440}-day"
        minutes % 60 == 0L -> "${minutes / 60}-hour"
        else -> "$minutes-minute"
    }

    /** Epoch seconds (or milliseconds), or an ISO instant. */
    private fun instant(s: String): Instant? = s.toLongOrNull()?.let { if (it > 100_000_000_000) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it) }
        ?: runCatching { Instant.parse(s) }.getOrNull()

    companion object {
        val PENDING_FOR: Duration = Duration.ofMinutes(15)
        val REFRESH_EARLY: Duration = Duration.ofSeconds(60)
    }
}

@RestController
@RequestMapping("/api/v1/chatgpt")
class ChatGptController(private val account: ChatGptAccount) {

    @GetMapping("/account")
    suspend fun chatgptAccount(): ChatGptStatus = account.status()

    /** Starts a sign-in: open the returned link, sign in, and send the address it ends on to /complete. */
    @PostMapping("/connect")
    fun chatgptConnect(): ChatGptConnect = account.connect()

    @PostMapping("/complete")
    suspend fun chatgptComplete(@RequestBody req: ChatGptComplete): ChatGptStatus = account.complete(req)

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun chatgptDisconnect() {
        account.disconnect()
    }
}
