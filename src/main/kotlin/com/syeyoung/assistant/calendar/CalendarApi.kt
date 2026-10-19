package com.syeyoung.assistant.calendar

import com.fasterxml.jackson.annotation.JsonInclude
import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.dav.DavConflictException
import com.syeyoung.assistant.dav.Event
import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.ical.Freq
import com.syeyoung.assistant.ical.RepeatRule
import com.syeyoung.assistant.source.SourceRepository
import com.syeyoung.assistant.source.ical.IcalSource
import com.syeyoung.assistant.tasks.TaskRepository
import com.syeyoung.assistant.tasks.validColor
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

// ---- API shapes ------------------------------------------------------------------

data class CalendarResponse(val id: String, val name: String, val color: String?, val fixed: Boolean)

data class UpdateCalendarRequest(val name: String? = null, val color: String? = null)

/** How an event repeats: every [interval] days, weeks (on [weekdays]) or months, until a date or [count] times. */
data class RepeatDto(
    val freq: Freq = Freq.WEEKLY,
    val interval: Int = 1,
    /** Weekly only; empty means the start date's weekday. */
    val weekdays: List<DayOfWeek> = emptyList(),
    /** Last possible date, inclusive. */
    val until: LocalDate? = null,
    val count: Int? = null,
    /** Dates whose occurrence is skipped (e.g. a cancelled class). */
    val skipped: List<LocalDate> = emptyList(),
)

/** One occurrence of an event on the calendar. Timed (start/end) or all-day (startDate..endDate, inclusive). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EventOccurrence(
    val id: String,
    val title: String,
    val calendarId: String,
    val calendarName: String,
    val color: String?,
    val categories: List<String>,
    val repeats: Boolean,
    /** From a source (e.g. holidays): can't be changed here. */
    val fixed: Boolean,
    val projectId: UUID? = null,
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
)

/** An event as stored; for a repeating one, the first occurrence's time and the rule. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EventResponse(
    val id: String,
    val title: String,
    val notes: String,
    val calendarId: String,
    val calendarName: String,
    val color: String?,
    val categories: List<String>,
    val fixed: Boolean,
    val repeats: Boolean,
    /** Null for a repeating event whose rule is beyond what can be edited here. */
    val repeat: RepeatDto? = null,
    val url: String? = null,
    val projectId: UUID? = null,
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
)

data class CreateEventRequest(
    val title: String,
    val notes: String = "",
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    val startDate: LocalDate? = null,
    /** Inclusive. */
    val endDate: LocalDate? = null,
    val categories: List<String> = emptyList(),
    val repeat: RepeatDto? = null,
    val projectId: UUID? = null,
)

/** A repeating event of a project in one line's worth: e.g. Tue · Thu 12:30–13:45. */
data class RecurringSummary(
    val eventId: String,
    val title: String,
    val freq: Freq,
    val interval: Int,
    val weekdays: List<DayOfWeek>,
    /** Local times; null for all-day events. */
    val start: LocalTime? = null,
    val end: LocalTime? = null,
)

/** A skipped occurrence of a project's repeating event (e.g. no class that day). */
data class SkippedOccurrence(
    val eventId: String,
    val title: String,
    val date: LocalDate,
    /** When it would have started; null for all-day events. */
    val start: LocalTime? = null,
)

/**
 * What a project page shows of the calendar: its repeating events summed up rather than listed,
 * and the one-off events and skipped occurrences coming up.
 */
data class ProjectSchedule(val recurring: List<RecurringSummary>, val upcoming: List<EventOccurrence>, val skipped: List<SkippedOccurrence>)

// ---- service ----------------------------------------------------------------------

@Service
class CalendarService(
    private val ws: Workspace,
    private val sources: SourceRepository,
    private val tasks: TaskRepository,
    private val clock: Clock,
    private val mapper: ObjectMapper,
) {
    private val zone get() = ws.zone

    suspend fun calendars(): List<CalendarResponse> {
        val fixed = sources.sourceCalendarIds()
        return ws.snapshot().calendars.sortedBy { it.name.lowercase() }.map { CalendarResponse(it.id, it.name, it.color, it.id in fixed) }
    }

    suspend fun updateCalendar(id: String, req: UpdateCalendarRequest): CalendarResponse {
        val cal = ws.snapshot().calendars.find { it.id == id } ?: throw NotFoundException("Calendar", id)
        ws.updateCalendar(cal, req.name?.trim()?.ifEmpty { null }, req.color?.let(::validColor))
        return calendars().first { it.id == id }
    }

    /** Every occurrence touching days [from]..[to] (inclusive), repeats expanded. */
    suspend fun occurrences(from: LocalDate, to: LocalDate): List<EventOccurrence> {
        if (to < from) throw ValidationException("'to' must not be before 'from'")
        if (ChronoUnit.DAYS.between(from, to) > 400) throw ValidationException("Range is longer than 400 days")
        val s = ws.snapshot()
        val fixed = sources.sourceCalendarIds()
        return ws.occurrences(s, from, to).map { o ->
            val cal = s.calendars.find { it.id == o.event.calendarId }
            EventOccurrence(
                id = o.event.uid, title = o.event.title, calendarId = o.event.calendarId, calendarName = cal?.name.orEmpty(), color = cal?.color,
                categories = o.event.categories, repeats = o.event.repeats, fixed = o.event.calendarId in fixed,
                projectId = o.event.projectId?.let(::uuidOrNull),
                start = o.start?.atZone(zone)?.toOffsetDateTime(), end = o.end?.atZone(zone)?.toOffsetDateTime(),
                startDate = o.startDate, endDate = o.endDate,
            )
        }.sortedBy { it.start ?: it.startDate!!.atStartOfDay(zone).toOffsetDateTime() }
    }

    suspend fun event(id: String): EventResponse {
        val s = ws.snapshot()
        val e = s.events.find { it.uid == id } ?: throw NotFoundException("Event", id)
        val cal = s.calendars.find { it.id == e.calendarId }
        val rule = s.docs[e.href]?.main
        return EventResponse(
            id = e.uid, title = e.title, notes = e.notes, calendarId = e.calendarId, calendarName = cal?.name.orEmpty(), color = cal?.color,
            categories = e.categories, fixed = e.calendarId in sources.sourceCalendarIds(), repeats = e.repeats,
            repeat = rule?.value("RRULE")?.let { r -> toRepeat(r, ws.fieldsOf(e, rule).exdates) }, url = e.url,
            projectId = e.projectId?.let(::uuidOrNull),
            start = e.start?.atZone(zone)?.toOffsetDateTime(), end = e.end?.atZone(zone)?.toOffsetDateTime(),
            startDate = e.startDate, endDate = e.endDate,
        )
    }

    /** New events go in the assistant's own calendar. */
    suspend fun create(req: CreateEventRequest): EventResponse {
        val title = req.title.trim().ifEmpty { throw ValidationException("Title is required") }
        req.projectId?.let { checkProject(it) }
        var f = Workspace.EventFields(title, req.notes, categories = req.categories, projectId = req.projectId?.toString())
        f = withTime(f, req.start, req.end, req.startDate, req.endDate)
        req.repeat?.let { f = withRepeat(f, it) }
        return event(ws.createEvent(ws.assistantCalendar().id, f).uid)
    }

    /**
     * Merge patch: title, notes, categories, start+end or startDate+endDate (for a repeating event,
     * the first occurrence), repeat (null stops repeating), projectId (null unlinks).
     */
    suspend fun update(id: String, patch: JsonNode): EventResponse {
        if (!patch.isObject) throw ValidationException("Patch body must be a JSON object")
        val e = editable(id)
        fun date(name: String) = patch.get(name)?.takeUnless { it.isNull }?.let { mapper.treeToValue(it, LocalDate::class.java) }
        fun time(name: String) = patch.get(name)?.takeUnless { it.isNull }?.let { mapper.treeToValue(it, OffsetDateTime::class.java) }
        val known = setOf("title", "notes", "categories", "start", "end", "startDate", "endDate", "repeat", "projectId")
        patch.properties().map { it.key }.find { it !in known }?.let { throw ValidationException("Unknown field '$it'") }
        val project = patch.get("projectId")?.takeUnless { it.isNull }?.asString()
            ?.let { uuidOrNull(it) ?: throw ValidationException("Not an ID: '$it'") }?.also { checkProject(it) }
        try {
            ws.updateEvent(e) { current ->
                var f = current
                patch.get("title")?.let { f = f.copy(title = it.asString().trim().ifEmpty { throw ValidationException("Title is required") }) }
                patch.get("notes")?.let { f = f.copy(notes = it.takeUnless { n -> n.isNull }?.asString().orEmpty()) }
                patch.get("categories")?.let { n -> f = f.copy(categories = (0 until n.size()).map { n.get(it).asString() }) }
                if (patch.has("projectId")) f = f.copy(projectId = project?.toString())
                if (patch.has("start") || patch.has("startDate")) f = withTime(f, time("start"), time("end"), date("startDate"), date("endDate"))
                if (patch.has("repeat")) {
                    val r = patch.get("repeat").takeUnless { it.isNull }?.let { mapper.treeToValue(it, RepeatDto::class.java) }
                    f = if (r == null) f.copy(rrule = null, exdates = emptyList()) else withRepeat(f, r)
                }
                f
            }
        } catch (_: DavConflictException) {
            throw ConflictException("changed", "This event was changed elsewhere; reload and try again")
        }
        return event(id)
    }

    suspend fun delete(id: String) {
        val e = editable(id)
        ws.delete(e.href, e.etag)
    }

    /** The project's events for a page: repeating ones summed up, then the next [days] days' one-off events and skipped dates. */
    suspend fun projectSchedule(projectId: UUID, days: Long): ProjectSchedule {
        checkProject(projectId)
        val s = ws.snapshot()
        val today = LocalDate.now(clock.withZone(zone))
        val last = today.plusDays(days.coerceIn(1, 120) - 1)
        val mine = s.events.filter { it.projectId == projectId.toString() }
        val recurring = mutableListOf<RecurringSummary>()
        val skipped = mutableListOf<SkippedOccurrence>()
        for (e in mine.filter { it.repeats }) {
            val doc = s.docs[e.href]?.main ?: continue
            val rule = doc.value("RRULE")?.let { IcalSource.parseRule(it, zone) } ?: continue
            if (rule.until != null && rule.until < today) continue
            val first = e.startDate ?: e.start!!.atZone(zone).toLocalDate()
            recurring += RecurringSummary(
                e.uid, e.title, rule.freq, rule.interval,
                rule.weekdays.ifEmpty { if (rule.freq == Freq.WEEKLY) setOf(first.dayOfWeek) else emptySet() }.sorted(),
                e.start?.atZone(zone)?.toLocalTime(), e.end?.atZone(zone)?.toLocalTime(),
            )
            val time = e.start?.atZone(zone)?.toLocalTime()
            ws.fieldsOf(e, doc).exdates.filter { it in today..last }.forEach { skipped += SkippedOccurrence(e.uid, e.title, it, time) }
        }
        val upcoming = occurrences(today, last).filter { it.projectId == projectId && !it.repeats }
        return ProjectSchedule(
            recurring.sortedWith(compareBy({ it.weekdays.firstOrNull() }, { it.start })),
            upcoming,
            skipped.sortedWith(compareBy({ it.date }, { it.start })),
        )
    }

    private suspend fun checkProject(id: UUID) {
        tasks.project(id) ?: throw ValidationException("No project $id")
    }

    private fun uuidOrNull(s: String) = runCatching { UUID.fromString(s) }.getOrNull()

    private suspend fun editable(id: String): Event {
        val e = ws.findEvent(id) ?: throw NotFoundException("Event", id)
        if (e.calendarId in sources.sourceCalendarIds()) throw ConflictException("fixed", "This event comes from a source")
        return e
    }

    private fun withTime(f: Workspace.EventFields, start: OffsetDateTime?, end: OffsetDateTime?, startDate: LocalDate?, endDate: LocalDate?) = when {
        startDate != null -> {
            val last = endDate ?: startDate
            if (last < startDate) throw ValidationException("endDate is before startDate")
            f.copy(start = null, end = null, startDate = startDate, endDate = last)
        }
        start != null && end != null && end.isAfter(start) -> f.copy(start = start.toInstant(), end = end.toInstant(), startDate = null, endDate = null)
        else -> throw ValidationException("An event needs start and end (end after start), or startDate")
    }

    private fun withRepeat(f: Workspace.EventFields, r: RepeatDto): Workspace.EventFields {
        val first = f.startDate ?: f.start!!.atZone(zone).toLocalDate()
        RepeatRule(r.freq, r.interval, r.weekdays.toSet(), r.until, r.count).validate(first)
        val parts = mutableListOf("FREQ=${r.freq.name}")
        if (r.interval != 1) parts += "INTERVAL=${r.interval}"
        if (r.freq == Freq.WEEKLY && r.weekdays.isNotEmpty()) parts += "BYDAY=" + r.weekdays.sorted().joinToString(",") { it.name.take(2) }
        r.until?.let { u ->
            // All-day rules end on a date; timed ones at the end of that day, in UTC.
            parts += "UNTIL=" + if (f.startDate != null) DateTimeFormatter.BASIC_ISO_DATE.format(u)
            else UNTIL_UTC.format(u.plusDays(1).atStartOfDay(zone).minusSeconds(1).toInstant())
        }
        r.count?.let { parts += "COUNT=$it" }
        return f.copy(rrule = parts.joinToString(";"), exdates = r.skipped.distinct().sorted())
    }

    /** The rule as a [RepeatDto], or null when it uses parts this editor doesn't know (e.g. yearly). */
    private fun toRepeat(rrule: String, skipped: List<LocalDate>): RepeatDto? {
        if (Regex("FREQ=YEARLY", RegexOption.IGNORE_CASE).containsMatchIn(rrule)) return null
        val rule = IcalSource.parseRule(rrule, zone) ?: return null
        return RepeatDto(rule.freq, rule.interval, rule.weekdays.sorted(), rule.until, rule.count, skipped.sorted())
    }

    private companion object {
        val UNTIL_UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}

// ---- controller -------------------------------------------------------------------

@RestController
@RequestMapping("/api/v1")
class CalendarController(private val service: CalendarService) {

    @GetMapping("/calendars")
    suspend fun calendars(): List<CalendarResponse> = service.calendars()

    @PatchMapping("/calendars/{id}")
    suspend fun updateCalendar(@PathVariable id: String, @RequestBody req: UpdateCalendarRequest): CalendarResponse = service.updateCalendar(id, req)

    /** Event occurrences (repeats expanded) for days from..to, inclusive. */
    @GetMapping("/events")
    suspend fun occurrences(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<EventOccurrence> = service.occurrences(from, to)

    @GetMapping("/events/{id}")
    suspend fun getEvent(@PathVariable id: String): EventResponse = service.event(id)

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createEvent(@RequestBody req: CreateEventRequest): EventResponse = service.create(req)

    @PatchMapping("/events/{id}", consumes = ["application/json", "application/merge-patch+json"])
    suspend fun updateEvent(@PathVariable id: String, @RequestBody patch: JsonNode): EventResponse = service.update(id, patch)

    @DeleteMapping("/events/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteEvent(@PathVariable id: String) = service.delete(id)

    /** The project's calendar: repeating events summed up, one-off events and skipped dates in the next [days] days. */
    @GetMapping("/projects/{id}/schedule")
    suspend fun projectSchedule(@PathVariable id: UUID, @RequestParam(defaultValue = "28") days: Long): ProjectSchedule = service.projectSchedule(id, days)
}
