package com.syeyoung.assistant.tasks

import com.syeyoung.assistant.dav.Workspace
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class MoveReport(val moved: Int, val skipped: Int, val projects: Int, val labels: Int)

/**
 * One-time move of the to-dos stored in CalDAV into Tasks. A source's to-do keeps its reference, so
 * the next sync matches it; its categories become labels ("assignment", "quiz") and a project (the
 * course, claimed by the source's next sync). Others' categories become labels. Each to-do is
 * deleted from CalDAV once moved, so running it again moves only what's left.
 */
@RestController
class MoveFromCalDav(private val ws: Workspace, private val repo: TaskRepository, private val clock: Clock) {

    @PostMapping("/api/v1/admin/move-tasks")
    suspend fun move(): MoveReport {
        val now = Instant.now(clock)
        val todos = ws.snapshot().docs.mapNotNull { (href, doc) -> doc.main?.takeIf { it.name == "VTODO" }?.let { href to it } }
        val projectsBefore = repo.projects().size
        val labelsBefore = repo.labels().size
        val ids = mutableMapOf<String, UUID>()
        val parents = mutableMapOf<UUID, String>()
        var moved = 0
        var skipped = 0
        for ((href, c) in todos) {
            val ref = c.value(Workspace.SOURCE_PROP)
            val sourceType = ref?.substringBefore(':')
            if (ref != null && repo.bySourceRef(ref) != null) { skipped++; ws.delete(href, null); continue }
            val status = when (c.value("STATUS")?.uppercase()) {
                "COMPLETED" -> TaskStatus.DONE
                "CANCELLED" -> TaskStatus.CANCELED
                "IN-PROCESS" -> TaskStatus.IN_PROGRESS
                else -> if (c.prop("COMPLETED") != null) TaskStatus.DONE else TaskStatus.TODO
            }
            val categories = ws.categoriesOf(c)
            val (labelNames, projectName) = if (sourceType == null) categories to null
            else categories.partition { it in MODULE_LABELS }.let { (labels, rest) -> labels to rest.firstOrNull() }
            val due = c.prop("DUE")?.let(ws::dueOf)
            val id = repo.insert(
                TaskValues(
                    title = c.text("SUMMARY").orEmpty().ifBlank { "Untitled" }, description = c.text("DESCRIPTION").orEmpty(),
                    status = status, dueDate = due?.date, dueTime = due?.time,
                    projectId = projectName?.let { project(it, sourceType!!, now) },
                    closedAt = if (status.closed) c.prop("COMPLETED")?.let(ws::instantOf) ?: now else null,
                    sourceUrl = c.value("URL"),
                ),
                now, sourceType = sourceType, sourceRef = ref,
            )
            repo.setLabels(id, labelNames.map { label(it) })
            c.value("UID")?.let { ids[it] = id }
            c.all("RELATED-TO").firstOrNull { (it.param("RELTYPE") ?: "PARENT").equals("PARENT", true) }?.let { parents[id] = it.value }
            ws.delete(href, null)
            moved++
        }
        for ((child, parentUid) in parents) {
            val parent = ids[parentUid] ?: continue
            repo.task(child)?.let { repo.update(it.id, it.version, it.values().copy(parentId = parent), now) }
        }
        return MoveReport(moved, skipped, repo.projects().size - projectsBefore, repo.labels().size - labelsBefore)
    }

    private suspend fun project(name: String, sourceType: String, now: Instant): UUID {
        val all = repo.projects()
        all.find { it.name == name && it.sourceType == sourceType }?.let { return it.id }
        val p = ProjectRow(UUID.randomUUID(), name, "", leastUsed(all.map { it.color }), ProjectState.IN_PROGRESS, sourceType, null)
        repo.insertProject(p, now)
        return p.id
    }

    private suspend fun label(name: String): UUID {
        val all = repo.labels()
        all.find { it.name.equals(name, true) }?.let { return it.id }
        val l = LabelRow(UUID.randomUUID(), name, leastUsed(all.map { it.color }))
        repo.insertLabel(l)
        return l.id
    }

    private companion object {
        /** What PLMS labels by type; its other category is the course. */
        val MODULE_LABELS = setOf("assignment", "quiz")
    }
}
