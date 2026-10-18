package com.syeyoung.assistant.item

import com.syeyoung.assistant.common.ValidationException
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/** Converts API input (create bodies and JSON merge patches) into [ItemFields], checking shape only. */
@Component
class ItemInput(private val mapper: ObjectMapper) {

    fun fromCreate(req: CreateItemRequest): ItemFields =
        ItemFields(
            title = req.title,
            notes = req.notes,
            parentId = req.parentId,
            mainTagId = req.mainTagId,
            tagIds = req.tagIds,
            doneAt = req.doneAt,
            sourceType = req.source?.type ?: SourceType.MANUAL,
            sourceRef = req.source?.ref,
            sourceUrl = req.source?.url,
        ).withBy(req.by).withAt(req.at)

    /**
     * Applies a JSON merge patch (RFC 7396) to [current]. A field that is absent stays unchanged;
     * a field set to null is cleared.
     */
    fun applyPatch(current: ItemFields, patch: JsonNode): ItemFields {
        if (!patch.isObject) throw ValidationException("Patch body must be a JSON object")
        var f = current
        for ((name, value) in patch.properties()) {
            val v = value.takeUnless { it.isNull }
            f = when (name) {
                "title" -> f.copy(title = v?.let { read<String>(name, it) } ?: "")
                "notes" -> f.copy(notes = v?.let { read<String>(name, it) } ?: "")
                "by" -> f.withBy(v?.let { read<By>(name, it) })
                "at" -> f.withAt(v?.let { read<At>(name, it) })
                "parentId" -> f.copy(parentId = v?.let { read<UUID>(name, it) })
                "mainTagId" -> f.copy(mainTagId = v?.let { read<UUID>(name, it) })
                "tagIds" -> f.copy(tagIds = v?.let { read<Array<UUID>>(name, it).toList() } ?: emptyList())
                "doneAt" -> f.copy(doneAt = v?.let { read<Instant>(name, it) })
                "sourceUrl" -> f.copy(sourceUrl = v?.let { read<String>(name, it) })
                else -> throw ValidationException("Unknown or read-only field '$name'")
            }
        }
        return f
    }

    private inline fun <reified T : Any> read(field: String, node: JsonNode): T =
        try {
            mapper.treeToValue(node, T::class.java)
        } catch (e: JacksonException) {
            throw ValidationException("Invalid value for '$field': ${e.originalMessage}")
        } ?: throw ValidationException("Invalid value for '$field'")

    private fun ItemFields.withBy(by: By?) = copy(byDate = by?.date, byTime = by?.time)

    private fun ItemFields.withAt(at: At?): ItemFields {
        if (at == null) return copy(atStart = null, atEnd = null, atStartDate = null, atEndDate = null)
        return if (at.allDay) {
            if (at.start != null || at.end != null) throw ValidationException("An all-day 'at' takes startDate/endDate, not start/end")
            val start = at.startDate ?: throw ValidationException("An all-day 'at' needs startDate")
            val end = at.endDate ?: start
            if (end < start) throw ValidationException("'at' endDate must not be before startDate")
            copy(atStart = null, atEnd = null, atStartDate = start, atEndDate = end)
        } else {
            if (at.startDate != null || at.endDate != null) throw ValidationException("A timed 'at' takes start/end; set allDay for dates")
            val start = at.start ?: throw ValidationException("'at' needs start")
            val end = at.end ?: throw ValidationException("'at' needs end")
            if (!end.isAfter(start)) throw ValidationException("'at' end must be after start")
            copy(atStart = start.toInstant(), atEnd = end.toInstant(), atStartDate = null, atEndDate = null)
        }
    }
}
