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
        val events = week.await()
        val todays = events.filter { e -> e.start?.toLocalDate()?.let { it <= date } ?: (e.startDate!! <= date) }
            .filter { e -> e.end?.let { it.toLocalDate() >= date } ?: (e.endDate!! >= date) }
            .sortedWith(compareBy({ it.start != null }, { it.start }))
        // The next class: a project's event going on now or the soonest to start, this week.
        val lecture = events.filter { it.projectId != null && it.start != null && it.end!! > now }.minByOrNull { it.start!! }
        Today(date, lecture?.let { nextClass(it, it.projectId!!) }, todays, due.await(), waiting.await())
    }

    private suspend fun nextClass(e: EventOccurrence, project: UUID): NextClass {
        val name = runCatching { tasks.project(project).name }.getOrNull()
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

@RestController
@RequestMapping("/api/v1/today")
class TodayController(private val service: TodayService) {
    /** The day at a glance: the next class (room, newest files), today's events, tasks due soon, Triage's count. */
    @GetMapping
    suspend fun today(): Today = service.today()
}
