package com.syeyoung.assistant.ical

import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.dav.IcsDocument
import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.tasks.TaskExport
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64

data class FeedInfo(
    /** Path of the feed, relative to the app's origin, e.g. /api/v1/ical/abc….ics. */
    val path: String,
    val createdAt: Instant,
)

/**
 * The iCal export: everything with a time, as a feed calendar apps subscribe to. The URL carries a
 * secret token; rotating it cuts off every existing subscription.
 */
@Service
class FeedService(
    private val db: DatabaseClient,
    private val ws: Workspace,
    private val tasks: TaskExport,
    private val clock: Clock,
    private val props: AppProperties,
) {
    @Transactional
    suspend fun current(): FeedInfo =
        active() ?: create()

    @Transactional
    suspend fun rotate(): FeedInfo {
        db.sql("update feed_tokens set revoked_at = :now where revoked_at is null")
            .bind("now", Instant.now(clock)).fetch().awaitRowsUpdated()
        return create()
    }

    /**
     * One iCalendar stream of everything: the calendar's events as stored (repeats stay RRULEs, time
     * zones included), and the tasks as VTODO with a "Due: …" event each ([TaskExport]).
     */
    suspend fun calendar(token: String): String {
        val valid = db.sql("select 1 from feed_tokens where token = :t and revoked_at is null")
            .bind("t", token).map { _ -> 1 }.awaitOneOrNull() != null
        if (!valid) throw NotFoundException("Feed", "")
        val s = ws.snapshot()
        val root = IcsDocument.Comp(
            "VCALENDAR",
            mutableListOf(
                IcsDocument.Prop("VERSION", emptyMap(), "2.0"),
                IcsDocument.Prop("PRODID", emptyMap(), "-//assistant//calendar feed//EN"),
                IcsDocument.Prop("CALSCALE", emptyMap(), "GREGORIAN"),
                IcsDocument.Prop("METHOD", emptyMap(), "PUBLISH"),
                IcsDocument.Prop("X-WR-CALNAME", emptyMap(), "Assistant"),
                IcsDocument.Prop("X-WR-TIMEZONE", emptyMap(), props.zone.id),
                IcsDocument.Prop("REFRESH-INTERVAL", mapOf("VALUE" to "DURATION"), "PT15M"),
                IcsDocument.Prop("X-PUBLISHED-TTL", emptyMap(), "PT15M"),
            ),
        )
        val timezones = mutableSetOf<String>()
        for (doc in s.docs.values) {
            for (c in doc.root.children) {
                when (c.name) {
                    "VTIMEZONE" -> if (timezones.add(c.value("TZID").orEmpty())) root.children += c
                    "VEVENT" -> root.children += c
                }
            }
        }
        root.children += tasks.components()
        return IcsDocument(root).serialize()
    }

    private suspend fun active(): FeedInfo? =
        db.sql("select token, created_at from feed_tokens where revoked_at is null order by created_at desc limit 1")
            .map { r -> FeedInfo(pathFor(r.req("token")), r.req("created_at")) }.awaitOneOrNull()

    private suspend fun create(): FeedInfo {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = Instant.now(clock)
        db.sql("insert into feed_tokens (token, created_at) values (:t, :now)").bind("t", token).bind("now", now)
            .fetch().awaitRowsUpdated()
        return FeedInfo(pathFor(token), now)
    }

    private fun pathFor(token: String) = "/api/v1/ical/$token.ics"
}

@RestController
@RequestMapping("/api/v1/ical")
class FeedController(private val service: FeedService) {

    /** The current feed URL path, created on first use. */
    @GetMapping("/feed")
    suspend fun feed(): FeedInfo = service.current()

    /** Replaces the feed URL; subscriptions to the old one stop working. */
    @PostMapping("/feed/rotate")
    suspend fun rotate(): FeedInfo = service.rotate()

    @GetMapping("/{token}.ics")
    suspend fun calendar(@PathVariable token: String): ResponseEntity<String> =
        ResponseEntity.ok()
            .contentType(MediaType("text", "calendar", Charsets.UTF_8))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(service.calendar(token))
}
