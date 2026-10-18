package com.syeyoung.assistant.source

import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.item.Item
import com.syeyoung.assistant.item.ItemRepository
import com.syeyoung.assistant.item.SourceType
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

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

    /** The tag mapped to [externalKey], if any (the tag may since have been deleted). */
    suspend fun mappedTag(type: SourceType, externalKey: String): UUID? =
        db.sql("select tag_id from source_tags where source_type = :type and external_key = :key")
            .bind("type", type.wire).bind("key", externalKey)
            .map { r -> r.req<UUID>("tag_id") }.awaitOneOrNull()

    suspend fun mapTag(type: SourceType, externalKey: String, tagId: UUID) {
        db.sql(
            """
            insert into source_tags (source_type, external_key, tag_id) values (:type, :key, :tag)
            on conflict (source_type, external_key) do update set tag_id = :tag
            """,
        ).bind("type", type.wire).bind("key", externalKey).bind("tag", tagId).fetch().awaitRowsUpdated()
    }

    /** The item for [ref], including a deleted one (so a deleted item is not recreated). */
    suspend fun itemByRef(type: SourceType, ref: String): Item? =
        db.sql("select * from items where source_type = :type and source_ref = :ref order by deleted_at nulls first limit 1")
            .bind("type", type.wire).bind("ref", ref)
            .map(ItemRepository::toItem).awaitOneOrNull()

    /** Live items from this source. */
    suspend fun liveItems(type: SourceType): List<Item> =
        db.sql("select * from items where source_type = :type and deleted_at is null")
            .bind("type", type.wire).map(ItemRepository::toItem).flow().toList()

    /** Live, not-done items from this source. */
    suspend fun openItems(type: SourceType): List<Item> =
        db.sql("select * from items where source_type = :type and deleted_at is null and done_at is null")
            .bind("type", type.wire).map(ItemRepository::toItem).flow().toList()
}
