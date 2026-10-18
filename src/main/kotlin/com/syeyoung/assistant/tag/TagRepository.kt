package com.syeyoung.assistant.tag

import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class TagRepository(private val db: DatabaseClient) {

    suspend fun findAll(): List<Tag> =
        db.sql("select * from tags where deleted_at is null order by lower(name)")
            .map(::toTag).flow().toList()

    suspend fun find(id: UUID, forUpdate: Boolean = false): Tag? =
        db.sql("select * from tags where id = :id and deleted_at is null" + if (forUpdate) " for update" else "")
            .bind("id", id).map(::toTag).awaitOneOrNull()

    suspend fun findAllIncludingDeleted(ids: Collection<UUID>): List<Tag> {
        if (ids.isEmpty()) return emptyList()
        return db.sql("select * from tags where id = any(:ids)")
            .bind("ids", ids.toTypedArray()).map(::toTag).flow().toList()
    }

    /** IDs among [ids] that exist and are not deleted. */
    suspend fun existing(ids: Collection<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return db.sql("select id from tags where id = any(:ids) and deleted_at is null")
            .bind("ids", ids.toTypedArray()).map { r -> r.req<UUID>("id") }.flow().toList().toSet()
    }

    suspend fun nameTaken(name: String, exceptId: UUID?): Boolean =
        db.sql("select 1 from tags where lower(name) = lower(:name) and deleted_at is null and id <> :except")
            .bind("name", name)
            .bind("except", exceptId ?: UUID(0, 0))
            .map { _ -> 1 }.awaitOneOrNull() != null

    suspend fun insert(id: UUID, name: String, color: String, now: Instant): Tag =
        db.sql(
            """
            insert into tags (id, name, color, created_at, updated_at)
            values (:id, :name, :color, :now, :now)
            returning *
            """,
        ).bind("id", id).bind("name", name).bind("color", color).bind("now", now)
            .map(::toTag).awaitOneOrNull()!!

    suspend fun update(id: UUID, name: String, color: String, now: Instant): Tag =
        db.sql(
            """
            update tags set name = :name, color = :color, updated_at = :now,
                version = version + 1, seq = nextval('change_seq')
            where id = :id
            returning *
            """,
        ).bind("id", id).bind("name", name).bind("color", color).bind("now", now)
            .map(::toTag).awaitOneOrNull()!!

    suspend fun softDelete(id: UUID, now: Instant) {
        db.sql(
            """
            update tags set deleted_at = :now, updated_at = :now,
                version = version + 1, seq = nextval('change_seq')
            where id = :id
            """,
        ).bind("id", id).bind("now", now).fetch().awaitRowsUpdated()
    }

    /** Removes a tag from every item that uses it; each touched item counts as changed. */
    suspend fun detachFromItems(id: UUID, now: Instant) {
        db.sql(
            """
            update items set
                tag_ids = array_remove(tag_ids, :id),
                main_tag_id = case when main_tag_id = :id then null else main_tag_id end,
                updated_at = :now, version = version + 1, seq = nextval('change_seq')
            where deleted_at is null and (main_tag_id = :id or :id = any(tag_ids))
            """,
        ).bind("id", id).bind("now", now).fetch().awaitRowsUpdated()
    }

    companion object {
        fun toTag(r: Readable) = Tag(
            id = r.req("id"),
            name = r.req("name"),
            color = r.req("color"),
            version = r.req("version"),
            seq = r.req("seq"),
            createdAt = r.req("created_at"),
            updatedAt = r.req("updated_at"),
            deletedAt = r.opt("deleted_at"),
        )
    }
}
