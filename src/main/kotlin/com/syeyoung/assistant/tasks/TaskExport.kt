package com.syeyoung.assistant.tasks

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.dav.IcsDocument
import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.refs.AppLinks
import com.syeyoung.assistant.refs.plainText
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Tasks as iCalendar, for the feed: each task as a VTODO, plus a short "Due: …" VEVENT per open task
 * with a due date, since calendar apps don't show VTODO in subscriptions.
 */
@Component
class TaskExport(private val repo: TaskRepository, private val clock: Clock, props: AppProperties) {
    private val zone = props.zone
    private val base = props.publicUrl
    private val plmsBase = props.sources.plms.baseUrl

    suspend fun components(): List<IcsDocument.Comp> {
        val tasks = repo.tasks()
        val projects = repo.projects().associateBy { it.id }
        val labels = repo.labels().associateBy { it.id }
        val blockers = repo.relations().filter { it.type == RelationType.BLOCKS }.groupBy({ it.relatedId }, { it.taskId })
        val stamp = Workspace.utc(Instant.now(clock))
        return tasks.flatMap { t ->
            val todo = IcsDocument.Comp("VTODO")
            todo.set("UID", uid(t.id))
            todo.set("DTSTAMP", stamp)
            todo.set("CREATED", Workspace.utc(t.createdAt))
            todo.set("LAST-MODIFIED", Workspace.utc(t.updatedAt))
            todo.setText("SUMMARY", plainText(t.title))
            // Calendar apps only open web links: mail and PLMS links as pages.
            todo.setText("DESCRIPTION", t.description.ifBlank { null }?.let { AppLinks.webLinks(it, base, plmsBase) })
            todo.set("STATUS", STATUS.getValue(t.status))
            if (t.status == TaskStatus.DONE) {
                todo.set("PERCENT-COMPLETE", "100")
                t.closedAt?.let { todo.set("COMPLETED", Workspace.utc(it)) }
            }
            PRIORITY[t.priority]?.let { todo.set("PRIORITY", it) }
            due(t)?.let { (value, params) -> todo.set("DUE", value, params) }
            val categories = listOfNotNull(t.projectId?.let { projects[it]?.name }) + t.labelIds.mapNotNull { labels[it]?.name }
            if (categories.isNotEmpty()) todo.set("CATEGORIES", categories.joinToString(",") { it.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;") })
            t.parentId?.let { todo.props += IcsDocument.Prop("RELATED-TO", mapOf("RELTYPE" to "PARENT"), uid(it)) }
            blockers[t.id].orEmpty().forEach { todo.props += IcsDocument.Prop("RELATED-TO", mapOf("RELTYPE" to "DEPENDS-ON"), uid(it)) }
            todo.set("URL", t.sourceUrl)
            listOfNotNull(todo, dueEvent(t, stamp))
        }
    }

    private fun dueEvent(t: TaskRow, stamp: String): IcsDocument.Comp? {
        if (t.status.closed || t.dueDate == null) return null
        val e = IcsDocument.Comp("VEVENT")
        e.set("UID", "${uid(t.id)}-due")
        e.set("DTSTAMP", stamp)
        if (t.dueTime == null) {
            e.set("DTSTART", DATE.format(t.dueDate), mapOf("VALUE" to "DATE"))
            e.set("DTEND", DATE.format(t.dueDate.plusDays(1)), mapOf("VALUE" to "DATE"))
        } else {
            val at = t.dueDate.atTime(t.dueTime).atZone(zone).toInstant()
            e.set("DTSTART", Workspace.utc(at))
            e.set("DTEND", Workspace.utc(at.plus(DEADLINE_LENGTH)))
        }
        e.setText("SUMMARY", "Due: ${plainText(t.title)}")
        e.set("URL", t.sourceUrl)
        e.set("RELATED-TO", uid(t.id))
        return e
    }

    private fun due(t: TaskRow): Pair<String, Map<String, String>>? {
        val date = t.dueDate ?: return null
        return if (t.dueTime == null) DATE.format(date) to mapOf("VALUE" to "DATE")
        else Workspace.utc(date.atTime(t.dueTime).atZone(zone).toInstant()) to emptyMap()
    }

    private fun uid(id: UUID) = "task-$id@assistant"

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        val DEADLINE_LENGTH: Duration = Duration.ofMinutes(15)
        val STATUS = mapOf(
            TaskStatus.BACKLOG to "NEEDS-ACTION", TaskStatus.TODO to "NEEDS-ACTION", TaskStatus.IN_PROGRESS to "IN-PROCESS",
            TaskStatus.DONE to "COMPLETED", TaskStatus.CANCELED to "CANCELLED",
        )
        /** iCalendar: 1 highest … 9 lowest; Apple and others read 1–4 high, 5 medium, 6–9 low. */
        val PRIORITY = mapOf(Priority.URGENT to "1", Priority.HIGH to "3", Priority.MEDIUM to "5", Priority.LOW to "9")
    }
}
