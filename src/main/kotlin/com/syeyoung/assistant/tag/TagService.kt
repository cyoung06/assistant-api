package com.syeyoung.assistant.tag

import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.common.VersionMismatchException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class TagService(private val tags: TagRepository, private val clock: Clock) {

    suspend fun list(): List<Tag> = tags.findAll()

    suspend fun get(id: UUID): Tag = tags.find(id) ?: throw NotFoundException("Tag", id)

    @Transactional
    suspend fun create(req: CreateTagRequest): Tag {
        val name = validName(req.name)
        val color = validColor(req.color)
        if (tags.nameTaken(name, null)) throw ConflictException("tag_exists", "A tag named '$name' already exists")
        val id = req.id ?: UUID.randomUUID()
        if (tags.findAllIncludingDeleted(listOf(id)).isNotEmpty()) throw ConflictException("id_exists", "Tag $id already exists")
        return tags.insert(id, name, color, Instant.now(clock))
    }

    @Transactional
    suspend fun update(id: UUID, req: UpdateTagRequest, expectedVersion: Long?): Tag {
        val current = tags.find(id, forUpdate = true) ?: throw NotFoundException("Tag", id)
        if (expectedVersion != null && expectedVersion != current.version) throw VersionMismatchException(current.version)
        val name = req.name?.let(::validName) ?: current.name
        val color = req.color?.let(::validColor) ?: current.color
        if (tags.nameTaken(name, id)) throw ConflictException("tag_exists", "A tag named '$name' already exists")
        return tags.update(id, name, color, Instant.now(clock))
    }

    @Transactional
    suspend fun delete(id: UUID, expectedVersion: Long?) {
        val current = tags.find(id, forUpdate = true) ?: throw NotFoundException("Tag", id)
        if (expectedVersion != null && expectedVersion != current.version) throw VersionMismatchException(current.version)
        val now = Instant.now(clock)
        tags.detachFromItems(id, now)
        tags.softDelete(id, now)
    }

    private fun validName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 60) throw ValidationException("Tag name must be 1–60 characters")
        return trimmed
    }

    private fun validColor(color: String): String {
        if (!COLOR.matches(color)) throw ValidationException("Color must look like #3B82F6")
        return color.uppercase()
    }

    private companion object {
        val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    }
}
