package com.syeyoung.assistant.tag

import com.syeyoung.assistant.common.parseIfMatch
import com.syeyoung.assistant.common.withETag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/tags")
class TagController(private val service: TagService) {

    @GetMapping
    suspend fun list(): List<TagResponse> = service.list().map { it.toResponse() }

    @GetMapping("/{id}")
    suspend fun get(@PathVariable id: UUID): ResponseEntity<TagResponse> =
        service.get(id).let { withETag(it.version, it.toResponse()) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(@RequestBody req: CreateTagRequest): TagResponse = service.create(req).toResponse()

    @PatchMapping("/{id}")
    suspend fun update(
        @PathVariable id: UUID,
        @RequestBody req: UpdateTagRequest,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<TagResponse> =
        service.update(id, req, parseIfMatch(ifMatch)).let { withETag(it.version, it.toResponse()) }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(@PathVariable id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?) {
        service.delete(id, parseIfMatch(ifMatch))
    }
}
