package com.syeyoung.assistant.tasks

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.source.SourceRecord
import com.syeyoung.assistant.source.SourceRepository
import com.syeyoung.assistant.source.SourceSnapshot
import com.syeyoung.assistant.source.SourceTag
import com.syeyoung.assistant.source.SourceType
import com.syeyoung.assistant.source.SyncReport
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Applies a source's deadline records to Tasks. The source owns title, due date, link (and the
 * description, when it sends one); status, priority, labels, project and relations stay the user's
 * once the task exists. Tasks the user deleted are not recreated.
 */
@Component
class TaskSourceSync(
    private val repo: TaskRepository,
    private val sources: SourceRepository,
    private val props: AppProperties,
) {
    suspend fun apply(type: SourceType, records: List<SourceRecord>, snapshot: SourceSnapshot, now: Instant): SyncReport {
        var report = SyncReport()
        val prefix = "${type.wire}:"
        val existing = repo.bySource(type.wire).associateBy { it.sourceRef!! }
        val known = sources.refs(type)
        val projects = mutableMapOf<String, UUID>()

        for (record in records) {
            val ref = prefix + record.ref
            val current = existing[ref]
            report = when {
                current == null && ref in known -> report.copy(skippedDeleted = report.skippedDeleted + 1)
                current == null -> {
                    val projectId = record.project?.let { p -> projects.getOrPut(p.key) { resolveProject(type, p, now) } }
                    val id = repo.insert(
                        TaskValues(
                            title = record.title, description = record.notes.orEmpty(), dueDate = record.dueDate, dueTime = record.dueTime,
                            projectId = projectId, sourceUrl = record.url,
                        ),
                        now, sourceType = type.wire, sourceRef = ref,
                    )
                    repo.setLabels(id, record.tags.map { resolveLabel(it.name) })
                    sources.rememberRef(type, ref, id.toString())
                    report.copy(created = report.created + 1)
                }
                current.title == record.title && current.dueDate == record.dueDate && current.dueTime == record.dueTime &&
                    current.sourceUrl == record.url && (!record.ownsNotes || record.notes == null || current.description == record.notes) ->
                    report.copy(unchanged = report.unchanged + 1)
                else -> {
                    fun withSource(t: TaskRow) = t.values().copy(
                        title = record.title, dueDate = record.dueDate, dueTime = record.dueTime, sourceUrl = record.url,
                        description = record.notes?.takeIf { record.ownsNotes } ?: t.description,
                    )
                    // If the user edited it meanwhile, apply the source's fields on top of that edit.
                    if (!repo.update(current.id, current.version, withSource(current), now)) {
                        repo.task(current.id)?.let { repo.update(it.id, it.version, withSource(it), now) }
                    }
                    report.copy(updated = report.updated + 1)
                }
            }
        }

        val reported = records.map { prefix + it.ref }.toSet()
        val missing = existing.values.filter { it.sourceRef !in reported }
        if (snapshot.deleteMissing) {
            for (t in missing) { repo.delete(t.id); report = report.copy(deleted = report.deleted + 1) }
        } else if (snapshot.completeMissing) {
            for (t in missing.filter { !it.status.closed }) {
                repo.update(t.id, t.version, t.values().copy(status = TaskStatus.DONE, closedAt = now), now)
                report = report.copy(completed = report.completed + 1)
            }
        } else snapshot.completeMissingDueFrom?.let { from ->
            for (t in missing) {
                if (t.status.closed || t.dueDate == null) continue
                val deadline = t.dueTime?.let { t.dueDate.atTime(it).atZone(props.zone).toInstant() } ?: t.dueDate.plusDays(1).atStartOfDay(props.zone).toInstant()
                if (deadline.isBefore(from)) continue
                repo.update(t.id, t.version, t.values().copy(status = TaskStatus.DONE, closedAt = now), now)
                report = report.copy(completed = report.completed + 1)
            }
        }
        return report
    }

    /**
     * One project per course (or other grouping) the source lists: created when missing, left
     * alone if the user deleted it. Same-named groupings (sections of one course) share a project.
     * The source's projects no longer listed are marked completed. Returns how many were created.
     */
    suspend fun syncProjects(type: SourceType, tags: List<SourceTag>, now: Instant): Int {
        val known = sources.refs(type)
        var created = 0
        for (tag in tags.distinctBy { it.name }) {
            val ref = "${type.wire}:project:${tag.name}"
            val existing = findProject(type, tag)
            if (existing == null && ref in known) continue
            val id = existing?.id ?: resolveProject(type, tag, now).also { created++ }
            sources.rememberRef(type, ref, id.toString())
            // Put into its initiative once; after that the user decides where it belongs.
            val linkRef = "${type.wire}:initiative:${tag.name}"
            if (tag.initiative != null && linkRef !in known) {
                repo.link(id, resolveInitiative(tag.initiative, now))
                sources.rememberRef(type, linkRef, tag.initiative)
            }
        }
        val listed = tags.map { it.name }.toSet()
        val active = setOf(ProjectState.BACKLOG, ProjectState.PLANNED, ProjectState.IN_PROGRESS)
        for (p in repo.projects().filter { it.sourceType == type.wire && it.state in active && it.name !in listed }) {
            repo.updateProject(p.copy(state = ProjectState.COMPLETED), now)
        }
        return created
    }

    private suspend fun findProject(type: SourceType, tag: SourceTag): ProjectRow? {
        val all = repo.projects()
        return all.find { it.sourceType == type.wire && it.sourceKey == tag.key } ?: all.find { it.sourceType == type.wire && it.name == tag.name }
    }

    /**
     * The project standing for [tag]: the one mapped to its key, else a same-named project of this
     * source (claimed when it has no key yet), else a new one.
     */
    private suspend fun resolveProject(type: SourceType, tag: SourceTag, now: Instant): UUID {
        val all = repo.projects()
        all.find { it.sourceType == type.wire && it.sourceKey == tag.key }?.let { return it.id }
        all.find { it.sourceType == type.wire && it.name == tag.name }?.let {
            if (it.sourceKey == null) repo.updateProject(it.copy(sourceKey = tag.key), now)
            return it.id
        }
        val p = ProjectRow(UUID.randomUUID(), tag.name, "", leastUsed(all.map { it.color }), ProjectState.IN_PROGRESS, type.wire, tag.key)
        repo.insertProject(p, now)
        return p.id
    }

    /** The initiative named [name], made (active) when there's none. */
    private suspend fun resolveInitiative(name: String, now: Instant): UUID {
        repo.initiatives().find { it.name == name }?.let { return it.id }
        val i = InitiativeRow(UUID.randomUUID(), name, "", InitiativeStatus.ACTIVE, null)
        repo.insertInitiative(i, now)
        return i.id
    }

    private suspend fun resolveLabel(name: String): UUID {
        val all = repo.labels()
        all.find { it.name.equals(name, true) }?.let { return it.id }
        val l = LabelRow(UUID.randomUUID(), name, leastUsed(all.map { it.color }))
        repo.insertLabel(l)
        return l.id
    }
}
