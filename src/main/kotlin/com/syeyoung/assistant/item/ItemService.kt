package com.syeyoung.assistant.item

import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.common.VersionMismatchException
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.tag.TagRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class ItemService(
    private val items: ItemRepository,
    private val tags: TagRepository,
    private val input: ItemInput,
    private val views: ItemViews,
    private val clock: Clock,
    private val props: AppProperties,
) {
    suspend fun get(id: UUID): ItemResponse = views.render(items.find(id) ?: throw NotFoundException("Item", id))

    suspend fun descendants(id: UUID): List<ItemResponse> {
        items.find(id) ?: throw NotFoundException("Item", id)
        return views.render(items.descendants(id).sortedWith(scheduleOrder))
    }

    suspend fun list(filter: ItemFilter, cursor: String?, limit: Int): ItemPage {
        if (limit !in 1..200) throw ValidationException("limit must be between 1 and 200")
        // Fetch one extra row to know whether another page exists.
        val rows = items.list(filter, Instant.now(clock), props.zone, cursor?.let(::decodeCursor), limit + 1)
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.last().let { encodeCursor(ItemCursor(it.createdAt, it.id)) } else null
        return ItemPage(views.render(page), next)
    }

    @Transactional
    suspend fun create(req: CreateItemRequest): ItemResponse {
        val id = req.id ?: UUID.randomUUID()
        if (items.exists(id)) throw ConflictException("id_exists", "Item $id already exists")
        val fields = validate(input.fromCreate(req), self = null)
        return views.render(items.insert(id, fields, Instant.now(clock)))
    }

    @Transactional
    suspend fun update(id: UUID, patch: JsonNode, expectedVersion: Long?): ItemResponse {
        val current = items.find(id, forUpdate = true) ?: throw NotFoundException("Item", id)
        if (expectedVersion != null && expectedVersion != current.version) throw VersionMismatchException(current.version)
        val fields = validate(input.applyPatch(current.fields, patch), self = id)
        val now = Instant.now(clock)
        val updated = items.update(id, fields, now)
        // Completing an item completes everything under it. Un-completing affects only the item itself.
        if (current.fields.doneAt == null && fields.doneAt != null) {
            items.completeDescendants(id, fields.doneAt, now)
        }
        return views.render(updated)
    }

    /** Soft-deletes the item and all its descendants. Undo with [restore]. */
    @Transactional
    suspend fun delete(id: UUID, expectedVersion: Long?) {
        val current = items.find(id, forUpdate = true) ?: throw NotFoundException("Item", id)
        if (expectedVersion != null && expectedVersion != current.version) throw VersionMismatchException(current.version)
        items.softDeleteTree(id, Instant.now(clock))
    }

    /** Restores a deleted item together with the descendants that were deleted with it. */
    @Transactional
    suspend fun restore(id: UUID): ItemResponse {
        val deleted = items.findDeleted(id) ?: throw NotFoundException("Deleted item", id)
        deleted.fields.parentId?.let { parent ->
            items.find(parent) ?: throw ConflictException("parent_deleted", "Restore the parent item $parent first")
        }
        items.restoreTree(id, deleted.deletedAt!!, Instant.now(clock))
        return get(id)
    }

    /** Checks references and limits; returns the fields with the main tag folded into the tag list. */
    private suspend fun validate(f: ItemFields, self: UUID?): ItemFields {
        if (f.title.length > MAX_TITLE) throw ValidationException("Title is longer than $MAX_TITLE characters")
        if (f.notes.length > MAX_NOTES) throw ValidationException("Notes are longer than $MAX_NOTES characters")

        f.parentId?.let { parent ->
            if (parent == self) throw ValidationException("An item cannot be its own parent")
            items.find(parent) ?: throw ValidationException("Parent item $parent does not exist")
            if (self != null && items.isDescendant(parent, of = self)) {
                throw ValidationException("Parent $parent is inside this item; that would create a cycle")
            }
        }

        val tagIds = (f.tagIds + listOfNotNull(f.mainTagId)).distinct()
        val missing = tagIds - tags.existing(tagIds)
        if (missing.isNotEmpty()) throw ValidationException("Unknown tags: ${missing.joinToString()}")
        return f.copy(tagIds = tagIds)
    }

    private fun encodeCursor(c: ItemCursor): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${c.createdAt}|${c.id}".toByteArray())

    private fun decodeCursor(s: String): ItemCursor = try {
        val (at, id) = String(Base64.getUrlDecoder().decode(s)).split('|', limit = 2)
        ItemCursor(Instant.parse(at), UUID.fromString(id))
    } catch (e: Exception) {
        throw ValidationException("Invalid cursor")
    }

    /** Scheduled items first, by their earliest date or time; then the rest by creation. */
    private val scheduleOrder = compareBy<Item, Instant?>(nullsLast()) { it.sortKey() }.thenBy { it.createdAt }

    private fun Item.sortKey(): Instant? = fields.atStart
        ?: fields.atStartDate?.atStartOfDay(props.zone)?.toInstant()
        ?: ItemViews.deadline(fields, props.zone)

    private companion object {
        const val MAX_TITLE = 500
        const val MAX_NOTES = 100_000
    }
}
