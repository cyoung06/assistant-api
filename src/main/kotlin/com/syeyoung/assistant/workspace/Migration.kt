package com.syeyoung.assistant.workspace

import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.common.uuids
import com.syeyoung.assistant.dav.Due
import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.source.SourceApplier
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class MigrationReport(val calendars: Int, val tasks: Int, val events: Int, val series: Int, val skipped: Int)

/**
 * One-time move of the pre-CalDAV data (items, series, tags tables) into CalDAV. Idempotent: objects
 * whose UID already exists are skipped. Items imported from iCal feeds are left out; the feed
 * source recreates them. Occurrences of a repeating event that were moved on their own are not
 * carried over as overrides (there were none when this ran).
 */
@Service
class Migration(private val db: DatabaseClient, private val ws: Workspace) {

    private data class OldTag(val id: UUID, val name: String, val color: String)
    private data class OldItem(
        val id: UUID, val title: String, val notes: String, val byDate: LocalDate?, val byTime: LocalTime?,
        val atStart: Instant?, val atEnd: Instant?, val atStartDate: LocalDate?, val atEndDate: LocalDate?,
        val parentId: UUID?, val mainTagId: UUID?, val tagIds: List<UUID>, val doneAt: Instant?,
        val sourceType: String, val sourceRef: String?, val sourceUrl: String?, val seriesId: UUID?, val deleted: Boolean,
        val occurrenceDate: LocalDate?, val detached: Boolean,
    )

    suspend fun run(): MigrationReport {
        val tags = db.sql("select id, name, color from tags where deleted_at is null")
            .map { r -> OldTag(r.req("id"), r.req("name"), r.req("color")) }.flow().toList().associateBy { it.id }
        val items = db.sql("select * from items").map(::item).flow().toList()
        val live = items.filter { !it.deleted && it.sourceType != "ical" }

        // Main tags become calendars; every other tag is a category.
        val mainTagIds = (live.mapNotNull { it.mainTagId } + db.sql("select main_tag_id from series where deleted_at is null and main_tag_id is not null")
            .map { r -> r.req<UUID>("main_tag_id") }.flow().toList()).toSet()
        var calendarsCreated = 0
        val calendarOf = mutableMapOf<UUID, String>()
        for (tagId in mainTagIds) {
            val tag = tags[tagId] ?: continue
            val existing = ws.snapshot().calendars.find { it.name.equals(tag.name, true) }
            calendarOf[tagId] = existing?.id ?: ws.createCalendar(tag.name, tag.color).also { calendarsCreated++ }.id
        }
        val inbox = ws.snapshot().calendars.find { it.name == SourceApplier.INBOX }?.id
            ?: ws.createCalendar(SourceApplier.INBOX, "#6B7280", slug = "inbox").also { calendarsCreated++ }.id
        // Sources keep writing into the same calendars.
        for ((type, key, tagId) in db.sql("select source_type, external_key, tag_id from source_tags").map { r -> Triple(r.req<String>("source_type"), r.req<String>("external_key"), r.req<UUID>("tag_id")) }.flow().toList()) {
            val cal = calendarOf[tagId] ?: continue
            db.sql("insert into source_calendars (source_type, external_key, calendar_id) values (:t, :k, :c) on conflict do nothing")
                .bind("t", type).bind("k", key).bind("c", cal).fetch().awaitRowsUpdated()
        }

        val existingUids = ws.snapshot().let { s -> (s.tasks.map { it.uid } + s.events.map { it.uid }).toSet() }
        // A source may already have written its own copy (e.g. a sync ran before this migration).
        val existingRefs = ws.snapshot().tasks.mapNotNull { it.sourceRef }.toSet()
        val uidOf = { id: UUID -> "$id@assistant" }
        val byId = live.associateBy { it.id }
        fun calendarFor(i: OldItem): String =
            i.mainTagId?.let(calendarOf::get) ?: i.parentId?.let(byId::get)?.let { calendarFor(it) } ?: inbox
        fun categoriesFor(tagIds: List<UUID>, mainTagId: UUID?) = tagIds.filter { it != mainTagId }.mapNotNull { tags[it]?.name }

        var tasks = 0; var events = 0; var skipped = 0
        // Parents before children, so RELATED-TO always points at something that exists.
        val ordered = live.filter { it.seriesId == null }.sortedBy { depth(it, byId) }
        for (i in ordered) {
            val uid = uidOf(i.id)
            val isTask = i.byDate != null || (i.atStart == null && i.atStartDate == null)
            val cats = categoriesFor(i.tagIds, i.mainTagId)
            val sourceRef = if (i.sourceType == "plms" && i.sourceRef != null) "plms:${i.sourceRef}" else null
            if (isTask) {
                if (uid in existingUids || sourceRef in existingRefs) { skipped++ } else {
                    ws.createTask(
                        calendarFor(i),
                        Workspace.TaskFields(i.title, i.notes, i.byDate?.let { Due(it, i.byTime) }, i.doneAt != null, cats, i.parentId?.let(uidOf), i.sourceUrl, sourceRef),
                        uid,
                    )
                    sourceRef?.let { ref ->
                        db.sql("insert into source_refs (source_type, ref, uid) values ('plms', :ref, :uid) on conflict do nothing")
                            .bind("ref", ref).bind("uid", uid).fetch().awaitRowsUpdated()
                    }
                    tasks++
                }
            }
            // A scheduled time becomes an event; on a task it's a work session for that task.
            if (i.atStart != null || i.atStartDate != null) {
                val eventUid = if (isTask) "${i.id}-at@assistant" else uid
                if (eventUid in existingUids) { skipped++; continue }
                val related = if (isTask) uid else i.parentId?.let(uidOf)
                ws.createEvent(
                    calendarFor(i),
                    Workspace.EventFields(
                        title = i.title, notes = if (isTask) "" else i.notes, start = i.atStart, end = i.atEnd,
                        startDate = i.atStartDate, endDate = i.atEndDate, categories = cats, relatedUid = related,
                    ),
                    eventUid,
                )
                events++
            }
        }

        // Repeating events: one VEVENT with RRULE; occurrences deleted on their own become EXDATEs.
        var series = 0
        for (r in db.sql("select * from series where deleted_at is null").map(::seriesRow).flow().toList()) {
            val uid = "${r.id}@assistant"
            if (uid in existingUids) { skipped++; continue }
            val occurrences = items.filter { it.seriesId == r.id }
            val exdates = occurrences.filter { it.deleted && it.detached }.mapNotNull { it.occurrenceDate }
            val start = r.startDate.atTime(r.startTime ?: LocalTime.MIDNIGHT).atZone(ws.zone).toInstant()
            ws.createEvent(
                calendarOf[r.mainTagId] ?: inbox,
                Workspace.EventFields(
                    title = r.title, notes = r.notes,
                    start = if (r.allDay) null else start,
                    end = if (r.allDay) null else start.plusSeconds((r.durationMinutes ?: 60) * 60L),
                    startDate = if (r.allDay) r.startDate else null,
                    endDate = if (r.allDay) r.startDate.plusDays((r.spanDays ?: 1) - 1L) else null,
                    categories = categoriesFor(r.tagIds, r.mainTagId),
                    rrule = rrule(r),
                    exdates = exdates,
                ),
                uid,
            )
            series++
        }
        return MigrationReport(calendarsCreated, tasks, events, series, skipped)
    }

    private data class SeriesRow(
        val id: UUID, val title: String, val notes: String, val mainTagId: UUID?, val tagIds: List<UUID>, val allDay: Boolean,
        val startTime: LocalTime?, val durationMinutes: Int?, val spanDays: Int?, val freq: String, val interval: Int,
        val weekdays: List<Short>, val startDate: LocalDate, val untilDate: LocalDate?, val count: Int?,
    )

    private fun seriesRow(r: Readable) = SeriesRow(
        r.req("id"), r.req("title"), r.req("notes"), r.opt("main_tag_id"), r.uuids("tag_ids"), r.req("all_day"),
        r.opt("start_time"), r.opt("duration_minutes"), r.opt("span_days"), r.req("freq"), r.req("interval"),
        (r.get("weekdays", Array<Short>::class.java) ?: emptyArray()).toList(), r.req("start_date"), r.opt("until_date"), r.opt("count"),
    )

    private fun rrule(r: SeriesRow): String {
        val parts = mutableListOf("FREQ=${r.freq.uppercase()}")
        if (r.interval > 1) parts += "INTERVAL=${r.interval}"
        if (r.weekdays.isNotEmpty()) parts += "BYDAY=" + r.weekdays.sorted().joinToString(",") { DAY_CODES[it - 1] }
        r.untilDate?.let { parts += "UNTIL=" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(it.plusDays(1).atStartOfDay().minusSeconds(1).atZone(java.time.ZoneId.of("Asia/Seoul")).withZoneSameInstant(ZoneOffset.UTC)) }
        r.count?.let { parts += "COUNT=$it" }
        return parts.joinToString(";")
    }

    private fun depth(i: OldItem, byId: Map<UUID, OldItem>): Int {
        var d = 0
        var p = i.parentId
        while (p != null && d < 50) { d++; p = byId[p]?.parentId }
        return d
    }

    private fun item(r: Readable) = OldItem(
        id = r.req("id"), title = r.req("title"), notes = r.req("notes"), byDate = r.opt("by_date"), byTime = r.opt("by_time"),
        atStart = r.opt("at_start"), atEnd = r.opt("at_end"), atStartDate = r.opt("at_start_date"), atEndDate = r.opt("at_end_date"),
        parentId = r.opt("parent_id"), mainTagId = r.opt("main_tag_id"), tagIds = r.uuids("tag_ids"), doneAt = r.opt("done_at"),
        sourceType = r.req("source_type"), sourceRef = r.opt("source_ref"), sourceUrl = r.opt("source_url"), seriesId = r.opt("series_id"),
        deleted = r.opt<Instant>("deleted_at") != null, occurrenceDate = r.opt("occurrence_date"), detached = r.req("detached"),
    )

    private companion object {
        val DAY_CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    }
}

@RestController
@RequestMapping("/api/v1/admin")
class MigrationController(private val migration: Migration) {
    /** Moves the pre-CalDAV data into CalDAV. Safe to run again: existing objects are skipped. */
    @PostMapping("/migrate-to-dav")
    suspend fun migrate(): MigrationReport = migration.run()
}
