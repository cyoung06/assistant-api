package com.syeyoung.assistant.item

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import com.syeyoung.assistant.series.RepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

enum class SourceType(@get:JsonValue val wire: String) {
    MANUAL("manual"), PLMS("plms"), EMAIL("email"), UPLOAD("upload");

    companion object {
        fun fromWire(value: String) = entries.find { it.wire == value }
            ?: throw IllegalArgumentException("unknown source type $value")
    }
}

/** The editable state of an item, independent of identity and bookkeeping. */
data class ItemFields(
    val title: String = "",
    val notes: String = "",
    val byDate: LocalDate? = null,
    val byTime: LocalTime? = null,
    val atStart: Instant? = null,
    val atEnd: Instant? = null,
    val atStartDate: LocalDate? = null,
    val atEndDate: LocalDate? = null,
    val parentId: UUID? = null,
    val mainTagId: UUID? = null,
    val tagIds: List<UUID> = emptyList(),
    val doneAt: Instant? = null,
    val sourceType: SourceType = SourceType.MANUAL,
    val sourceRef: String? = null,
    val sourceUrl: String? = null,
    /** Set on occurrences of a repeating event. */
    val seriesId: UUID? = null,
    /** The date this occurrence was generated for; unchanged when the occurrence is moved. */
    val occurrenceDate: LocalDate? = null,
    /** Changed on its own, so edits to the whole series leave it alone. */
    val detached: Boolean = false,
) {
    val hasTimedAt get() = atStart != null
    val hasAllDayAt get() = atStartDate != null
    val hasAt get() = hasTimedAt || hasAllDayAt
}

data class Item(
    val id: UUID,
    val fields: ItemFields,
    val version: Long,
    val seq: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

// ---- API representation ----------------------------------------------------

/** Deadline. A null [time] means "by the end of [date]". */
data class By(val date: LocalDate, val time: LocalTime? = null)

/**
 * Scheduled time: either timed ([start]/[end]) or all-day ([startDate]/[endDate], end inclusive).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class At(
    val allDay: Boolean = false,
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
)

data class Source(val type: SourceType, val ref: String?, val url: String?)

data class SeriesRef(val id: UUID, val occurrenceDate: LocalDate, val detached: Boolean)

data class Derived(
    /** Has a deadline that has passed and is not done. */
    val overdue: Boolean,
    /** Color of the main tag, or of the nearest ancestor's main tag. */
    val color: String?,
    /** The title, or the nearest ancestor's title if this one is empty. */
    val displayTitle: String,
    /** Timed descendants that are done or have ended. */
    val timeSpentMinutes: Long,
    /** Timed descendants that are not done and have not ended yet. */
    val timePlannedMinutes: Long,
    /** All live descendants, at any depth. */
    val subItems: Int,
    /** Of [subItems], how many are done. */
    val subItemsDone: Int,
)

data class ItemResponse(
    val id: UUID,
    val title: String,
    val notes: String,
    val by: By?,
    val at: At?,
    val parentId: UUID?,
    val mainTagId: UUID?,
    val tagIds: List<UUID>,
    val doneAt: Instant?,
    val source: Source,
    /** Set when this item is one occurrence of a repeating event. */
    val series: SeriesRef?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Only set in the change feed, for deleted items. */
    val deletedAt: Instant?,
    val derived: Derived,
)

data class CreateItemRequest(
    /** Optional client-generated ID, so offline clients can create items. */
    val id: UUID? = null,
    val title: String = "",
    val notes: String = "",
    val by: By? = null,
    val at: At? = null,
    val parentId: UUID? = null,
    val mainTagId: UUID? = null,
    val tagIds: List<UUID> = emptyList(),
    val doneAt: Instant? = null,
    val source: CreateSource? = null,
    /** Makes the new item the first occurrence of a repeating event. Needs `at` and no `by`. */
    val repeat: RepeatRule? = null,
)

data class CreateSource(val type: SourceType, val ref: String? = null, val url: String? = null)

data class ItemPage(val items: List<ItemResponse>, val nextCursor: String?)
