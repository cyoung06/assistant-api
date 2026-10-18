package com.syeyoung.assistant.tasks

import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.common.VersionMismatchException
import com.syeyoung.assistant.config.AppProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class TaskFilter(
    val statuses: Set<TaskStatus> = emptySet(),
    val projectId: UUID? = null,
    val labelId: UUID? = null,
    val parentId: UUID? = null,
    /** Due on or after / on or before these dates (inclusive). */
    val dueFrom: LocalDate? = null,
    val dueTo: LocalDate? = null,
    val q: String? = null,
)

/** Everything a response needs, read once per request (the data is one person's tasks). */
private class TaskGraph(val tasks: List<TaskRow>, val relations: List<RelationRow>) {
    val byId = tasks.associateBy { it.id }
    val children = tasks.filter { it.parentId != null }.groupBy { it.parentId!! }

    fun descendants(id: UUID): List<TaskRow> {
        val out = mutableListOf<TaskRow>()
        val queue = ArrayDeque(listOf(id))
        val seen = mutableSetOf(id)
        while (queue.isNotEmpty()) {
            for (c in children[queue.removeFirst()].orEmpty()) if (seen.add(c.id)) { out += c; queue += c.id }
        }
        return out
    }
}

@Service
class TaskService(
    private val repo: TaskRepository,
    private val clock: Clock,
    private val mapper: ObjectMapper,
    props: AppProperties,
) {
    private val zone = props.zone

    // ---- tasks ----------------------------------------------------------------------

    suspend fun tasks(f: TaskFilter): List<TaskResponse> {
        val g = graph()
        val q = f.q?.trim()?.takeIf { it.isNotEmpty() }
        return g.tasks.asSequence()
            .filter { f.statuses.isEmpty() || it.status in f.statuses }
            .filter { f.projectId == null || it.projectId == f.projectId }
            .filter { f.labelId == null || f.labelId in it.labelIds }
            .filter { f.parentId == null || it.parentId == f.parentId }
            .filter { f.dueFrom == null || (it.dueDate != null && it.dueDate >= f.dueFrom) }
            .filter { f.dueTo == null || (it.dueDate != null && it.dueDate <= f.dueTo) }
            .filter { q == null || it.title.contains(q, true) || it.description.contains(q, true) || "#${it.number}" == q }
            .map { render(g, it) }
            .toList()
    }

    suspend fun task(id: UUID): TaskResponse = graph().let { g -> render(g, g.byId[id] ?: throw NotFoundException("Task", id)) }

    @Transactional
    suspend fun create(req: CreateTaskRequest): TaskResponse {
        val title = req.title.trim().ifEmpty { throw ValidationException("Title is required") }
        val now = Instant.now(clock)
        req.parentId?.let { repo.task(it) ?: throw ValidationException("No task $it") }
        req.projectId?.let { repo.project(it) ?: throw ValidationException("No project $it") }
        checkLabels(req.labelIds)
        val id = repo.insert(
            TaskValues(
                title = title, description = req.description, status = req.status, priority = req.priority,
                dueDate = req.due?.date, dueTime = req.due?.time, projectId = req.projectId, parentId = req.parentId,
                closedAt = if (req.status.closed) now else null,
            ),
            now,
        )
        repo.setLabels(id, req.labelIds)
        return task(id)
    }

    /**
     * Merge patch: title, description, status, priority, due, projectId, labelIds, parentId. A source
     * owns the title and due date of its tasks. [ifMatch] is the version the client last saw.
     */
    @Transactional
    suspend fun update(id: UUID, patch: JsonNode, ifMatch: Long?): TaskResponse {
        if (!patch.isObject) throw ValidationException("Patch body must be a JSON object")
        val current = repo.task(id) ?: throw NotFoundException("Task", id)
        if (ifMatch != null && ifMatch != current.version) throw VersionMismatchException(current.version)
        val now = Instant.now(clock)
        var v = current.values()
        var labels: List<UUID>? = null
        for ((name, value) in patch.properties()) {
            if (current.sourceType != null && name in setOf("title", "due")) {
                throw ConflictException("fixed", "This task comes from ${current.sourceType.uppercase()}; its $name is set there")
            }
            val n = value.takeUnless { it.isNull }
            v = when (name) {
                "title" -> v.copy(title = n?.asString()?.trim()?.ifEmpty { null } ?: throw ValidationException("Title is required"))
                "description" -> v.copy(description = n?.asString().orEmpty())
                "status" -> {
                    val s = TaskStatus.of(n?.asString() ?: throw ValidationException("Status is required"))
                    v.copy(status = s, closedAt = if (!s.closed) null else if (current.status.closed) current.closedAt else now)
                }
                "priority" -> v.copy(priority = n?.let { Priority.of(it.asString()) } ?: Priority.NONE)
                "due" -> n?.let { mapper.treeToValue(it, DueDto::class.java) }.let { v.copy(dueDate = it?.date, dueTime = it?.time) }
                "projectId" -> v.copy(projectId = n?.let { uuid(it.asString()) }?.also { p -> repo.project(p) ?: throw ValidationException("No project $p") })
                "labelIds" -> { labels = n?.let { a -> (0 until a.size()).map { uuid(a.get(it).asString()) } }.orEmpty(); v }
                "parentId" -> v.copy(parentId = n?.let { uuid(it.asString()) }?.also { checkParent(id, it) })
                else -> throw ValidationException("Unknown or read-only field '$name'")
            }
        }
        if (!repo.update(id, current.version, v, now)) throw VersionMismatchException(repo.task(id)?.version ?: current.version)
        labels?.let { checkLabels(it); repo.setLabels(id, it) }
        return task(id)
    }

    /** Deletes a task with its sub-tasks. A source's task deleted here is not recreated by its next sync. */
    @Transactional
    suspend fun delete(id: UUID) {
        repo.task(id) ?: throw NotFoundException("Task", id)
        repo.delete(id)
    }

    // ---- relations ------------------------------------------------------------------

    /** Adds a relation seen from [id], e.g. kind "blocked_by" with taskId = the blocker. */
    @Transactional
    suspend fun relate(id: UUID, req: CreateRelationRequest): TaskResponse {
        repo.task(id) ?: throw NotFoundException("Task", id)
        repo.task(req.taskId) ?: throw ValidationException("No task ${req.taskId}")
        if (req.taskId == id) throw ValidationException("A task can't be related to itself")
        when (RelationKind.of(req.kind)) {
            RelationKind.BLOCKS -> repo.insertRelation(RelationType.BLOCKS, id, req.taskId)
            RelationKind.BLOCKED_BY -> repo.insertRelation(RelationType.BLOCKS, req.taskId, id)
            RelationKind.RELATED -> repo.insertRelation(RelationType.RELATED, id, req.taskId)
            RelationKind.DUPLICATE_OF -> repo.insertRelation(RelationType.DUPLICATE, id, req.taskId)
            RelationKind.DUPLICATED_BY -> repo.insertRelation(RelationType.DUPLICATE, req.taskId, id)
        }
        val now = Instant.now(clock)
        repo.touch(id, now)
        repo.touch(req.taskId, now)
        return task(id)
    }

    @Transactional
    suspend fun unrelate(id: UUID, relationId: UUID): TaskResponse {
        val r = repo.deleteRelation(relationId)?.takeIf { it.taskId == id || it.relatedId == id } ?: throw NotFoundException("Relation", relationId)
        val now = Instant.now(clock)
        repo.touch(r.taskId, now)
        repo.touch(r.relatedId, now)
        return task(id)
    }

    // ---- projects -------------------------------------------------------------------

    suspend fun projects(): List<ProjectResponse> {
        val tasks = repo.tasks()
        return repo.projects().map { renderProject(it, tasks) }
    }

    suspend fun project(id: UUID): ProjectResponse = renderProject(repo.project(id) ?: throw NotFoundException("Project", id), repo.tasks())

    @Transactional
    suspend fun createProject(req: CreateProjectRequest): ProjectResponse {
        val name = req.name.trim().ifEmpty { throw ValidationException("Name is required") }
        val color = req.color?.let(::validColor) ?: leastUsed(repo.projects().map { it.color })
        val p = ProjectRow(UUID.randomUUID(), name, req.description, color, req.state, null, null)
        repo.insertProject(p, Instant.now(clock))
        return project(p.id)
    }

    @Transactional
    suspend fun updateProject(id: UUID, req: UpdateProjectRequest): ProjectResponse {
        val p = repo.project(id) ?: throw NotFoundException("Project", id)
        repo.updateProject(
            p.copy(
                name = req.name?.trim()?.ifEmpty { throw ValidationException("Name is required") } ?: p.name,
                description = req.description ?: p.description,
                color = req.color?.let(::validColor) ?: p.color,
                state = req.state ?: p.state,
            ),
            Instant.now(clock),
        )
        return project(id)
    }

    @Transactional
    suspend fun deleteProject(id: UUID) {
        repo.project(id) ?: throw NotFoundException("Project", id)
        repo.deleteProject(id)
    }

    // ---- labels ---------------------------------------------------------------------

    suspend fun labels(): List<LabelResponse> = repo.labels().map { LabelResponse(it.id, it.name, it.color) }

    @Transactional
    suspend fun createLabel(req: LabelRequest): LabelResponse {
        val name = req.name?.trim()?.ifEmpty { null } ?: throw ValidationException("Name is required")
        val all = repo.labels()
        if (all.any { it.name.equals(name, true) }) throw ConflictException("exists", "A label named '$name' exists")
        val l = LabelRow(UUID.randomUUID(), name, req.color?.let(::validColor) ?: leastUsed(all.map { it.color }))
        repo.insertLabel(l)
        return LabelResponse(l.id, l.name, l.color)
    }

    @Transactional
    suspend fun updateLabel(id: UUID, req: LabelRequest): LabelResponse {
        val all = repo.labels()
        val l = all.find { it.id == id } ?: throw NotFoundException("Label", id)
        val name = req.name?.trim()?.ifEmpty { throw ValidationException("Name is required") } ?: l.name
        if (all.any { it.id != id && it.name.equals(name, true) }) throw ConflictException("exists", "A label named '$name' exists")
        val next = l.copy(name = name, color = req.color?.let(::validColor) ?: l.color)
        repo.updateLabel(next)
        return LabelResponse(next.id, next.name, next.color)
    }

    @Transactional
    suspend fun deleteLabel(id: UUID) {
        repo.labels().find { it.id == id } ?: throw NotFoundException("Label", id)
        repo.deleteLabel(id)
    }

    // ---- rendering ------------------------------------------------------------------

    private suspend fun graph() = TaskGraph(repo.tasks(), repo.relations())

    private fun render(g: TaskGraph, t: TaskRow): TaskResponse {
        val subs = g.descendants(t.id)
        val relations = g.relations.mapNotNull { r ->
            val (kind, other) = when (t.id) {
                r.taskId -> when (r.type) {
                    RelationType.BLOCKS -> RelationKind.BLOCKS
                    RelationType.RELATED -> RelationKind.RELATED
                    RelationType.DUPLICATE -> RelationKind.DUPLICATE_OF
                } to r.relatedId
                r.relatedId -> when (r.type) {
                    RelationType.BLOCKS -> RelationKind.BLOCKED_BY
                    RelationType.RELATED -> RelationKind.RELATED
                    RelationType.DUPLICATE -> RelationKind.DUPLICATED_BY
                } to r.taskId
                else -> return@mapNotNull null
            }
            val o = g.byId[other] ?: return@mapNotNull null
            RelationResponse(r.id, kind, TaskRef(o.id, o.number, o.title, o.status))
        }
        return TaskResponse(
            id = t.id, number = t.number, title = t.title, description = t.description, status = t.status, priority = t.priority,
            due = t.dueDate?.let { DueDto(it, t.dueTime) }, projectId = t.projectId, labelIds = t.labelIds, parentId = t.parentId,
            subtasks = subs.size, subtasksDone = subs.count { it.status == TaskStatus.DONE },
            relations = relations, blocked = relations.any { it.kind == RelationKind.BLOCKED_BY && !it.task.status.closed },
            source = t.sourceType, url = t.sourceUrl, overdue = isOverdue(t, Instant.now(clock)),
            createdAt = t.createdAt, updatedAt = t.updatedAt, closedAt = t.closedAt, version = t.version,
        )
    }

    private fun renderProject(p: ProjectRow, tasks: List<TaskRow>): ProjectResponse {
        val mine = tasks.filter { it.projectId == p.id && it.status != TaskStatus.CANCELED }
        return ProjectResponse(p.id, p.name, p.description, p.color, p.state, p.sourceType, mine.size, mine.count { it.status == TaskStatus.DONE })
    }

    fun isOverdue(t: TaskRow, now: Instant): Boolean {
        if (t.status.closed) return false
        val date = t.dueDate ?: return false
        val deadline = t.dueTime?.let { date.atTime(it).atZone(zone).toInstant() } ?: date.plusDays(1).atStartOfDay(zone).toInstant()
        return !now.isBefore(deadline)
    }

    private suspend fun checkParent(id: UUID, parent: UUID) {
        val g = graph()
        if (parent == id || g.byId[parent] == null || g.descendants(id).any { it.id == parent }) throw ValidationException("Invalid parent")
    }

    private suspend fun checkLabels(ids: List<UUID>) {
        val known = repo.labels().map { it.id }.toSet()
        ids.find { it !in known }?.let { throw ValidationException("No label $it") }
    }

    private fun uuid(s: String): UUID = runCatching { UUID.fromString(s) }.getOrElse { throw ValidationException("Not an ID: '$s'") }
}

/** The palette color used least among [used]. */
fun leastUsed(used: List<String>): String {
    val counts = used.groupingBy { it.uppercase() }.eachCount()
    return PALETTE.minBy { counts[it] ?: 0 }
}
