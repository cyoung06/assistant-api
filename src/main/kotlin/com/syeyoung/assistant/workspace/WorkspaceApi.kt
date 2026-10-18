package com.syeyoung.assistant.workspace

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.dav.Cal
import com.syeyoung.assistant.dav.DavConflictException
import com.syeyoung.assistant.dav.Due
import com.syeyoung.assistant.dav.Snapshot
import com.syeyoung.assistant.dav.Task
import com.syeyoung.assistant.dav.Workspace
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

// ---- API shapes ------------------------------------------------------------------

data class CalendarResponse(val id: String, val name: String, val color: String?, val openTasks: Int)

data class DueDto(val date: LocalDate, val time: LocalTime? = null)

data class TaskResponse(
    val id: String,
    val calendarId: String,
    val calendarName: String,
    val color: String?,
    val title: String,
    val notes: String,
    val due: DueDto?,
    val done: Boolean,
    val doneAt: Instant?,
    val categories: List<String>,
    val parentId: String?,
    val url: String?,
    /** Set when an exporter (e.g. PLMS) owns the title and deadline. */
    val source: String?,
    /** In a source's calendar: title and deadline belong to the source and can't be changed here. */
    val fixed: Boolean,
    val overdue: Boolean,
    val subtasks: Int,
    val subtasksDone: Int,
    /** Work sessions (events related to this task) that are over, and those still ahead. */
    val minutesSpent: Long,
    val minutesPlanned: Long,
    val etag: String?,
)

enum class EntryKind(@get:JsonValue val wire: String) { BLOCK("block"), ALL_DAY_BLOCK("allDayBlock"), DEADLINE("deadline"), ALL_DAY_DEADLINE("allDayDeadline") }

/** Something to draw on a day: an event occurrence or a task's deadline. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgendaEntry(
    /** The task or event UID. */
    val id: String,
    val kind: EntryKind,
    val title: String,
    val color: String?,
    val calendarName: String,
    val categories: List<String>,
    val done: Boolean,
    val overdue: Boolean,
    val repeats: Boolean,
    /** In a source's calendar (or repeating): can't be moved here. */
    val fixed: Boolean,
    /** A work session for this task. */
    val taskId: String? = null,
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val due: OffsetDateTime? = null,
    val date: LocalDate? = null,
)

data class UpdateCalendarRequest(val name: String? = null, val color: String? = null)

data class CreateTaskRequest(
    val title: String,
    val notes: String = "",
    val due: DueDto? = null,
    val categories: List<String> = emptyList(),
    val parentId: String? = null,
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
    /** Makes this a work session for that task. */
    val taskId: String? = null,
)

/** Moves an event: a timed one by start/end, an all-day one by startDate/endDate. */
data class MoveEventRequest(
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
)

// ---- service ----------------------------------------------------------------------

@Service
class WorkspaceService(
    private val ws: Workspace,
    private val sources: com.syeyoung.assistant.source.SourceRepository,
    private val clock: Clock,
    private val mapper: ObjectMapper,
) {
    private val zone get() = ws.zone

    suspend fun calendars(): List<CalendarResponse> {
        val s = ws.snapshot()
        return s.calendars.sortedBy { it.name.lowercase() }.map { c -> CalendarResponse(c.id, c.name, c.color, s.tasks.count { it.calendarId == c.id && !it.done }) }
    }

    suspend fun updateCalendar(id: String, req: UpdateCalendarRequest): CalendarResponse {
        val cal = calendar(id)
        ws.updateCalendar(cal, req.name?.trim()?.ifEmpty { null }, req.color?.let(::validColor))
        return calendars().first { it.id == id }
    }

    suspend fun tasks(status: String, calendarId: String?, category: String?, hide: Set<String>, parentId: String?, q: String?): List<TaskResponse> {
        val s = ws.snapshot()
        val fixed = sources.sourceCalendarIds()
        val hidden = hide.map { it.lowercase() }.toSet()
        return s.tasks.asSequence()
            .filter { status == "all" || (status == "done") == it.done }
            .filter { calendarId == null || it.calendarId == calendarId }
            .filter { category == null || it.categories.any { c -> c.equals(category, true) } }
            .filter { parentId == null || it.parentUid == parentId }
            .filter { t -> hidden.isEmpty() || (calendarName(s, t.calendarId).lowercase() !in hidden && t.categories.none { it.lowercase() in hidden }) }
            .filter { t -> q.isNullOrBlank() || t.title.contains(q, true) || t.notes.contains(q, true) }
            .map { render(s, it, fixed) }
            .toList()
    }

    suspend fun task(id: String): TaskResponse =
        ws.snapshot().let { s -> render(s, s.tasks.find { it.uid == id } ?: throw NotFoundException("Task", id), sources.sourceCalendarIds()) }

    suspend fun createTask(req: CreateTaskRequest): TaskResponse {
        val title = req.title.trim().ifEmpty { throw ValidationException("Title is required") }
        val s = ws.snapshot()
        val parent = req.parentId?.let { p -> s.tasks.find { it.uid == p } ?: throw ValidationException("No task $p") }
        val t = ws.createTask(ws.assistantCalendar().id, Workspace.TaskFields(title, req.notes, req.due?.let { Due(it.date, it.time) }, categories = req.categories, parentUid = req.parentId))
        return task(t.uid)
    }

    /**
     * Merge patch of a task. Supported: title, notes, due, done, categories, parentId. Title and due of
     * a task in a source's calendar belong to the source. Completing a task completes its sub-tasks.
     * [etag] guards against overwriting another client's change.
     */
    suspend fun updateTask(id: String, patch: JsonNode, etag: String?): TaskResponse {
        if (!patch.isObject) throw ValidationException("Patch body must be a JSON object")
        val s = ws.snapshot()
        val current = s.tasks.find { it.uid == id } ?: throw NotFoundException("Task", id)
        if (etag != null && current.etag != null && etag != current.etag) throw ConflictException("changed", "This task was changed elsewhere; reload and try again")
        var f = ws.fieldsOf(current)
        val isFixed = current.calendarId in sources.sourceCalendarIds()
        for ((name, value) in patch.properties()) {
            if (isFixed && name in setOf("title", "due")) throw ConflictException("fixed", "This task comes from ${current.sourceRef?.substringBefore(':')?.uppercase() ?: "a source"}; its $name is set there")
            val v = value.takeUnless { it.isNull }
            f = when (name) {
                "title" -> f.copy(title = v?.asString()?.trim()?.ifEmpty { null } ?: throw ValidationException("Title is required"))
                "notes" -> f.copy(notes = v?.asString().orEmpty())
                "due" -> f.copy(due = v?.let { mapper.treeToValue(it, DueDto::class.java) }?.let { Due(it.date, it.time) })
                "done" -> f.copy(done = v?.asBoolean() ?: false)
                "categories" -> f.copy(categories = v?.let { n -> (0 until n.size()).map { n.get(it).asString() } }.orEmpty())
                "parentId" -> f.copy(parentUid = v?.asString()?.also { p -> if (p == id || s.tasks.none { it.uid == p }) throw ValidationException("Invalid parent") })
                else -> throw ValidationException("Unknown or read-only field '$name'")
            }
        }
        try {
            ws.updateTask(current) { f }
            if (!current.done && f.done) {
                for (child in descendants(s, id)) if (!child.done) ws.findTask(child.uid)?.let { c -> ws.updateTask(c) { it.copy(done = true) } }
            }
        } catch (e: DavConflictException) {
            throw ConflictException("changed", "This task was changed elsewhere; reload and try again")
        }
        return task(id)
    }

    /** Deletes a task with its sub-tasks and work sessions. */
    suspend fun deleteTask(id: String) {
        val s = ws.snapshot()
        val t = s.tasks.find { it.uid == id } ?: throw NotFoundException("Task", id)
        val all = listOf(t) + descendants(s, id)
        val uids = all.map { it.uid }.toSet()
        all.forEach { ws.delete(it.href, it.etag) }
        s.events.filter { it.relatedUid in uids }.forEach { ws.delete(it.href, it.etag) }
    }

    suspend fun createEvent(req: CreateEventRequest): AgendaEntry {
        val title = req.title.trim().ifEmpty { throw ValidationException("Title is required") }
        val s = ws.snapshot()
        req.taskId?.let { id -> s.tasks.find { it.uid == id } ?: throw ValidationException("No task $id") }
        val calendarId = ws.assistantCalendar().id
        val fields = when {
            req.startDate != null -> Workspace.EventFields(title, req.notes, startDate = req.startDate, endDate = req.endDate ?: req.startDate, categories = req.categories, relatedUid = req.taskId)
            req.start != null && req.end != null && req.end.isAfter(req.start) ->
                Workspace.EventFields(title, req.notes, start = req.start.toInstant(), end = req.end.toInstant(), categories = req.categories, relatedUid = req.taskId)
            else -> throw ValidationException("An event needs start and end (end after start), or startDate")
        }
        val e = ws.createEvent(calendarId, fields)
        val date = e.startDate ?: e.start!!.atZone(zone).toLocalDate()
        return agenda(date, date, emptySet()).first { it.id == e.uid }
    }

    suspend fun deleteEvent(id: String) {
        val e = ws.findEvent(id) ?: throw NotFoundException("Event", id)
        if (e.calendarId in sources.sourceCalendarIds()) throw ConflictException("fixed", "This event comes from a source")
        ws.delete(e.href, e.etag)
    }

    /** Moves or resizes a one-off event in the assistant's calendar. */
    suspend fun moveEvent(id: String, req: MoveEventRequest): AgendaEntry {
        val e = ws.findEvent(id) ?: throw NotFoundException("Event", id)
        if (e.calendarId in sources.sourceCalendarIds()) throw ConflictException("fixed", "This event comes from a source")
        if (e.repeats) throw ConflictException("repeats", "Repeating events are changed in a calendar app")
        ws.updateEvent(e) { f ->
            when {
                req.startDate != null -> f.copy(start = null, end = null, startDate = req.startDate, endDate = req.endDate ?: req.startDate)
                req.start != null && req.end != null && req.end.isAfter(req.start) -> f.copy(start = req.start.toInstant(), end = req.end.toInstant(), startDate = null, endDate = null)
                else -> throw ValidationException("Give start and end (end after start), or startDate")
            }
        }
        val moved = ws.findEvent(id)!!
        val date = moved.startDate ?: moved.start!!.atZone(zone).toLocalDate()
        return agenda(date, date, emptySet()).first { it.id == id }
    }

    /** Event occurrences and task deadlines for days [from]..[to] (inclusive). */
    suspend fun agenda(from: LocalDate, to: LocalDate, hide: Set<String>): List<AgendaEntry> {
        if (to < from) throw ValidationException("'to' must not be before 'from'")
        if (ChronoUnit.DAYS.between(from, to) > 400) throw ValidationException("Range is longer than 400 days")
        val s = ws.snapshot()
        val fixed = sources.sourceCalendarIds()
        val hidden = hide.map { it.lowercase() }.toSet()
        fun visible(calendarId: String, categories: List<String>) =
            hidden.isEmpty() || (calendarName(s, calendarId).lowercase() !in hidden && categories.none { it.lowercase() in hidden })
        val now = Instant.now(clock)
        val events = ws.occurrences(s, from, to).filter { visible(it.event.calendarId, it.event.categories) }.map { o ->
            val cal = s.calendars.find { it.id == o.event.calendarId }
            AgendaEntry(
                id = o.event.uid,
                kind = if (o.startDate != null) EntryKind.ALL_DAY_BLOCK else EntryKind.BLOCK,
                title = o.event.title.ifBlank { o.event.relatedUid?.let { r -> s.tasks.find { it.uid == r }?.title }.orEmpty() },
                color = cal?.color, calendarName = cal?.name.orEmpty(), categories = o.event.categories,
                done = false, overdue = false, repeats = o.event.repeats, fixed = o.event.calendarId in fixed || o.event.repeats,
                taskId = o.event.relatedUid,
                start = o.start?.atZone(zone)?.toOffsetDateTime(), end = o.end?.atZone(zone)?.toOffsetDateTime(),
                startDate = o.startDate, endDate = o.endDate,
            )
        }
        val deadlines = s.tasks.filter { t -> t.due != null && t.due.date >= from && t.due.date <= to && visible(t.calendarId, t.categories) }.map { t ->
            val cal = s.calendars.find { it.id == t.calendarId }
            val base = AgendaEntry(
                id = t.uid, kind = EntryKind.DEADLINE, title = t.title, color = cal?.color, calendarName = cal?.name.orEmpty(),
                categories = t.categories, done = t.done, overdue = isOverdue(t, now), repeats = false, fixed = t.calendarId in fixed,
            )
            t.due!!.time?.let { base.copy(due = t.due.date.atTime(it).atZone(zone).toOffsetDateTime()) }
                ?: base.copy(kind = EntryKind.ALL_DAY_DEADLINE, date = t.due.date)
        }
        return (events + deadlines).sortedBy { it.start ?: it.due ?: (it.startDate ?: it.date)!!.atStartOfDay(zone).toOffsetDateTime() }
    }

    private fun render(s: Snapshot, t: Task, fixedCalendars: Set<String>): TaskResponse {
        val cal = s.calendars.find { it.id == t.calendarId }
        val subs = descendants(s, t.uid)
        val uids = (subs.map { it.uid } + t.uid).toSet()
        val now = Instant.now(clock)
        val sessions = s.events.filter { it.relatedUid in uids && it.start != null && it.end != null }
        val spent = sessions.filter { !it.end!!.isAfter(now) }.sumOf { Duration.between(it.start, it.end).toMinutes() }
        val planned = sessions.filter { it.end!!.isAfter(now) }.sumOf { Duration.between(it.start, it.end).toMinutes() }
        return TaskResponse(
            id = t.uid, calendarId = t.calendarId, calendarName = cal?.name.orEmpty(), color = cal?.color,
            title = t.title, notes = t.notes, due = t.due?.let { DueDto(it.date, it.time) }, done = t.done, doneAt = t.doneAt,
            categories = t.categories, parentId = t.parentUid, url = t.url, source = t.sourceRef?.substringBefore(':'), fixed = t.calendarId in fixedCalendars,
            overdue = isOverdue(t, now), subtasks = subs.size, subtasksDone = subs.count { it.done },
            minutesSpent = spent, minutesPlanned = planned, etag = t.etag,
        )
    }

    private fun isOverdue(t: Task, now: Instant): Boolean {
        if (t.done) return false
        val d = t.due ?: return false
        val deadline = d.time?.let { d.date.atTime(it).atZone(zone).toInstant() } ?: d.date.plusDays(1).atStartOfDay(zone).toInstant()
        return !now.isBefore(deadline)
    }

    private fun descendants(s: Snapshot, uid: String): List<Task> {
        val out = mutableListOf<Task>()
        val queue = ArrayDeque(listOf(uid))
        val seen = mutableSetOf(uid)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            for (c in s.tasks.filter { it.parentUid == id }) if (seen.add(c.uid)) { out += c; queue += c.uid }
        }
        return out
    }

    private fun calendarName(s: Snapshot, id: String) = s.calendars.find { it.id == id }?.name.orEmpty()

    private suspend fun calendar(id: String): Cal = ws.snapshot().calendars.find { it.id == id } ?: throw NotFoundException("Calendar", id)

    private fun validColor(c: String): String {
        if (!Regex("^#[0-9A-Fa-f]{6}$").matches(c)) throw ValidationException("Color must look like #3B82F6")
        return c.uppercase()
    }
}

// ---- controllers ------------------------------------------------------------------

@RestController
@RequestMapping("/api/v1")
class WorkspaceController(private val service: WorkspaceService) {

    @GetMapping("/calendars")
    suspend fun calendars(): List<CalendarResponse> = service.calendars()

    @PatchMapping("/calendars/{id}")
    suspend fun updateCalendar(@PathVariable id: String, @RequestBody req: UpdateCalendarRequest): CalendarResponse = service.updateCalendar(id, req)

    /** Tasks. `hide` leaves out tasks whose calendar or a category has one of these names. */
    @GetMapping("/tasks")
    suspend fun tasks(
        @RequestParam(defaultValue = "open") status: String,
        @RequestParam(required = false) calendarId: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) hide: List<String>?,
        @RequestParam(required = false) parentId: String?,
        @RequestParam(required = false) q: String?,
    ): List<TaskResponse> {
        if (status !in setOf("open", "done", "all")) throw ValidationException("status must be open, done or all")
        return service.tasks(status, calendarId, category, hide.orEmpty().toSet(), parentId, q)
    }

    @GetMapping("/tasks/{id}")
    suspend fun task(@PathVariable id: String): TaskResponse = service.task(id)

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createTask(@RequestBody req: CreateTaskRequest): TaskResponse = service.createTask(req)

    @PatchMapping("/tasks/{id}", consumes = ["application/json", "application/merge-patch+json"])
    suspend fun updateTask(
        @PathVariable id: String,
        @RequestBody patch: JsonNode,
        @org.springframework.web.bind.annotation.RequestHeader("If-Match", required = false) ifMatch: String?,
    ): TaskResponse = service.updateTask(id, patch, ifMatch)

    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteTask(@PathVariable id: String) = service.deleteTask(id)

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createEvent(@RequestBody req: CreateEventRequest): AgendaEntry = service.createEvent(req)

    @PatchMapping("/events/{id}")
    suspend fun moveEvent(@PathVariable id: String, @RequestBody req: MoveEventRequest): AgendaEntry = service.moveEvent(id, req)

    @DeleteMapping("/events/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteEvent(@PathVariable id: String) = service.deleteEvent(id)

    /** Event occurrences (repeats expanded) and task deadlines for days from..to, inclusive. */
    @GetMapping("/agenda")
    suspend fun agenda(
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) hide: List<String>?,
    ): List<AgendaEntry> = service.agenda(from, to, hide.orEmpty().toSet())
}
