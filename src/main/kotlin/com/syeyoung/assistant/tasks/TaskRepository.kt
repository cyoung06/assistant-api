package com.syeyoung.assistant.tasks

import com.syeyoung.assistant.common.bindNullable
import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.common.uuids
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** The fields of a task that writes set. */
data class TaskValues(
    val title: String,
    val description: String = "",
    val status: TaskStatus = TaskStatus.TODO,
    val priority: Priority = Priority.NONE,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val projectId: UUID? = null,
    val parentId: UUID? = null,
    val closedAt: Instant? = null,
    val sourceUrl: String? = null,
)

fun TaskRow.values() = TaskValues(title, description, status, priority, dueDate, dueTime, projectId, parentId, closedAt, sourceUrl)

@Repository
class TaskRepository(private val db: DatabaseClient) {

    // ---- tasks ----------------------------------------------------------------------

    suspend fun tasks(): List<TaskRow> = db.sql("$SELECT_TASKS group by t.id order by t.number").map(::task).flow().toList()

    suspend fun task(id: UUID): TaskRow? =
        db.sql("$SELECT_TASKS where t.id = :id group by t.id").bind("id", id).map(::task).awaitOneOrNull()

    suspend fun bySourceRef(ref: String): TaskRow? =
        db.sql("$SELECT_TASKS where t.source_ref = :ref group by t.id").bind("ref", ref).map(::task).awaitOneOrNull()

    suspend fun bySource(type: String): List<TaskRow> =
        db.sql("$SELECT_TASKS where t.source_type = :type group by t.id").bind("type", type).map(::task).flow().toList()

    suspend fun insert(v: TaskValues, now: Instant, sourceType: String? = null, sourceRef: String? = null): UUID {
        val id = UUID.randomUUID()
        db.sql(
            """
            insert into tasks (id, title, description, status, priority, due_date, due_time, project_id, parent_id,
                               closed_at, source_type, source_ref, source_url, created_at, updated_at)
            values (:id, :title, :description, :status, :priority, :dueDate, :dueTime, :projectId, :parentId,
                    :closedAt, :sourceType, :sourceRef, :sourceUrl, :now, :now)
            """,
        ).bind("id", id).bindValues(v).bindNullable("sourceType", sourceType, String::class.java)
            .bindNullable("sourceRef", sourceRef, String::class.java).bind("now", now)
            .fetch().awaitRowsUpdated()
        return id
    }

    /** Writes [v] if the task is still at [version]; false when it has changed since. */
    suspend fun update(id: UUID, version: Long, v: TaskValues, now: Instant): Boolean =
        db.sql(
            """
            update tasks set title = :title, description = :description, status = :status, priority = :priority,
                due_date = :dueDate, due_time = :dueTime, project_id = :projectId, parent_id = :parentId,
                closed_at = :closedAt, source_url = :sourceUrl, version = version + 1, updated_at = :now
            where id = :id and version = :version
            """,
        ).bind("id", id).bind("version", version).bindValues(v).bind("now", now)
            .fetch().awaitRowsUpdated() == 1L

    /** Deletes the task; sub-tasks, labels and relations go with it (cascade). */
    suspend fun delete(id: UUID) {
        db.sql("delete from tasks where id = :id").bind("id", id).fetch().awaitRowsUpdated()
    }

    suspend fun setLabels(taskId: UUID, labelIds: List<UUID>) {
        db.sql("delete from task_labels where task_id = :id").bind("id", taskId).fetch().awaitRowsUpdated()
        for (label in labelIds.distinct()) {
            db.sql("insert into task_labels (task_id, label_id) values (:t, :l)").bind("t", taskId).bind("l", label)
                .fetch().awaitRowsUpdated()
        }
    }

    /** Marks a change that isn't in the tasks row itself (labels, relations), so versions stay honest. */
    suspend fun touch(id: UUID, now: Instant) {
        db.sql("update tasks set version = version + 1, updated_at = :now where id = :id").bind("id", id).bind("now", now)
            .fetch().awaitRowsUpdated()
    }

    // ---- relations ------------------------------------------------------------------

    suspend fun relations(): List<RelationRow> =
        db.sql("select * from task_relations").map { r ->
            RelationRow(r.req("id"), RelationType.of(r.req("type")), r.req("task_id"), r.req("related_id"))
        }.flow().toList()

    suspend fun insertRelation(type: RelationType, taskId: UUID, relatedId: UUID): UUID {
        val id = UUID.randomUUID()
        db.sql(
            """
            insert into task_relations (id, type, task_id, related_id) values (:id, :type, :task, :related)
            on conflict do nothing
            """,
        ).bind("id", id).bind("type", type.wire).bind("task", taskId).bind("related", relatedId).fetch().awaitRowsUpdated()
        return id
    }

    suspend fun deleteRelation(id: UUID): RelationRow? =
        db.sql("delete from task_relations where id = :id returning *").bind("id", id).map { r ->
            RelationRow(r.req("id"), RelationType.of(r.req("type")), r.req("task_id"), r.req("related_id"))
        }.awaitOneOrNull()

    // ---- projects -------------------------------------------------------------------

    suspend fun projects(): List<ProjectRow> = db.sql("select * from projects order by lower(name)").map(::project).flow().toList()

    suspend fun project(id: UUID): ProjectRow? = db.sql("select * from projects where id = :id").bind("id", id).map(::project).awaitOneOrNull()

    suspend fun insertProject(p: ProjectRow, now: Instant) {
        db.sql(
            """
            insert into projects (id, name, description, color, state, source_type, source_key, created_at, updated_at)
            values (:id, :name, :description, :color, :state, :sourceType, :sourceKey, :now, :now)
            """,
        ).bind("id", p.id).bind("name", p.name).bind("description", p.description).bind("color", p.color).bind("state", p.state.wire)
            .bindNullable("sourceType", p.sourceType, String::class.java).bindNullable("sourceKey", p.sourceKey, String::class.java)
            .bind("now", now).fetch().awaitRowsUpdated()
    }

    suspend fun updateProject(p: ProjectRow, now: Instant) {
        db.sql(
            """
            update projects set name = :name, description = :description, color = :color, state = :state,
                source_type = :sourceType, source_key = :sourceKey, updated_at = :now
            where id = :id
            """,
        ).bind("id", p.id).bind("name", p.name).bind("description", p.description).bind("color", p.color).bind("state", p.state.wire)
            .bindNullable("sourceType", p.sourceType, String::class.java).bindNullable("sourceKey", p.sourceKey, String::class.java)
            .bind("now", now).fetch().awaitRowsUpdated()
    }

    /** Deletes the project; its tasks stay, without a project. */
    suspend fun deleteProject(id: UUID) {
        db.sql("delete from projects where id = :id").bind("id", id).fetch().awaitRowsUpdated()
    }

    // ---- labels ---------------------------------------------------------------------

    suspend fun labels(): List<LabelRow> =
        db.sql("select * from labels order by lower(name)").map { r -> LabelRow(r.req("id"), r.req("name"), r.req("color")) }.flow().toList()

    suspend fun insertLabel(l: LabelRow) {
        db.sql("insert into labels (id, name, color) values (:id, :name, :color)")
            .bind("id", l.id).bind("name", l.name).bind("color", l.color).fetch().awaitRowsUpdated()
    }

    suspend fun updateLabel(l: LabelRow) {
        db.sql("update labels set name = :name, color = :color where id = :id")
            .bind("id", l.id).bind("name", l.name).bind("color", l.color).fetch().awaitRowsUpdated()
    }

    suspend fun deleteLabel(id: UUID) {
        db.sql("delete from labels where id = :id").bind("id", id).fetch().awaitRowsUpdated()
    }

    // ---- mapping --------------------------------------------------------------------

    private fun DatabaseClient.GenericExecuteSpec.bindValues(v: TaskValues) =
        bind("title", v.title).bind("description", v.description).bind("status", v.status.wire).bind("priority", v.priority.level.toShort())
            .bindNullable("dueDate", v.dueDate, LocalDate::class.java).bindNullable("dueTime", v.dueTime, LocalTime::class.java)
            .bindNullable("projectId", v.projectId, UUID::class.java).bindNullable("parentId", v.parentId, UUID::class.java)
            .bindNullable("closedAt", v.closedAt, Instant::class.java).bindNullable("sourceUrl", v.sourceUrl, String::class.java)

    private fun task(r: Readable) = TaskRow(
        id = r.req("id"),
        number = r.req("number"),
        title = r.req("title"),
        description = r.req("description"),
        status = TaskStatus.of(r.req("status")),
        priority = Priority.ofLevel(r.req<Short>("priority").toInt()),
        dueDate = r.opt("due_date"),
        dueTime = r.opt("due_time"),
        projectId = r.opt("project_id"),
        parentId = r.opt("parent_id"),
        closedAt = r.opt("closed_at"),
        sourceType = r.opt("source_type"),
        sourceRef = r.opt("source_ref"),
        sourceUrl = r.opt("source_url"),
        version = r.req("version"),
        createdAt = r.req("created_at"),
        updatedAt = r.req("updated_at"),
        labelIds = r.uuids("label_ids"),
    )

    private fun project(r: Readable) = ProjectRow(
        id = r.req("id"),
        name = r.req("name"),
        description = r.req("description"),
        color = r.req("color"),
        state = ProjectState.of(r.req("state")),
        sourceType = r.opt("source_type"),
        sourceKey = r.opt("source_key"),
    )

    private companion object {
        const val SELECT_TASKS = """
            select t.*, coalesce(array_agg(tl.label_id) filter (where tl.label_id is not null), '{}') as label_ids
            from tasks t left join task_labels tl on tl.task_id = t.id
        """
    }
}
