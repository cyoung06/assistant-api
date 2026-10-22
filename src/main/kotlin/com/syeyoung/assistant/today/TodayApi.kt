package com.syeyoung.assistant.today

import com.syeyoung.assistant.calendar.CalendarService
import com.syeyoung.assistant.calendar.EventOccurrence
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.drive.DriveEntry
import com.syeyoung.assistant.drive.DriveService
import com.syeyoung.assistant.drive.WebDav
import com.syeyoung.assistant.tasks.TaskFilter
import com.syeyoung.assistant.tasks.TaskResponse
import com.syeyoung.assistant.tasks.TaskService
import com.syeyoung.assistant.tasks.TaskStatus
import com.syeyoung.assistant.triage.TriageService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import com.syeyoung.assistant.core.NotebookPage
import com.syeyoung.assistant.sync.NotebookPdf
import com.syeyoung.assistant.tasks.ProjectResponse
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import reactor.core.publisher.Flux
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** The next class: the lecture (an event of a project), its project, and what's newest in its files. */
data class NextClass(
    val event: EventOccurrence,
    val projectName: String?,
    /** The project's folder in the drive. */
    val folder: String?,
    /** Its newest files (the course's from PLMS, and the project's own), latest first. */
    val files: List<DriveEntry>,
)

/** One screen of the day: what's next, what's on, what's due, and how much waits in Triage. */
data class Today(
    val date: LocalDate,
    val next: NextClass?,
    /** Today's events, all-day ones first, then by time. */
    val events: List<EventOccurrence>,
    /** Open tasks overdue or due by [date] + [DUE_DAYS] days, soonest first. */
    val due: List<TaskResponse>,
    /** Suggestions waiting for a decision, plus inputs not read yet (as the apps count them). */
    val triage: Int,
)

/** How far ahead "due soon" looks, and how many of a class's files are shown. */
private const val DUE_DAYS = 2L
private const val FILES = 4

@Service
class TodayService(
    private val calendar: CalendarService,
    private val tasks: TaskService,
    private val triage: TriageService,
    private val drive: DriveService,
    private val dav: WebDav,
    private val props: AppProperties,
    private val clock: Clock,
) {
    suspend fun today(): Today = coroutineScope {
        val now = OffsetDateTime.now(clock.withZone(props.zone))
        val date = now.toLocalDate()
        val week = async { calendar.occurrences(date, date.plusDays(6)) }
        val due = async {
            tasks.tasks(TaskFilter(statuses = TaskStatus.entries.filter { !it.closed }.toSet(), dueTo = date.plusDays(DUE_DAYS)))
                .sortedWith(compareBy({ it.due?.date }, { it.due?.time }, { it.priority }))
        }
        val waiting = async { triage.list().sumOf { i -> if (i.status == "pending") 1 else i.items.count { it.status == "pending" } } }
        val projects = async { tasks.projects() }
        val events = week.await()
        val todays = events.filter { e -> e.start?.toLocalDate()?.let { it <= date } ?: (e.startDate!! <= date) }
            .filter { e -> e.end?.let { it.toLocalDate() >= date } ?: (e.endDate!! >= date) }
            .sortedWith(compareBy({ it.start != null }, { it.start }))
        val all = projects.await()
        // The next class: a project's event going on now or the soonest to start, this week.
        val lecture = events.filter { it.start != null && it.end!! > now }.sortedBy { it.start!! }.firstNotNullOfOrNull { e -> projectOf(e, all)?.let { e to it } }
        Today(date, lecture?.let { (e, p) -> nextClass(e, p.id, p.name) }, todays, due.await(), waiting.await())
    }

    /**
     * An event's project: the one it's linked to, or (a lecture added by hand, titled as its course)
     * the project its title names.
     */
    private fun projectOf(e: EventOccurrence, all: List<ProjectResponse>) = e.projectId?.let { id -> all.find { it.id == id } }
        ?: all.filter { p -> e.title == p.name || e.title.startsWith(p.name + " ") }.maxByOrNull { it.name.length }

    /** The project whose class is on now (from a quarter of an hour before it starts to its end), if any. */
    suspend fun currentProject(): ProjectResponse? {
        val now = OffsetDateTime.now(clock.withZone(props.zone))
        val all = tasks.projects()
        return calendar.occurrences(now.toLocalDate(), now.toLocalDate())
            .filter { it.start != null && it.start!!.minusMinutes(15) <= now && it.end!! > now }
            .sortedBy { it.start!! }
            .firstNotNullOfOrNull { projectOf(it, all) }
    }

    /**
     * A new note ([notebook]: a notebook page) for what's on now: in the current class's project's
     * Notes folder (No project's, between classes), named by the time, headed with the course and day.
     */
    suspend fun quickNote(notebook: Boolean): QuickNote {
        check(drive.enabled) { "The drive isn't set up" }
        val now = OffsetDateTime.now(clock.withZone(props.zone))
        val project = currentProject()
        val folder = project?.let { drive.folderOf(it.id) + "Notes/" } ?: "${DriveService.PROJECTS}/${DriveService.NO_PROJECT}/"
        dav.makeFolders(folder)
        val stem = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"))
        val ext = if (notebook) ".pdf" else ".md"
        var path = "$folder$stem$ext"
        var n = 2
        while (dav.exists(path)) path = "$folder$stem (${n++})$ext"
        val bytes = if (notebook) {
            val empty = org.apache.pdfbox.pdmodel.PDDocument().use { d -> java.io.ByteArrayOutputStream().also { d.save(it) }.toByteArray() }
            NotebookPdf.write(empty, listOf(NotebookPage(UUID.randomUUID().toString(), -1, "dots", 595f, 842f)), emptyList(), emptyList(), emptyList())
        } else {
            val day = now.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.ENGLISH))
            "# ${listOfNotNull(project?.name, day).joinToString(" · ")}\n\n".toByteArray()
        }
        dav.put(path, Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)), bytes.size.toLong(), if (notebook) "application/pdf" else "text/markdown; charset=utf-8", absent = true)
        return QuickNote(path, project?.name)
    }

    private suspend fun nextClass(e: EventOccurrence, project: UUID, name: String): NextClass {
        val folder = if (drive.enabled) runCatching { drive.folderOf(project) }.getOrNull() else null
        val files = folder?.let { f ->
            listOf("${f}${DriveService.COURSE_FILES}/", f).flatMap { runCatching { dav.list(it) }.getOrNull().orEmpty() }
                .filter { !it.folder }
                .sortedByDescending { it.modified }
                .take(FILES)
        }.orEmpty()
        return NextClass(e, name, folder, files)
    }
}

/** A note just made for what's on now: where it is, and the class's project (none between classes). */
data class QuickNote(val path: String, val project: String?)

@RestController
@RequestMapping("/api/v1")
class TodayController(private val service: TodayService) {
    /** The day at a glance: the next class (room, newest files), today's events, tasks due soon, Triage's count. */
    @GetMapping("/today")
    suspend fun today(): Today = service.today()

    /** A new note, or ([kind] notebook) notebook page, in the Notes of the class on now (No project's, between classes). */
    @PostMapping("/quick-note")
    suspend fun quickNote(@RequestParam(defaultValue = "note") kind: String): QuickNote = service.quickNote(kind == "notebook")
}
