package com.syeyoung.assistant.ical

import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.dav.IcsDocument
import com.syeyoung.assistant.dav.Workspace
import java.time.format.DateTimeFormatter
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
     * One iCalendar stream of everything, from every source: the stored events and to-dos as they
     * are (repeats stay RRULEs, time zones included), plus a short "Due: …" event per open to-do,
     * since calendar apps don't show VTODO in subscriptions.
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
                    "VEVENT", "VTODO" -> root.children += c
                }
            }
        }
        for (t in s.tasks.filter { !it.done && it.due != null }) {
            val e = IcsDocument.Comp("VEVENT")
            e.set("UID", "${t.uid}-due")
            e.set("DTSTAMP", Workspace.utc(Instant.now(clock)))
            val due = t.due!!
            if (due.time == null) {
                e.set("DTSTART", due.date.format(DateTimeFormatter.BASIC_ISO_DATE), mapOf("VALUE" to "DATE"))
                e.set("DTEND", due.date.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE), mapOf("VALUE" to "DATE"))
            } else {
                val at = due.date.atTime(due.time).atZone(props.zone).toInstant()
                e.set("DTSTART", Workspace.utc(at))
                e.set("DTEND", Workspace.utc(at.plus(DEADLINE_LENGTH)))
            }
            e.setText("SUMMARY", "Due: ${t.title}")
            t.url?.let { e.set("URL", it) }
            e.set("RELATED-TO", t.uid)
            root.children += e
        }
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

    private companion object {
        val DEADLINE_LENGTH: Duration = Duration.ofMinutes(15)
    }
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
