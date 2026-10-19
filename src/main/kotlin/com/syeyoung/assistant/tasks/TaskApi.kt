package com.syeyoung.assistant.tasks

import com.syeyoung.assistant.common.parseIfMatch
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class TaskController(private val service: TaskService) {

    /** Tasks, all of them unless filtered. `status` may repeat; `dueFrom`/`dueTo` are inclusive. */
    @GetMapping("/tasks")
    suspend fun tasks(
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) labelId: UUID?,
        @RequestParam(required = false) parentId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dueFrom: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dueTo: LocalDate?,
        @RequestParam(required = false) q: String?,
    ): List<TaskResponse> = service.tasks(
        TaskFilter(status.orEmpty().map(TaskStatus::of).toSet(), projectId, labelId, parentId, dueFrom, dueTo, q),
    )

    @GetMapping("/tasks/{id}")
    suspend fun task(@PathVariable id: UUID): TaskResponse = service.task(id)

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(@RequestBody req: CreateTaskRequest): TaskResponse = service.create(req)

    /** JSON merge patch; send `If-Match: "<version>"` to fail with 412 if it changed meanwhile. */
    @PatchMapping("/tasks/{id}", consumes = ["application/json", "application/merge-patch+json"])
    suspend fun update(
        @PathVariable id: UUID,
        @RequestBody patch: JsonNode,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): TaskResponse = service.update(id, patch, parseIfMatch(ifMatch))

    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(@PathVariable id: UUID) = service.delete(id)

    /** Relates this task to another: kind blocks, blocked_by, related, duplicate_of or duplicated_by. */
    @PostMapping("/tasks/{id}/relations")
    suspend fun relate(@PathVariable id: UUID, @RequestBody req: CreateRelationRequest): TaskResponse = service.relate(id, req)

    @DeleteMapping("/tasks/{id}/relations/{relationId}")
    suspend fun unrelate(@PathVariable id: UUID, @PathVariable relationId: UUID): TaskResponse = service.unrelate(id, relationId)

    @GetMapping("/projects")
    suspend fun projects(): List<ProjectResponse> = service.projects()

    @GetMapping("/projects/{id}")
    suspend fun project(@PathVariable id: UUID): ProjectResponse = service.project(id)

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createProject(@RequestBody req: CreateProjectRequest): ProjectResponse = service.createProject(req)

    @PatchMapping("/projects/{id}")
    suspend fun updateProject(@PathVariable id: UUID, @RequestBody req: UpdateProjectRequest): ProjectResponse = service.updateProject(id, req)

    /** Deletes the project; its tasks stay, without a project. */
    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteProject(@PathVariable id: UUID) = service.deleteProject(id)

    @GetMapping("/initiatives")
    suspend fun initiatives(): List<InitiativeResponse> = service.initiatives()

    @GetMapping("/initiatives/{id}")
    suspend fun initiative(@PathVariable id: UUID): InitiativeResponse = service.initiative(id)

    @PostMapping("/initiatives")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createInitiative(@RequestBody req: CreateInitiativeRequest): InitiativeResponse = service.createInitiative(req)

    /** Merge patch: name, description, status, targetDate (null clears it), projectIds (replaces them). */
    @PatchMapping("/initiatives/{id}")
    suspend fun updateInitiative(@PathVariable id: UUID, @RequestBody patch: JsonNode): InitiativeResponse = service.updateInitiative(id, patch)

    /** Deletes the initiative; its projects stay. */
    @DeleteMapping("/initiatives/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteInitiative(@PathVariable id: UUID) = service.deleteInitiative(id)

    @GetMapping("/labels")
    suspend fun labels(): List<LabelResponse> = service.labels()

    @PostMapping("/labels")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createLabel(@RequestBody req: LabelRequest): LabelResponse = service.createLabel(req)

    @PatchMapping("/labels/{id}")
    suspend fun updateLabel(@PathVariable id: UUID, @RequestBody req: LabelRequest): LabelResponse = service.updateLabel(id, req)

    @DeleteMapping("/labels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteLabel(@PathVariable id: UUID) = service.deleteLabel(id)
}
