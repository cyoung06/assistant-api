package com.syeyoung.assistant.tag

import java.time.Instant
import java.util.UUID

data class Tag(
    val id: UUID,
    val name: String,
    val color: String,
    val version: Long,
    val seq: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

data class TagResponse(
    val id: UUID,
    val name: String,
    val color: String,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Only set in the change feed, for deleted tags. */
    val deletedAt: Instant?,
)

data class CreateTagRequest(
    /** Optional client-generated ID, so offline clients can create tags. */
    val id: UUID? = null,
    val name: String,
    val color: String,
)

data class UpdateTagRequest(
    val name: String? = null,
    val color: String? = null,
)

fun Tag.toResponse() = TagResponse(id, name, color, version, createdAt, updatedAt, deletedAt)
