package com.syeyoung.assistant.public

import com.syeyoung.assistant.calendar.CalendarService
import com.syeyoung.assistant.calendar.CreateEventRequest
import com.syeyoung.assistant.capture.AnalyzeRequest
import com.syeyoung.assistant.capture.AnalyzeResponse
import com.syeyoung.assistant.capture.CaptureService
import com.syeyoung.assistant.common.ApiException
import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.tasks.CreateTaskRequest
import com.syeyoung.assistant.tasks.DueDto
import com.syeyoung.assistant.tasks.LabelRequest
import com.syeyoung.assistant.tasks.Priority
import com.syeyoung.assistant.tasks.TaskService
import kotlinx.coroutines.flow.toList
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class UnauthorizedException : ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "A valid token is required")

class TooManyRequestsException : ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests; try again in a minute")

/**
 * Through the public host only paths under /api/v1/public/ are reachable: everything else answers 404 there,
 * whatever the ingress in front lets through.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class PublicHostFilter(props: AppProperties) : WebFilter {
    private val host = props.publicHost.lowercase()

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (host.isEmpty()) return chain.filter(exchange)
        val headers = exchange.request.headers
        val requestHost = (headers.getFirst("X-Forwarded-Host") ?: headers.host?.hostString ?: "").substringBefore(':').lowercase()
        val path = exchange.request.path.value()
        if (requestHost == host && !path.startsWith("/api/v1/public/")) {
            exchange.response.statusCode = HttpStatus.NOT_FOUND
            return exchange.response.setComplete()
        }
        return chain.filter(exchange)
    }
}

data class TokenInfo(val id: UUID, val name: String, val createdAt: Instant, val lastUsed: Instant?)

data class NewToken(val id: UUID, val name: String, val token: String)

data class CreateTokenRequest(val name: String = "Outlook add-in")

@Service
class PublicTokens(private val db: DatabaseClient, private val clock: Clock) {
    /** Requests per token per minute. */
    private val windows = ConcurrentHashMap<UUID, Pair<Long, Int>>()

    suspend fun create(name: String): NewToken {
        val token = "ast_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val id = UUID.randomUUID()
        db.sql("insert into public_tokens (id, name, token_hash, created_at) values (:id, :name, :hash, :now)")
            .bind("id", id).bind("name", name.trim().ifEmpty { "Token" }).bind("hash", hash(token)).bind("now", Instant.now(clock))
            .fetch().awaitRowsUpdated()
        return NewToken(id, name, token)
    }

    suspend fun list(): List<TokenInfo> =
        db.sql("select * from public_tokens where revoked_at is null order by created_at").map { r ->
            TokenInfo(r.req("id"), r.req("name"), r.req("created_at"), r.opt("last_used"))
        }.flow().toList()

    suspend fun revoke(id: UUID) {
        val n = db.sql("update public_tokens set revoked_at = :now where id = :id and revoked_at is null").bind("id", id).bind("now", Instant.now(clock))
            .fetch().awaitRowsUpdated()
        if (n == 0L) throw NotFoundException("Token", id)
    }

    /** Checks a bearer token and the rate limit (60 requests a minute). */
    suspend fun authorize(header: String?) {
        val token = header?.removePrefix("Bearer ")?.trim()?.takeIf { it.startsWith("ast_") } ?: throw UnauthorizedException()
        val id = db.sql("select id from public_tokens where token_hash = :hash and revoked_at is null").bind("hash", hash(token))
            .map { r -> r.req<UUID>("id") }.awaitOneOrNull() ?: throw UnauthorizedException()
        val minute = Instant.now(clock).epochSecond / 60
        val (window, count) = windows.compute(id) { _, old -> if (old == null || old.first != minute) minute to 1 else old.first to old.second + 1 }!!
        if (window == minute && count > 60) throw TooManyRequestsException()
        db.sql("update public_tokens set last_used = :now where id = :id").bind("id", id).bind("now", Instant.now(clock)).fetch().awaitRowsUpdated()
    }

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Token management, on the tailnet only. */
@RestController
@RequestMapping("/api/v1/public-tokens")
class PublicTokenController(private val tokens: PublicTokens) {
    @GetMapping
    suspend fun list(): List<TokenInfo> = tokens.list()

    /** Creates a token; the token itself is shown only in this response. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(@RequestBody req: CreateTokenRequest): NewToken = tokens.create(req.name)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun revoke(@PathVariable id: UUID) = tokens.revoke(id)
}

// ---- what the Outlook add-in may do ---------------------------------------------------

data class PublicProject(val id: UUID, val name: String)

data class MailTaskRequest(
    val title: String,
    val description: String = "",
    val due: DueDto? = null,
    val projectId: UUID? = null,
    /** Label names; missing ones are created. */
    val labels: List<String> = emptyList(),
    val priority: Priority = Priority.NONE,
)

data class Created(val id: String, val url: String)

/**
 * The internet-facing endpoints, for the Outlook add-in: list projects, make a task, read a mail
 * with the model, make an event. Every call needs a token.
 */
@RestController
@RequestMapping("/api/v1/public")
class PublicController(
    private val tokens: PublicTokens,
    private val tasks: TaskService,
    private val capture: CaptureService,
    private val calendar: CalendarService,
    private val props: AppProperties,
) {
    @GetMapping("/projects")
    suspend fun projects(@RequestHeader("Authorization", required = false) auth: String?): List<PublicProject> {
        tokens.authorize(auth)
        return tasks.projects().filter { it.state.wire in setOf("backlog", "planned", "in_progress") }.map { PublicProject(it.id, it.name) }
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun task(@RequestHeader("Authorization", required = false) auth: String?, @RequestBody req: MailTaskRequest): Created {
        tokens.authorize(auth)
        if (req.title.length > 500 || req.description.length > 20_000) throw ValidationException("Too long")
        val labels = tasks.labels()
        val labelIds = req.labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { name ->
            labels.find { it.name.equals(name, true) }?.id ?: tasks.createLabel(LabelRequest(name)).id
        }
        val t = tasks.create(CreateTaskRequest(title = req.title, description = req.description, due = req.due, projectId = req.projectId, labelIds = labelIds, priority = req.priority))
        return Created(t.id.toString(), "${props.publicUrl.trimEnd('/')}/#task/${t.id}")
    }

    @PostMapping("/analyze")
    suspend fun analyze(@RequestHeader("Authorization", required = false) auth: String?, @RequestBody req: AnalyzeRequest): AnalyzeResponse {
        tokens.authorize(auth)
        if (req.text.length > 50_000) throw ValidationException("Too long")
        // Text only from outside; attachments stay a tailnet thing.
        return capture.analyze(AnalyzeRequest(text = req.text))
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun event(@RequestHeader("Authorization", required = false) auth: String?, @RequestBody req: CreateEventRequest): Created {
        tokens.authorize(auth)
        if (req.repeat != null) throw ValidationException("Repeating events are made in the app")
        val e = calendar.create(req)
        return Created(e.id, "${props.publicUrl.trimEnd('/')}/#event/${java.net.URLEncoder.encode(e.id, Charsets.UTF_8)}")
    }
}

