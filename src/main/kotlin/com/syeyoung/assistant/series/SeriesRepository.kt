package com.syeyoung.assistant.series

import com.syeyoung.assistant.common.bindNullable
import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.common.uuids
import com.syeyoung.assistant.item.Item
import com.syeyoung.assistant.item.ItemRepository
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Repository
class SeriesRepository(private val db: DatabaseClient) {

    suspend fun find(id: UUID, forUpdate: Boolean = false): Series? =
        db.sql("select * from series where id = :id and deleted_at is null" + if (forUpdate) " for update" else "")
            .bind("id", id).map(::toSeries).awaitOneOrNull()

    suspend fun findAllIncludingDeleted(ids: Collection<UUID>): List<Series> {
        if (ids.isEmpty()) return emptyList()
        return db.sql("select * from series where id = any(:ids)").bind("ids", ids.toTypedArray()).map(::toSeries).flow().toList()
    }

    /** Live series whose occurrences haven't been generated up to [date] yet. */
    suspend fun needingGenerationThrough(date: LocalDate): List<Series> =
        db.sql(
            """
            select * from series where deleted_at is null
              and (generated_until is null or generated_until < :date)
              and (until_date is null or generated_until is null or generated_until < until_date)
            """,
        ).bind("date", date).map(::toSeries).flow().toList()

    suspend fun insert(s: Series, now: Instant): Series =
        bind(
            db.sql(
                """
                insert into series (id, title, notes, main_tag_id, tag_ids, all_day, start_time, duration_minutes, span_days,
                    freq, interval, weekdays, start_date, until_date, count, generated_until, created_at, updated_at)
                values (:id, :title, :notes, :mainTagId, :tagIds, :allDay, :startTime, :durationMinutes, :spanDays,
                    :freq, :interval, :weekdays, :startDate, :untilDate, :count, :generatedUntil, :now, :now)
                returning *
                """,
            ),
            s,
        ).bind("now", now).map(::toSeries).awaitOneOrNull()!!

    suspend fun update(s: Series, now: Instant): Series =
        bind(
            db.sql(
                """
                update series set title = :title, notes = :notes, main_tag_id = :mainTagId, tag_ids = :tagIds,
                    all_day = :allDay, start_time = :startTime, duration_minutes = :durationMinutes, span_days = :spanDays,
                    freq = :freq, interval = :interval, weekdays = :weekdays, start_date = :startDate,
                    until_date = :untilDate, count = :count, generated_until = :generatedUntil,
                    updated_at = :now, version = version + 1, seq = nextval('change_seq')
                where id = :id
                returning *
                """,
            ),
            s,
        ).bind("now", now).map(::toSeries).awaitOneOrNull()!!

    suspend fun softDelete(id: UUID, now: Instant) {
        db.sql("update series set deleted_at = :now, updated_at = :now, version = version + 1, seq = nextval('change_seq') where id = :id")
            .bind("id", id).bind("now", now).fetch().awaitRowsUpdated()
    }

    /** Every occurrence item of a series by date, deleted ones included. */
    suspend fun occurrences(seriesId: UUID): Map<LocalDate, Item> =
        db.sql("select * from items where series_id = :id")
            .bind("id", seriesId).map(ItemRepository::toItem).flow().toList()
            .associateBy { it.fields.occurrenceDate!! }

    /** Live occurrences of a series on or after [from] (all of them when null). */
    suspend fun liveItems(seriesId: UUID, from: LocalDate? = null): List<Item> =
        db.sql(
            "select * from items where series_id = :id and deleted_at is null" +
                (if (from != null) " and occurrence_date >= :from" else "") + " order by occurrence_date",
        ).bind("id", seriesId).let { if (from != null) it.bind("from", from) else it }
            .map(ItemRepository::toItem).flow().toList()

    /** Moves every occurrence (deleted ones included) on or after [from] to another series. */
    suspend fun moveItems(fromSeries: UUID, toSeries: UUID, from: LocalDate, now: Instant) {
        db.sql(
            """
            update items set series_id = :to, updated_at = :now, version = version + 1, seq = nextval('change_seq')
            where series_id = :fromSeries and occurrence_date >= :from
            """,
        ).bind("to", toSeries).bind("fromSeries", fromSeries).bind("from", from).bind("now", now).fetch().awaitRowsUpdated()
    }

    private fun bind(spec: DatabaseClient.GenericExecuteSpec, s: Series) = spec
        .bind("id", s.id)
        .bind("title", s.template.title)
        .bind("notes", s.template.notes)
        .bindNullable("mainTagId", s.template.mainTagId, UUID::class.java)
        .bind("tagIds", s.template.tagIds.toTypedArray())
        .bind("allDay", s.template.allDay)
        .bindNullable("startTime", s.template.startTime, LocalTime::class.java)
        .bindNullable("durationMinutes", s.template.durationMinutes, Int::class.javaObjectType)
        .bindNullable("spanDays", s.template.spanDays, Int::class.javaObjectType)
        .bind("freq", s.rule.freq.wire)
        .bind("interval", s.rule.interval)
        .bind("weekdays", s.rule.weekdays.map { it.value.toShort() }.sorted().toTypedArray())
        .bind("startDate", s.startDate)
        .bindNullable("untilDate", s.rule.until, LocalDate::class.java)
        .bindNullable("count", s.rule.count, Int::class.javaObjectType)
        .bindNullable("generatedUntil", s.generatedUntil, LocalDate::class.java)

    companion object {
        fun toSeries(r: Readable) = Series(
            id = r.req("id"),
            template = SeriesTemplate(
                title = r.req("title"),
                notes = r.req("notes"),
                mainTagId = r.opt("main_tag_id"),
                tagIds = r.uuids("tag_ids"),
                allDay = r.req("all_day"),
                startTime = r.opt("start_time"),
                durationMinutes = r.opt("duration_minutes"),
                spanDays = r.opt("span_days"),
            ),
            rule = RepeatRule(
                freq = Freq.fromWire(r.req("freq")),
                interval = r.req("interval"),
                weekdays = (r.get("weekdays", Array<Short>::class.java) ?: emptyArray()).map { DayOfWeek.of(it.toInt()) }.toSet(),
                until = r.opt("until_date"),
                count = r.opt("count"),
            ),
            startDate = r.req("start_date"),
            generatedUntil = r.opt("generated_until"),
            version = r.req("version"),
            seq = r.req("seq"),
            createdAt = r.req("created_at"),
            updatedAt = r.req("updated_at"),
            deletedAt = r.opt("deleted_at"),
        )
    }
}
