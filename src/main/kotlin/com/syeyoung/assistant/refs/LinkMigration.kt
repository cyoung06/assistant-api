package com.syeyoung.assistant.refs

import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.source.SourceRepository
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Brings links written in older forms up to date, wherever text is kept: `…/#task/<id>` becomes
 * `<base>/task/<id>` (projects and events likewise), `…/#mail/<id>` becomes `mid:<id>`, and PLMS
 * pages become `plms:` links. Runs at start-up; text already in the current form is left alone.
 */
@Component
class LinkMigration(
    private val db: DatabaseClient,
    private val ws: Workspace,
    private val sources: SourceRepository,
    props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val base = props.publicUrl
    private val plmsBase = props.sources.plms.baseUrl

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        scope.launch { runCatching { migrate() }.onFailure { log.warn("Link migration failed: {}", it.toString()) } }
    }

    /** Rewrites old links everywhere; returns how many texts changed. */
    suspend fun migrate(): Int {
        var changed = 0
        changed += rewriteRows("tasks", listOf("title", "description"))
        changed += rewriteRows("projects", listOf("description"))
        changed += rewriteRows("initiatives", listOf("description"))
        val fixed = sources.sourceCalendarIds()
        for (e in ws.snapshot().events.filter { it.calendarId !in fixed }) {
            val title = rewrite(e.title)
            val notes = rewrite(e.notes)
            if (title == e.title && notes == e.notes) continue
            runCatching { ws.updateEvent(e) { it.copy(title = title, notes = notes) } }.onSuccess { changed++ }
                .onFailure { log.warn("Couldn't update links in event {}: {}", e.uid, it.toString()) }
        }
        val oldPlms = db.sql("select url from ref_index where url like 'http%'").map { r -> r.req<String>("url") }.flow().toList()
        for (url in oldPlms) {
            val link = PlmsLinks.fromWeb(url, plmsBase)
            if (link == url) continue
            db.sql("delete from ref_index where url = :new").bind("new", link).fetch().awaitRowsUpdated()
            db.sql("update ref_index set url = :new where url = :old").bind("new", link).bind("old", url).fetch().awaitRowsUpdated()
        }
        if (changed > 0) log.info("Updated links in {} texts", changed)
        return changed
    }

    private suspend fun rewriteRows(table: String, columns: List<String>): Int {
        var changed = 0
        val rows = db.sql("select id, ${columns.joinToString()} from $table").map { r -> r.req<UUID>("id") to columns.map { r.get(it, String::class.java).orEmpty() } }
            .flow().toList()
        for ((id, values) in rows) {
            val updated = values.map(::rewrite)
            if (updated == values) continue
            val spec = db.sql("update $table set ${columns.joinToString { "$it = :$it" }} where id = :id").bind("id", id)
            columns.zip(updated).fold(spec) { s, (c, v) -> s.bind(c, v) }.fetch().awaitRowsUpdated()
            changed++
        }
        return changed
    }

    /** Every Markdown link target in [text] in its current form. */
    fun rewrite(text: String): String = LINK_TARGET.replace(text) { m ->
        val url = m.groupValues[1]
        val target = AppLinks.parse(url)
        val current = when {
            url.startsWith("mid:") || url.startsWith("plms:") -> url
            target != null && "#" in url -> when (target.kind) {
                RefKind.TASK -> AppLinks.task(base, target.id)
                RefKind.PROJECT -> AppLinks.project(base, target.id)
                RefKind.EVENT -> AppLinks.event(base, target.id)
                else -> AppLinks.mail(target.id)
            }
            else -> PlmsLinks.fromWeb(url, plmsBase)
        }
        "](" + current + ")"
    }

    @PreDestroy
    fun stop() = scope.cancel()

    private companion object {
        val LINK_TARGET = Regex("""]\(((?:[^()\s]|\([^()\s]*\))+)\)""")
    }
}
