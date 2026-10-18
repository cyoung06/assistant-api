package com.syeyoung.assistant.source

import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.source.SourceType
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant

data class SourceStatusRow(
    val lastSyncAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastError: String?,
    val lastReport: SyncReport?,
)

@Repository
class SourceRepository(private val db: DatabaseClient, private val mapper: ObjectMapper) {

    suspend fun status(type: SourceType): SourceStatusRow? =
        db.sql("select * from sources where type = :type").bind("type", type.wire).map { r ->
            SourceStatusRow(
                lastSyncAt = r.opt("last_sync_at"),
                lastSuccessAt = r.opt("last_success_at"),
                lastError = r.opt("last_error"),
                lastReport = r.opt<Json>("last_report")?.let { mapper.readValue(it.asString(), SyncReport::class.java) },
            )
        }.awaitOneOrNull()

    suspend fun recordSuccess(type: SourceType, at: Instant, report: SyncReport) {
        db.sql(
            """
            insert into sources (type, last_sync_at, last_success_at, last_error, last_report)
            values (:type, :at, :at, null, :report)
            on conflict (type) do update set last_sync_at = :at, last_success_at = :at, last_error = null, last_report = :report
            """,
        ).bind("type", type.wire).bind("at", at).bind("report", Json.of(mapper.writeValueAsString(report)))
            .fetch().awaitRowsUpdated()
    }

    suspend fun recordFailure(type: SourceType, at: Instant, error: String) {
        db.sql(
            """
            insert into sources (type, last_sync_at, last_error) values (:type, :at, :error)
            on conflict (type) do update set last_sync_at = :at, last_error = :error
            """,
        ).bind("type", type.wire).bind("at", at).bind("error", error.take(1000)).fetch().awaitRowsUpdated()
    }

    /** UID of every record this source has ever written, by its reference. */
    suspend fun refs(type: SourceType): Map<String, String> =
        db.sql("select ref, uid from source_refs where source_type = :type").bind("type", type.wire)
            .map { r -> r.req<String>("ref") to r.req<String>("uid") }.flow().toList().toMap()

    suspend fun rememberRef(type: SourceType, ref: String, uid: String) {
        db.sql("insert into source_refs (source_type, ref, uid) values (:type, :ref, :uid) on conflict do nothing")
            .bind("type", type.wire).bind("ref", ref).bind("uid", uid).fetch().awaitRowsUpdated()
    }

    suspend fun mappedCalendar(type: SourceType, key: String): String? =
        db.sql("select calendar_id from source_calendars where source_type = :type and external_key = :key")
            .bind("type", type.wire).bind("key", key).map { r -> r.req<String>("calendar_id") }.awaitOneOrNull()

    suspend fun mapCalendar(type: SourceType, key: String, calendarId: String) {
        db.sql(
            """
            insert into source_calendars (source_type, external_key, calendar_id) values (:type, :key, :cal)
            on conflict (source_type, external_key) do update set calendar_id = :cal
            """,
        ).bind("type", type.wire).bind("key", key).bind("cal", calendarId).fetch().awaitRowsUpdated()
    }
}
