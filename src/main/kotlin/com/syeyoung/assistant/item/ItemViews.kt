package com.syeyoung.assistant.item

import com.syeyoung.assistant.config.AppProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Turns stored items into API responses, computing the derived fields in a few batched queries. */
@Component
class ItemViews(
    private val items: ItemRepository,
    private val clock: Clock,
    private val props: AppProperties,
) {
    suspend fun render(item: Item): ItemResponse = render(listOf(item)).single()

    suspend fun render(list: List<Item>): List<ItemResponse> {
        if (list.isEmpty()) return emptyList()
        val now = Instant.now(clock)
        val ids = list.map { it.id }
        val lineage = items.lineage(ids)
        val totals = items.sessionTotals(ids, now)
        return list.map { item ->
            val l = lineage[item.id]
            val t = totals[item.id]
            item.toResponse(
                zone = props.zone,
                derived = Derived(
                    overdue = isOverdue(item.fields, now, props.zone),
                    color = l?.color,
                    displayTitle = l?.displayTitle ?: item.fields.title,
                    timeSpentMinutes = t?.spentMinutes ?: 0,
                    timePlannedMinutes = t?.plannedMinutes ?: 0,
                ),
            )
        }
    }

    companion object {
        /** The instant a deadline passes. Date-only deadlines pass at the start of the next day. */
        fun deadline(f: ItemFields, zone: ZoneId): Instant? {
            val date = f.byDate ?: return null
            return f.byTime?.let { date.atTime(it).atZone(zone).toInstant() }
                ?: date.plusDays(1).atStartOfDay(zone).toInstant()
        }

        fun isOverdue(f: ItemFields, now: Instant, zone: ZoneId): Boolean {
            if (f.doneAt != null) return false
            val deadline = deadline(f, zone) ?: return false
            return !now.isBefore(deadline)
        }
    }
}

fun ItemFields.toBy(): By? = byDate?.let { By(it, byTime) }

fun ItemFields.toAt(zone: ZoneId): At? = when {
    atStart != null && atEnd != null -> At(
        allDay = false,
        start = atStart.atZone(zone).toOffsetDateTime(),
        end = atEnd.atZone(zone).toOffsetDateTime(),
    )
    atStartDate != null && atEndDate != null -> At(allDay = true, startDate = atStartDate, endDate = atEndDate)
    else -> null
}

fun Item.toResponse(zone: ZoneId, derived: Derived) = ItemResponse(
    id = id,
    title = fields.title,
    notes = fields.notes,
    by = fields.toBy(),
    at = fields.toAt(zone),
    parentId = fields.parentId,
    mainTagId = fields.mainTagId,
    tagIds = fields.tagIds,
    doneAt = fields.doneAt,
    source = Source(fields.sourceType, fields.sourceRef, fields.sourceUrl),
    version = version,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    derived = derived,
)
