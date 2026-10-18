package com.syeyoung.assistant.refs

import com.syeyoung.assistant.common.bindNullable
import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Titles and links of things outside the app (PLMS files, notices, …), replaced per source on each refresh. */
@Component
class RefIndex(private val db: DatabaseClient) : RefProvider {

    @Transactional
    suspend fun replace(source: String, refs: List<Ref>, now: Instant) {
        db.sql("delete from ref_index where source = :source").bind("source", source).fetch().awaitRowsUpdated()
        for (r in refs.distinctBy { it.url }) {
            db.sql(
                """
                insert into ref_index (url, kind, title, context, date, source, updated_at)
                values (:url, :kind, :title, :context, :date, :source, :now)
                on conflict (url) do update set kind = :kind, title = :title, context = :context, date = :date, source = :source, updated_at = :now
                """,
            ).bind("url", r.url).bind("kind", r.kind.wire).bind("title", r.title)
                .bindNullable("context", r.context, String::class.java).bindNullable("date", r.date, Instant::class.java)
                .bind("source", source).bind("now", now).fetch().awaitRowsUpdated()
        }
    }

    override suspend fun search(query: String, limit: Int): List<Ref> =
        db.sql(
            """
            select * from ref_index
            where title ilike :q or context ilike :q
            order by (title ilike :prefix) desc, date desc nulls last
            limit :limit
            """,
        ).bind("q", "%${escape(query)}%").bind("prefix", "${escape(query)}%").bind("limit", limit)
            .map(::ref).flow().toList()

    override suspend fun resolve(url: String): Ref? =
        db.sql("select * from ref_index where url = :url").bind("url", url).map(::ref).awaitOneOrNull()

    private fun ref(r: Readable) = Ref(RefKind.of(r.req("kind")), r.req("title"), r.req("url"), r.opt("context"), r.opt("date"))

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
