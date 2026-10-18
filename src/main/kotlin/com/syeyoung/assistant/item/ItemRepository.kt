package com.syeyoung.assistant.item

import com.syeyoung.assistant.common.bindNullable
import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.common.uuids
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class Lineage(val color: String?, val displayTitle: String, val tagIds: Set<UUID>)

/** Aggregates over an item's live descendants. */
data class DescendantTotals(
    val spentMinutes: Long,
    val plannedMinutes: Long,
    val subItems: Int,
    val subItemsDone: Int,
)

data class ItemFilter(
    val tagId: UUID? = null,
    val parentId: UUID? = null,
    val topLevel: Boolean = false,
    val done: Boolean? = null,
    /** True: has a `by` or an `at`. False: has neither. */
    val scheduled: Boolean? = null,
    /** Only items whose deadline has passed and that are not done. */
    val overdueOnly: Boolean = false,
    /** True: only occurrences of repeating events. False: leave them out. */
    val repeating: Boolean? = null,
    val query: String? = null,
)

data class ItemCursor(val createdAt: Instant, val id: UUID)

@Repository
class ItemRepository(private val db: DatabaseClient) {

    suspend fun find(id: UUID, forUpdate: Boolean = false): Item? =
        db.sql("select * from items where id = :id and deleted_at is null" + if (forUpdate) " for update" else "")
            .bind("id", id).map(::toItem).awaitOneOrNull()

    suspend fun exists(id: UUID): Boolean =
        db.sql("select 1 from items where id = :id").bind("id", id).map { _ -> 1 }.awaitOneOrNull() != null

    suspend fun findDeleted(id: UUID): Item? =
        db.sql("select * from items where id = :id and deleted_at is not null for update")
            .bind("id", id).map(::toItem).awaitOneOrNull()

    suspend fun findAllIncludingDeleted(ids: Collection<UUID>): List<Item> {
        if (ids.isEmpty()) return emptyList()
        return db.sql("select * from items where id = any(:ids)")
            .bind("ids", ids.toTypedArray()).map(::toItem).flow().toList()
    }

    suspend fun list(filter: ItemFilter, now: Instant, zone: ZoneId, after: ItemCursor?, limit: Int): List<Item> {
        val where = mutableListOf("deleted_at is null")
        filter.tagId?.let { where += ":tagId = any(tag_ids)" }
        filter.parentId?.let { where += "parent_id = :parentId" }
        if (filter.topLevel) where += "parent_id is null"
        when (filter.done) {
            true -> where += "done_at is not null"
            false -> where += "done_at is null"
            null -> {}
        }
        when (filter.scheduled) {
            true -> where += "(by_date is not null or at_start is not null or at_start_date is not null)"
            false -> where += "(by_date is null and at_start is null and at_start_date is null)"
            null -> {}
        }
        if (filter.overdueOnly) where += "done_at is null and by_date is not null and $DEADLINE_SQL <= :now"
        when (filter.repeating) {
            true -> where += "series_id is not null"
            false -> where += "series_id is null"
            null -> {}
        }
        filter.query?.let { where += "(title ilike :q or notes ilike :q)" }
        after?.let { where += "(created_at, id) < (:afterCreatedAt, :afterId)" }

        var spec = db.sql(
            "select * from items where ${where.joinToString(" and ")} order by created_at desc, id desc limit :limit",
        ).bind("limit", limit)
        filter.tagId?.let { spec = spec.bind("tagId", it) }
        filter.parentId?.let { spec = spec.bind("parentId", it) }
        if (filter.overdueOnly) spec = spec.bind("now", now).bind("zone", zone.id)
        filter.query?.let { spec = spec.bind("q", "%${escapeLike(it)}%") }
        after?.let { spec = spec.bind("afterCreatedAt", it.createdAt).bind("afterId", it.id) }
        return spec.map(::toItem).flow().toList()
    }

    /** All live descendants of [id] (not including [id]), in no particular order. */
    suspend fun descendants(id: UUID): List<Item> =
        db.sql(
            """
            with recursive down as (
                select c.* from items c where c.parent_id = :id and c.deleted_at is null
                union all
                select c.* from items c join down on c.parent_id = down.id where c.deleted_at is null
            )
            select * from down
            """,
        ).bind("id", id).map(::toItem).flow().toList()

    suspend fun isDescendant(candidate: UUID, of: UUID): Boolean =
        db.sql(
            """
            with recursive down as (
                select id from items where parent_id = :of and deleted_at is null
                union all
                select c.id from items c join down on c.parent_id = down.id where c.deleted_at is null
            )
            select 1 from down where id = :candidate limit 1
            """,
        ).bind("of", of).bind("candidate", candidate).map { _ -> 1 }.awaitOneOrNull() != null

    /** Items that touch the half-open range [[from], [to]) through their `at` or their `by`. */
    suspend fun inRange(from: Instant, to: Instant, fromDate: LocalDate, toDate: LocalDate): List<Item> =
        db.sql(
            """
            select * from items where deleted_at is null and (
                (at_start < :to and at_end > :from)
                or (at_start_date < :toDate and at_end_date >= :fromDate)
                or (by_date >= :fromDate and by_date < :toDate)
            )
            """,
        ).bind("from", from).bind("to", to).bind("fromDate", fromDate).bind("toDate", toDate)
            .map(::toItem).flow().toList()

    suspend fun insert(id: UUID, f: ItemFields, now: Instant): Item =
        bindFields(
            db.sql(
                """
                insert into items (id, title, notes, by_date, by_time, at_start, at_end, at_start_date, at_end_date,
                    parent_id, main_tag_id, tag_ids, done_at, source_type, source_ref, source_url,
                    series_id, occurrence_date, detached, created_at, updated_at)
                values (:id, :title, :notes, :byDate, :byTime, :atStart, :atEnd, :atStartDate, :atEndDate,
                    :parentId, :mainTagId, :tagIds, :doneAt, :sourceType, :sourceRef, :sourceUrl,
                    :seriesId, :occurrenceDate, :detached, :now, :now)
                returning *
                """,
            ).bind("id", id).bind("now", now),
            f,
        ).map(::toItem).awaitOneOrNull()!!

    suspend fun update(id: UUID, f: ItemFields, now: Instant): Item =
        bindFields(
            db.sql(
                """
                update items set title = :title, notes = :notes, by_date = :byDate, by_time = :byTime,
                    at_start = :atStart, at_end = :atEnd, at_start_date = :atStartDate, at_end_date = :atEndDate,
                    parent_id = :parentId, main_tag_id = :mainTagId, tag_ids = :tagIds, done_at = :doneAt,
                    source_type = :sourceType, source_ref = :sourceRef, source_url = :sourceUrl,
                    series_id = :seriesId, occurrence_date = :occurrenceDate, detached = :detached,
                    updated_at = :now, version = version + 1, seq = nextval('change_seq')
                where id = :id
                returning *
                """,
            ).bind("id", id).bind("now", now),
            f,
        ).map(::toItem).awaitOneOrNull()!!

    /** Marks every live, not-yet-done descendant of [id] as done at [doneAt]. */
    suspend fun completeDescendants(id: UUID, doneAt: Instant, now: Instant) {
        db.sql(
            """
            with recursive down as (
                select id from items where parent_id = :id and deleted_at is null
                union all
                select c.id from items c join down on c.parent_id = down.id where c.deleted_at is null
            )
            update items set done_at = :doneAt, updated_at = :now, version = version + 1, seq = nextval('change_seq')
            where id in (select id from down) and done_at is null
            """,
        ).bind("id", id).bind("doneAt", doneAt).bind("now", now).fetch().awaitRowsUpdated()
    }

    /** Soft-deletes [id] and all its live descendants, all stamped with the same [now]. */
    suspend fun softDeleteTree(id: UUID, now: Instant) {
        db.sql(
            """
            with recursive tree as (
                select id from items where id = :id and deleted_at is null
                union all
                select c.id from items c join tree on c.parent_id = tree.id where c.deleted_at is null
            )
            update items set deleted_at = :now, updated_at = :now, version = version + 1, seq = nextval('change_seq')
            where id in (select id from tree)
            """,
        ).bind("id", id).bind("now", now).fetch().awaitRowsUpdated()
    }

    /** Restores [id] and the descendants that were deleted together with it (same deletion time). */
    suspend fun restoreTree(id: UUID, deletedAt: Instant, now: Instant) {
        db.sql(
            """
            with recursive tree as (
                select id from items where id = :id and deleted_at = :deletedAt
                union all
                select c.id from items c join tree on c.parent_id = tree.id where c.deleted_at = :deletedAt
            )
            update items set deleted_at = null, updated_at = :now, version = version + 1, seq = nextval('change_seq')
            where id in (select id from tree)
            """,
        ).bind("id", id).bind("deletedAt", deletedAt).bind("now", now).fetch().awaitRowsUpdated()
    }

    /** For each of [ids]: inherited color and title, and the union of tags along the ancestor chain. */
    suspend fun lineage(ids: Collection<UUID>): Map<UUID, Lineage> {
        if (ids.isEmpty()) return emptyMap()
        return db.sql(
            """
            with recursive up as (
                select i.id as origin, i.parent_id, i.main_tag_id, i.title, i.tag_ids, 0 as depth
                from items i where i.id = any(:ids)
                union all
                select up.origin, p.parent_id, p.main_tag_id, p.title, p.tag_ids, up.depth + 1
                from up join items p on p.id = up.parent_id
            )
            select o.origin,
                (select t.color from up u join tags t on t.id = u.main_tag_id and t.deleted_at is null
                    where u.origin = o.origin order by u.depth limit 1) as color,
                (select u.title from up u where u.origin = o.origin and u.title <> ''
                    order by u.depth limit 1) as display_title,
                (select array_agg(distinct x) from up u cross join lateral unnest(u.tag_ids) x
                    where u.origin = o.origin) as tag_ids
            from (select distinct origin from up) o
            """,
        ).bind("ids", ids.toTypedArray()).map { r ->
            r.req<UUID>("origin") to Lineage(
                color = r.opt("color"),
                displayTitle = r.opt("display_title") ?: "",
                tagIds = r.uuids("tag_ids").toSet(),
            )
        }.flow().toList().toMap()
    }

    /**
     * For each of [ids]: minutes of timed descendants, split by whether they are done or over as of
     * [now], and how many descendants there are and how many are done.
     */
    suspend fun descendantTotals(ids: Collection<UUID>, now: Instant): Map<UUID, DescendantTotals> {
        if (ids.isEmpty()) return emptyMap()
        return db.sql(
            """
            with recursive down as (
                select c.id, c.parent_id as origin from items c where c.parent_id = any(:ids) and c.deleted_at is null
                union all
                select c.id, down.origin from items c join down on c.parent_id = down.id where c.deleted_at is null
            )
            select d.origin,
                coalesce(sum(extract(epoch from i.at_end - i.at_start) / 60)
                    filter (where i.at_start is not null and (i.done_at is not null or i.at_end <= :now)), 0)::bigint as spent,
                coalesce(sum(extract(epoch from i.at_end - i.at_start) / 60)
                    filter (where i.at_start is not null and i.done_at is null and i.at_end > :now), 0)::bigint as planned,
                count(*)::int as sub_items,
                (count(*) filter (where i.done_at is not null))::int as sub_items_done
            from down d join items i on i.id = d.id
            group by d.origin
            """,
        ).bind("ids", ids.toTypedArray()).bind("now", now).map { r ->
            r.req<UUID>("origin") to DescendantTotals(r.req("spent"), r.req("planned"), r.req("sub_items"), r.req("sub_items_done"))
        }.flow().toList().toMap()
    }

    private fun bindFields(spec: DatabaseClient.GenericExecuteSpec, f: ItemFields) = spec
        .bind("title", f.title)
        .bind("notes", f.notes)
        .bindNullable("byDate", f.byDate, LocalDate::class.java)
        .bindNullable("byTime", f.byTime, LocalTime::class.java)
        .bindNullable("atStart", f.atStart, Instant::class.java)
        .bindNullable("atEnd", f.atEnd, Instant::class.java)
        .bindNullable("atStartDate", f.atStartDate, LocalDate::class.java)
        .bindNullable("atEndDate", f.atEndDate, LocalDate::class.java)
        .bindNullable("parentId", f.parentId, UUID::class.java)
        .bindNullable("mainTagId", f.mainTagId, UUID::class.java)
        .bind("tagIds", f.tagIds.toTypedArray())
        .bindNullable("doneAt", f.doneAt, Instant::class.java)
        .bind("sourceType", f.sourceType.wire)
        .bindNullable("sourceRef", f.sourceRef, String::class.java)
        .bindNullable("sourceUrl", f.sourceUrl, String::class.java)
        .bindNullable("seriesId", f.seriesId, UUID::class.java)
        .bindNullable("occurrenceDate", f.occurrenceDate, LocalDate::class.java)
        .bind("detached", f.detached)

    companion object {
        /** The instant a deadline passes in zone `:zone`. Date-only deadlines pass at the start of the next day. */
        private const val DEADLINE_SQL = "((by_date + coalesce(by_time, time '24:00')) at time zone :zone)"

        private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        fun toItem(r: Readable) = Item(
            id = r.req("id"),
            fields = ItemFields(
                title = r.req("title"),
                notes = r.req("notes"),
                byDate = r.opt("by_date"),
                byTime = r.opt("by_time"),
                atStart = r.opt("at_start"),
                atEnd = r.opt("at_end"),
                atStartDate = r.opt("at_start_date"),
                atEndDate = r.opt("at_end_date"),
                parentId = r.opt("parent_id"),
                mainTagId = r.opt("main_tag_id"),
                tagIds = r.uuids("tag_ids"),
                doneAt = r.opt("done_at"),
                sourceType = SourceType.fromWire(r.req("source_type")),
                sourceRef = r.opt("source_ref"),
                sourceUrl = r.opt("source_url"),
                seriesId = r.opt("series_id"),
                occurrenceDate = r.opt("occurrence_date"),
                detached = r.req("detached"),
            ),
            version = r.req("version"),
            seq = r.req("seq"),
            createdAt = r.req("created_at"),
            updatedAt = r.req("updated_at"),
            deletedAt = r.opt("deleted_at"),
        )
    }
}
