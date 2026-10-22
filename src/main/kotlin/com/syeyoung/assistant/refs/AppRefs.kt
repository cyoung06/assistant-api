package com.syeyoung.assistant.refs

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.tasks.TaskRepository
import org.springframework.stereotype.Component
import java.util.UUID

/** Tasks (by title or #number) and projects. */
@Component
class TaskRefs(private val repo: TaskRepository, props: AppProperties) : RefProvider {
    private val base = props.publicUrl

    override suspend fun search(query: String, limit: Int): List<Ref> {
        val q = query.trim().removePrefix("#")
        val projects = repo.projects()
        val projectName = projects.associate { it.id to it.name }
        val tasks = repo.tasks()
            .filter { q.isEmpty() || plainText(it.title).contains(q, true) || it.number.toString() == q }
            .sortedWith(compareBy({ it.status.closed }, { -it.number }))
            .take(limit)
            .map { Ref(RefKind.TASK, "#${it.number} ${plainText(it.title)}", AppLinks.task(base, it.id), it.projectId?.let(projectName::get), it.updatedAt) }
        val matchingProjects = projects.filter { q.isEmpty() || it.name.contains(q, true) }.take(limit)
            .map { Ref(RefKind.PROJECT, it.name, AppLinks.project(base, it.id), "project") }
        return tasks + matchingProjects
    }

    override suspend fun resolve(url: String): Ref? {
        val target = AppLinks.parse(url) ?: return null
        val id = runCatching { UUID.fromString(target.id) }.getOrNull() ?: return null
        return when (target.kind) {
            RefKind.TASK -> repo.task(id)?.let { Ref(RefKind.TASK, "#${it.number} ${plainText(it.title)}", AppLinks.task(base, it.id), date = it.updatedAt) }
            RefKind.PROJECT -> repo.project(id)?.let { Ref(RefKind.PROJECT, it.name, AppLinks.project(base, it.id), "project") }
            else -> null
        }
    }
}

/** Calendar events (a repeating one is one reference). */
@Component
class EventRefs(private val ws: Workspace, props: AppProperties) : RefProvider {
    private val base = props.publicUrl

    override suspend fun search(query: String, limit: Int): List<Ref> {
        val s = ws.snapshot()
        return s.events.filter { query.isBlank() || plainText(it.title).contains(query, true) }
            .sortedByDescending { it.start ?: it.startDate?.atStartOfDay(ws.zone)?.toInstant() }
            .take(limit)
            .map { e -> Ref(RefKind.EVENT, plainText(e.title), AppLinks.event(base, e.uid), s.calendars.find { it.id == e.calendarId }?.name, e.start ?: e.startDate?.atStartOfDay(ws.zone)?.toInstant()) }
    }

    override suspend fun resolve(url: String): Ref? {
        val target = AppLinks.parse(url)?.takeIf { it.kind == RefKind.EVENT } ?: return null
        val e = ws.findEvent(target.id) ?: return null
        return Ref(RefKind.EVENT, plainText(e.title), AppLinks.event(base, e.uid), date = e.start ?: e.startDate?.atStartOfDay(ws.zone)?.toInstant())
    }
}

/** Notes and files in the drive, by name (as the drive was last walked; see [DriveIndex]). */
@Component
class DriveRefs(private val index: com.syeyoung.assistant.drive.DriveIndex, props: AppProperties) : RefProvider {
    private val base = props.publicUrl

    override suspend fun search(query: String, limit: Int): List<Ref> =
        if (query.isBlank()) emptyList() else index.search(query, limit).filter { !it.path.startsWith(".Trash/") }.map(::ref)

    override suspend fun resolve(url: String): Ref? {
        val target = AppLinks.parse(url)?.takeIf { it.kind == RefKind.NOTE || it.kind == RefKind.FILE } ?: return null
        return index.get(target.id)?.let(::ref) ?: Ref(target.kind, target.id.substringAfterLast('/'), AppLinks.file(base, target.id), projectOf(target.id))
    }

    /** What a note or file is, as a reference: its name (a note's without .md), in its project. */
    fun ref(i: com.syeyoung.assistant.drive.Indexed): Ref {
        val note = Regex("""\.(md|markdown)$""", RegexOption.IGNORE_CASE).containsMatchIn(i.name)
        return Ref(if (note) RefKind.NOTE else RefKind.FILE, if (note) i.name.substringBeforeLast('.') else i.name, AppLinks.file(base, i.path), projectOf(i.path), i.modified)
    }

    private fun projectOf(path: String) = path.split('/').getOrNull(1)
}
