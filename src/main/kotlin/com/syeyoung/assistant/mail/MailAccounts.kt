package com.syeyoung.assistant.mail

import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.TokenCipher
import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.config.AppProperties
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

data class MailAccountStatus(
    /** The app has a client ID and an encryption key, so an account can be connected. */
    val configured: Boolean,
    /** disconnected · pending (waiting for the sign-in with [userCode]) · connected · error */
    val state: String,
    val email: String? = null,
    val userCode: String? = null,
    val verificationUri: String? = null,
    val expiresAt: Instant? = null,
    val error: String? = null,
)

/**
 * The connected mail account: the device-code sign-in, the encrypted refresh token, and fresh
 * access tokens for IMAP. One account for now (POSTECH's Outlook).
 */
@Service
class MailAccounts(
    private val db: DatabaseClient,
    private val oauth: MicrosoftOAuth,
    private val cipher: TokenCipher,
    private val clock: Clock,
    props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val config = props.sources.mail
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private data class Pending(val code: DeviceCode, val expiresAt: Instant, val job: Job)

    @Volatile private var pending: Pending? = null
    @Volatile private var flowError: String? = null
    @Volatile private var cachedToken: Pair<String, Instant>? = null

    val configured get() = config.clientId.isNotBlank() && cipher.configured

    suspend fun status(): MailAccountStatus {
        if (!configured) return MailAccountStatus(false, "disconnected", error = "Mail isn't set up on the server (client ID and token key)")
        val now = Instant.now(clock)
        pending?.takeIf { it.expiresAt.isAfter(now) }?.let {
            return MailAccountStatus(true, "pending", userCode = it.code.userCode, verificationUri = it.code.verificationUri, expiresAt = it.expiresAt)
        }
        val account = account()
        return when {
            account == null -> MailAccountStatus(true, if (flowError != null) "error" else "disconnected", error = flowError)
            account.lastError != null -> MailAccountStatus(true, "error", email = account.email, error = account.lastError)
            else -> MailAccountStatus(true, "connected", email = account.email)
        }
    }

    /** Starts a sign-in: returns the code to enter at Microsoft, and waits for it in the background. */
    suspend fun connect(): MailAccountStatus {
        if (!configured) throw ConflictException("not_configured", "Mail isn't set up on the server (client ID and token key)")
        pending?.job?.cancel()
        flowError = null
        val code = try {
            oauth.startDeviceFlow()
        } catch (e: OAuthException) {
            throw ConflictException("signin_failed", "Microsoft refused the sign-in: ${e.message}")
        }
        val expiresAt = Instant.now(clock).plusSeconds(code.expiresIn)
        val job = scope.launch { await(code, expiresAt) }
        pending = Pending(code, expiresAt, job)
        return status()
    }

    suspend fun disconnect() {
        pending?.job?.cancel()
        pending = null
        flowError = null
        cachedToken = null
        db.sql("delete from mail_accounts").fetch().awaitRowsUpdated()
    }

    /** The signed-in mailbox and an access token for it; refreshes (and stores the rotated refresh token) as needed. */
    suspend fun accessToken(): Pair<String, String> = lock.withLock {
        val account = account() ?: throw MailNotConnected("Not connected: connect your mail in Sources")
        if (account.lastError != null) throw MailNotConnected("Sign-in expired: reconnect your mail in Sources")
        val now = Instant.now(clock)
        cachedToken?.let { (token, until) -> if (now.isBefore(until)) return account.email to token }
        val tokens = try {
            oauth.refresh(cipher.decrypt(account.refreshToken))
        } catch (e: OAuthException) {
            // invalid_grant and friends: the refresh token no longer works; only signing in again helps.
            if (e.error != "bad_response") markBroken(account.email, e.message ?: e.error)
            throw MailNotConnected("Sign-in failed: ${e.message}")
        }
        save(account.email, tokens.refreshToken)
        cachedToken = tokens.accessToken to now.plusSeconds(tokens.expiresIn - 120)
        account.email to tokens.accessToken
    }

    private suspend fun await(code: DeviceCode, expiresAt: Instant) {
        var interval = code.interval
        try {
            while (Instant.now(clock).isBefore(expiresAt)) {
                delay(interval * 1000)
                when (val r = oauth.poll(code.deviceCode)) {
                    PollResult.Pending -> {}
                    PollResult.SlowDown -> interval += 5
                    is PollResult.Done -> {
                        val email = r.tokens.email ?: error("Microsoft didn't say which mailbox signed in")
                        db.sql("delete from mail_accounts").fetch().awaitRowsUpdated()
                        save(email, r.tokens.refreshToken)
                        cachedToken = r.tokens.accessToken to Instant.now(clock).plusSeconds(r.tokens.expiresIn - 120)
                        log.info("Mail connected: {}", email)
                        return
                    }
                    is PollResult.Failed -> {
                        flowError = r.description.lineSequence().first()
                        return
                    }
                }
            }
            flowError = "The sign-in code expired; connect again"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Mail sign-in failed: {}", e.toString())
            flowError = e.message ?: e.javaClass.simpleName
        } finally {
            if (pending?.code == code) pending = null
        }
    }

    private data class Account(val email: String, val refreshToken: ByteArray, val lastError: String?)

    private suspend fun account(): Account? =
        db.sql("select * from mail_accounts order by connected_at limit 1").map { r ->
            Account(r.req("email"), r.req<java.nio.ByteBuffer>("refresh_token").let { b -> ByteArray(b.remaining()).also { b.get(it) } }, r.opt("last_error"))
        }.awaitOneOrNull()

    private suspend fun save(email: String, refreshToken: String) {
        db.sql(
            """
            insert into mail_accounts (email, refresh_token, connected_at) values (:email, :token, :now)
            on conflict (email) do update set refresh_token = :token, last_error = null
            """,
        ).bind("email", email).bind("token", java.nio.ByteBuffer.wrap(cipher.encrypt(refreshToken))).bind("now", Instant.now(clock))
            .fetch().awaitRowsUpdated()
    }

    private suspend fun markBroken(email: String, error: String) {
        cachedToken = null
        db.sql("update mail_accounts set last_error = :error where email = :email").bind("email", email).bind("error", error.take(500))
            .fetch().awaitRowsUpdated()
    }

    /** For tests: the next [accessToken] call goes to Microsoft. */
    internal fun forgetAccessToken() {
        cachedToken = null
    }

    @PreDestroy
    fun stop() = scope.cancel()
}

class MailNotConnected(message: String) : RuntimeException(message)
