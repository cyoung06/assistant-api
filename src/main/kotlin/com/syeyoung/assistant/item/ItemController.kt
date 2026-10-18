package com.syeyoung.assistant.item

import com.syeyoung.assistant.common.parseIfMatch
import com.syeyoung.assistant.common.withETag
import org.springframework.http.HttpHeaders
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

@RestController
@RequestMapping("/api/v1/items")
class ItemController(private val service: ItemService) {

    @GetMapping
    suspend fun list(
        @RequestParam tag: UUID?,
        @RequestParam parentId: UUID?,
        @RequestParam(defaultValue = "false") topLevel: Boolean,
        @RequestParam done: Boolean?,
        @RequestParam scheduled: Boolean?,
        @RequestParam(defaultValue = "false") overdue: Boolean,
        @RequestParam q: String?,
        @RequestParam cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ItemPage = service.list(
        ItemFilter(
            tagId = tag,
            parentId = parentId,
            topLevel = topLevel,
            done = done,
            scheduled = scheduled,
            overdueOnly = overdue,
            query = q?.takeIf { it.isNotBlank() },
        ),
        cursor,
        limit,
    )

    @GetMapping("/{id}")
    suspend fun get(@PathVariable id: UUID): ResponseEntity<ItemResponse> =
        service.get(id).let { withETag(it.version, it) }

    /** All live descendants, flattened, scheduled ones first. */
    @GetMapping("/{id}/descendants")
    suspend fun descendants(@PathVariable id: UUID): List<ItemResponse> = service.descendants(id)

    @PostMapping
    suspend fun create(@RequestBody req: CreateItemRequest): ResponseEntity<ItemResponse> =
        service.create(req).let {
            ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, "\"${it.version}\"")
                .header(HttpHeaders.LOCATION, "/api/v1/items/${it.id}")
                .body(it)
        }

    /** JSON merge patch. Send `If-Match: "<version>"` to reject the write if someone else changed the item. */
    @PatchMapping("/{id}", consumes = ["application/json", "application/merge-patch+json"])
    suspend fun update(
        @PathVariable id: UUID,
        @RequestBody patch: JsonNode,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<ItemResponse> =
        service.update(id, patch, parseIfMatch(ifMatch)).let { withETag(it.version, it) }

    /** Deletes the item and everything under it. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(@PathVariable id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?) {
        service.delete(id, parseIfMatch(ifMatch))
    }

    /** Undoes a delete. */
    @PostMapping("/{id}/restore")
    suspend fun restore(@PathVariable id: UUID): ResponseEntity<ItemResponse> =
        service.restore(id).let { withETag(it.version, it) }
}
