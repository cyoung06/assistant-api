package com.syeyoung.assistant.series

import com.fasterxml.jackson.annotation.JsonValue
import com.syeyoung.assistant.common.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** What every occurrence of a series starts out as. */
data class SeriesTemplate(
    val title: String,
    val notes: String,
    val mainTagId: UUID?,
    val tagIds: List<UUID>,
    val allDay: Boolean,
    /** Timed occurrences: local start time and length. */
    val startTime: LocalTime? = null,
    val durationMinutes: Int? = null,
    /** All-day occurrences: number of days, starting on the occurrence date. */
    val spanDays: Int? = null,
)

data class Series(
    val id: UUID,
    val template: SeriesTemplate,
    val rule: RepeatRule,
    val startDate: LocalDate,
    val generatedUntil: LocalDate?,
    val version: Long,
    val seq: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

/** Which occurrences an edit or delete of one occurrence applies to. */
enum class Scope(@get:JsonValue val wire: String) {
    THIS("this"), FOLLOWING("following"), ALL("all");

    companion object {
        /** Null means [THIS]; anything unknown is rejected rather than guessed. */
        fun fromWire(value: String?): Scope = if (value == null) THIS else entries.find { it.wire == value }
            ?: throw ValidationException("scope must be one of this, following, all")
    }
}

data class SeriesResponse(
    val id: UUID,
    val title: String,
    val notes: String,
    val mainTagId: UUID?,
    val tagIds: List<UUID>,
    val allDay: Boolean,
    val startTime: LocalTime?,
    val durationMinutes: Int?,
    val spanDays: Int?,
    val startDate: LocalDate,
    val repeat: RepeatRule,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Only set in the change feed, for deleted series. */
    val deletedAt: Instant?,
)

fun Series.toResponse() = SeriesResponse(
    id = id,
    title = template.title,
    notes = template.notes,
    mainTagId = template.mainTagId,
    tagIds = template.tagIds,
    allDay = template.allDay,
    startTime = template.startTime,
    durationMinutes = template.durationMinutes,
    spanDays = template.spanDays,
    startDate = startDate,
    repeat = rule,
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)
