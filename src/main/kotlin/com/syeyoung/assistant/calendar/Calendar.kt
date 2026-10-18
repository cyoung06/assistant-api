package com.syeyoung.assistant.calendar

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.item.Item
import com.syeyoung.assistant.item.ItemRepository
import com.syeyoung.assistant.item.ItemViews
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class EntryKind(@get:JsonValue val wire: String) {
    BLOCK("block"),
    ALL_DAY_BLOCK("allDayBlock"),
    DEADLINE("deadline"),
    ALL_DAY_DEADLINE("allDayDeadline"),
}

/** One thing to draw on the calendar. An item can produce two entries: its `at` block and its `by` deadline. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CalendarEntry(
    val itemId: UUID,
    val parentId: UUID?,
    val kind: EntryKind,
    val title: String,
    val color: String?,
    val done: Boolean,
    /** A child item with an `at`: a work session for its parent. */
    val session: Boolean,
    val overdue: Boolean,
    /** Blocks. */
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    /** All-day blocks; end date inclusive. */
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    /** Deadlines with a time. */
    val due: OffsetDateTime? = null,
    /** Date-only deadlines. */
    val date: LocalDate? = null,
)

@Service
class CalendarService(
    private val items: ItemRepository,
    private val clock: Clock,
    private val props: AppProperties,
) {
    /**
     * Entries for the days [from] (inclusive) to [to] (exclusive). When [tagIds] is not empty, only
     * items carrying one of them, directly or through an ancestor, are included.
     */
    suspend fun entries(from: LocalDate, to: LocalDate, tagIds: Set<UUID>, includeSessions: Boolean): List<CalendarEntry> {
        if (!to.isAfter(from)) throw ValidationException("'to' must be after 'from'")
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) throw ValidationException("Range is longer than $MAX_DAYS days")

        val zone = props.zone
        val candidates = items.inRange(from.atStartOfDay(zone).toInstant(), to.atStartOfDay(zone).toInstant(), from, to)
        val lineage = items.lineage(candidates.map { it.id })
        val now = Instant.now(clock)

        return candidates
            .filter { tagIds.isEmpty() || lineage[it.id]?.tagIds.orEmpty().any(tagIds::contains) }
            .flatMap { item ->
                val l = lineage[item.id]
                entriesFor(item, l?.displayTitle ?: item.fields.title, l?.color, now, from, to)
            }
            .filter { includeSessions || !it.session }
            .sortedBy { it.sortKey() }
    }

    private fun entriesFor(item: Item, title: String, color: String?, now: Instant, from: LocalDate, to: LocalDate): List<CalendarEntry> {
        val f = item.fields
        val zone = props.zone
        val base = CalendarEntry(
            itemId = item.id,
            parentId = f.parentId,
            kind = EntryKind.BLOCK,
            title = title,
            color = color,
            done = f.doneAt != null,
            session = f.parentId != null && f.hasAt,
            overdue = ItemViews.isOverdue(f, now, zone),
        )
        val result = mutableListOf<CalendarEntry>()
        if (f.atStart != null && f.atEnd != null) {
            result += base.copy(
                kind = EntryKind.BLOCK,
                start = f.atStart.atZone(zone).toOffsetDateTime(),
                end = f.atEnd.atZone(zone).toOffsetDateTime(),
            )
        } else if (f.atStartDate != null && f.atEndDate != null) {
            result += base.copy(kind = EntryKind.ALL_DAY_BLOCK, startDate = f.atStartDate, endDate = f.atEndDate)
        }
        val byDate = f.byDate
        // The query also returns items that only matched through their `at`; skip deadlines outside the range.
        if (byDate != null && byDate >= from && byDate < to) {
            // Deadlines are not sessions, even on child items: they always show.
            result += if (f.byTime != null) {
                base.copy(kind = EntryKind.DEADLINE, session = false, due = byDate.atTime(f.byTime).atZone(zone).toOffsetDateTime())
            } else {
                base.copy(kind = EntryKind.ALL_DAY_DEADLINE, session = false, date = byDate)
            }
        }
        return result
    }

    private fun CalendarEntry.sortKey(): OffsetDateTime =
        start ?: due ?: (startDate ?: date)!!.atTime(LocalTime.MIN).atZone(props.zone).toOffsetDateTime()

    private companion object {
        const val MAX_DAYS = 100L
    }
}

@RestController
@RequestMapping("/api/v1/calendar")
class CalendarController(private val service: CalendarService) {

    /** Calendar entries for days in [from, to). Example: `?from=2026-10-18&to=2026-10-25`. */
    @GetMapping
    suspend fun entries(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) tags: List<UUID>?,
        @RequestParam(defaultValue = "true") sessions: Boolean,
    ): List<CalendarEntry> = service.entries(from, to, tags.orEmpty().toSet(), sessions)
}
