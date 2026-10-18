package com.syeyoung.assistant.tasks

import com.fasterxml.jackson.annotation.JsonValue
import com.syeyoung.assistant.common.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// ---- values ---------------------------------------------------------------------

enum class TaskStatus(@get:JsonValue val wire: String) {
    BACKLOG("backlog"), TODO("todo"), IN_PROGRESS("in_progress"), DONE("done"), CANCELED("canceled");

    val closed get() = this == DONE || this == CANCELED

    companion object {
        fun of(wire: String) = entries.find { it.wire == wire } ?: throw ValidationException("Unknown status '$wire'")
    }
}

/** Linear's scale; [level] is what's stored (0 none, 1 urgent … 4 low). */
enum class Priority(@get:JsonValue val wire: String, val level: Int) {
    NONE("none", 0), URGENT("urgent", 1), HIGH("high", 2), MEDIUM("medium", 3), LOW("low", 4);

    companion object {
        fun of(wire: String) = entries.find { it.wire == wire } ?: throw ValidationException("Unknown priority '$wire'")
        fun ofLevel(level: Int) = entries.first { it.level == level }
    }
}

enum class ProjectState(@get:JsonValue val wire: String) {
    BACKLOG("backlog"), PLANNED("planned"), IN_PROGRESS("in_progress"), PAUSED("paused"), COMPLETED("completed"), CANCELED("canceled");

    companion object {
        fun of(wire: String) = entries.find { it.wire == wire } ?: throw ValidationException("Unknown project state '$wire'")
    }
}

/** How relations are stored: [BLOCKS] and [DUPLICATE] have a direction, [RELATED] doesn't. */
enum class RelationType(val wire: String) {
    BLOCKS("blocks"), RELATED("related"), DUPLICATE("duplicate");

    companion object {
        fun of(wire: String) = entries.find { it.wire == wire } ?: error("unknown relation type $wire")
    }
}

/** A relation as seen from one task. */
enum class RelationKind(@get:JsonValue val wire: String) {
    BLOCKS("blocks"), BLOCKED_BY("blocked_by"), RELATED("related"), DUPLICATE_OF("duplicate_of"), DUPLICATED_BY("duplicated_by");

    companion object {
        fun of(wire: String) = entries.find { it.wire == wire } ?: throw ValidationException("Unknown relation '$wire'")
    }
}

// ---- rows -------------------------------------------------------------------------

data class TaskRow(
    val id: UUID,
    val number: Long,
    val title: String,
    val description: String,
    val status: TaskStatus,
    val priority: Priority,
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val projectId: UUID?,
    val parentId: UUID?,
    val closedAt: Instant?,
    val sourceType: String?,
    val sourceRef: String?,
    val sourceUrl: String?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val labelIds: List<UUID>,
)

data class RelationRow(val id: UUID, val type: RelationType, val taskId: UUID, val relatedId: UUID)

data class ProjectRow(
    val id: UUID,
    val name: String,
    val description: String,
    val color: String,
    val state: ProjectState,
    val sourceType: String?,
    val sourceKey: String?,
)

data class LabelRow(val id: UUID, val name: String, val color: String)

// ---- API shapes -------------------------------------------------------------------

data class DueDto(val date: LocalDate, val time: LocalTime? = null)

data class TaskRef(val id: UUID, val number: Long, val title: String, val status: TaskStatus)

data class RelationResponse(val id: UUID, val kind: RelationKind, val task: TaskRef)

data class TaskResponse(
    val id: UUID,
    val number: Long,
    val title: String,
    val description: String,
    val status: TaskStatus,
    val priority: Priority,
    val due: DueDto?,
    val projectId: UUID?,
    val labelIds: List<UUID>,
    val parentId: UUID?,
    val subtasks: Int,
    val subtasksDone: Int,
    val relations: List<RelationResponse>,
    /** Has an open task blocking it. */
    val blocked: Boolean,
    /** Set when a source (e.g. "plms") owns the title, due date and link. */
    val source: String?,
    val url: String?,
    val overdue: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val closedAt: Instant?,
    val version: Long,
)

data class CreateTaskRequest(
    val title: String,
    val description: String = "",
    val status: TaskStatus = TaskStatus.TODO,
    val priority: Priority = Priority.NONE,
    val due: DueDto? = null,
    val projectId: UUID? = null,
    val labelIds: List<UUID> = emptyList(),
    val parentId: UUID? = null,
)

data class CreateRelationRequest(val kind: String, val taskId: UUID)

data class ProjectResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val color: String,
    val state: ProjectState,
    val source: String?,
    /** Tasks in the project, canceled ones left out, and how many of them are done. */
    val taskCount: Int,
    val doneCount: Int,
)

data class CreateProjectRequest(
    val name: String,
    val description: String = "",
    val color: String? = null,
    val state: ProjectState = ProjectState.IN_PROGRESS,
)

data class UpdateProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val color: String? = null,
    val state: ProjectState? = null,
)

data class LabelResponse(val id: UUID, val name: String, val color: String)

data class LabelRequest(val name: String? = null, val color: String? = null)

/** Colors new projects and labels cycle through. */
val PALETTE = listOf("#2F5BE0", "#E0642F", "#1F8A4C", "#A23FD1", "#D13B6E", "#0E8C9C", "#B88A00", "#7A5230", "#5B6CFF", "#C2410C", "#4D7C0F", "#BE185D")

fun validColor(c: String): String {
    if (!Regex("^#[0-9A-Fa-f]{6}$").matches(c)) throw ValidationException("Color must look like #3B82F6")
    return c.uppercase()
}
