package com.syeyoung.assistant.ical

import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.dav.Workspace
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

    suspend fun calendar(token: String): String {
        val valid = db.sql("select 1 from feed_tokens where token = :t and revoked_at is null")
            .bind("t", token).map { _ -> 1 }.awaitOneOrNull() != null
        if (!valid) throw NotFoundException("Feed", "")
        val today = LocalDate.now(clock)
        val s = ws.snapshot()
        val events = ws.occurrences(s, today.minusDays(PAST_DAYS), today.plusDays(FUTURE_DAYS))
            // Events that came from another calendar feed are already in that calendar.
            .filter { it.event.sourceRef?.startsWith("ical:") != true }
            .map { o ->
                val day = o.startDate ?: o.start!!.atZone(props.zone).toLocalDate()
                IcalEvent(
                    uid = if (o.event.repeats) "${o.event.uid}-$day" else o.event.uid,
                    summary = o.event.title.ifBlank { "Untitled" }, description = o.event.notes, url = o.event.url,
                    start = o.start, end = o.end, startDate = o.startDate, endDate = o.endDate?.plusDays(1),
                )
            }
        // Open deadlines as short events (calendar subscriptions ignore VTODO).
        val deadlines = s.tasks.filter { !it.done && it.due != null }.map { t ->
            val base = IcalEvent(uid = "${t.uid}-due", summary = "Due: ${t.title}", description = t.notes, url = t.url)
            t.due!!.time?.let { time ->
                val at = t.due.date.atTime(time).atZone(props.zone).toInstant()
                base.copy(start = at, end = at.plus(DEADLINE_LENGTH))
            } ?: base.copy(startDate = t.due.date, endDate = t.due.date.plusDays(1))
        }
        return IcalWriter.write("Assistant", events + deadlines)
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
        const val PAST_DAYS = 120L
        const val FUTURE_DAYS = 400L
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
