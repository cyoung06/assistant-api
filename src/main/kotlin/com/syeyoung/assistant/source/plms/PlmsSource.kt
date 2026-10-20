package com.syeyoung.assistant.source.plms

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.source.SourceType
import com.syeyoung.assistant.source.DataSource
import com.syeyoung.assistant.source.SourceRecord
import com.syeyoung.assistant.source.SourceSnapshot
import com.syeyoung.assistant.source.SourceTag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * PLMS (POSTECH's Moodle): every assignment, quiz and other deadline on your PLMS to-do list
 * becomes a task due at the PLMS deadline, in its course's project and labeled by type; an
 * assignment's task carries its instructions and attached files. Once
 * something is submitted it leaves the to-do list, and the task is marked done.
 */
@Component
class PlmsSource(
    private val props: AppProperties,
    private val mapper: ObjectMapper,
    private val db: org.springframework.r2dbc.core.DatabaseClient,
    private val web: ObjectProvider<WebClient.Builder>,
    private val connectors: ClientHttpConnectorBuilder<*>,
    private val settings: HttpClientSettings,
) : DataSource {
    private val config = props.sources.plms
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    private val zone = props.zone

    override val type = SourceType.PLMS
    override val displayName = "PLMS"
    override val enabled = config.enabled && config.username.isNotBlank() && config.password.isNotBlank()
    override val interval: Duration = config.interval

    val client by lazy {
        PlmsClient(
            config.baseUrl.trimEnd('/'), config.username, config.password, mapper, loginFailures,
            session = web.getObject().clientConnector(connectors.build(settings.withRedirects(HttpRedirects.DONT_FOLLOW))).build(),
            files = web.getObject().build(),
        )
    }

    /** The last failed login, as "<epoch millis> <credentials hash>" in settings. */
    private val loginFailures = object : PlmsClient.LoginFailures {
        override suspend fun last() = db.sql("select value from settings where key = 'plms.login_failed'").map { r -> r.get("value", String::class.java)!! }
            .awaitOneOrNull()?.split(' ', limit = 2)?.takeIf { it.size == 2 }?.let { (at, with) -> Instant.ofEpochMilli(at.toLong()) to with }

        override suspend fun failed(at: Instant, credentials: String) {
            db.sql("insert into settings (key, value) values ('plms.login_failed', :v) on conflict (key) do update set value = :v")
                .bind("v", "${at.toEpochMilli()} $credentials").fetch().awaitRowsUpdated()
        }

        override suspend fun cleared() {
            db.sql("delete from settings where key = 'plms.login_failed'").fetch().awaitRowsUpdated()
        }
    }

    override suspend fun fetch(now: Instant): SourceSnapshot {
        val from = now.minus(config.lookback)
        val events = client.actionEvents(from, now.plus(config.lookahead))
        val details = runCatching { assignmentNotes(events) }.onFailure { log.warn("PLMS assignment descriptions: {}", it.toString()) }.getOrDefault(emptyMap())
        // Deadlines in the window that are no longer listed were submitted (or removed): done.
        return SourceSnapshot(events.map { e -> toRecord(e, zone, details[e.path("instance").asLong(0)].takeIf { e.path("modulename").asString("") == "assign" }) }, completeMissingDueFrom = from)
    }

    /**
     * Each assignment's description, for its task: the instructions as text, then its attached
     * files, linked to their copies in the course's folder (shown in the app, once copied there), else to PLMS.
     */
    private suspend fun assignmentNotes(events: List<JsonNode>): Map<Long, String> {
        val assignments = events.filter { it.path("modulename").asString("") == "assign" }
        if (assignments.isEmpty()) return emptyMap()
        val copies = db.sql("select key, path from plms_files where key like 'assign/%'").map { r -> r.get("key", String::class.java)!! to r.get("path", String::class.java)!! }
            .flow().toList().toMap()
        val base = props.publicUrl.trimEnd('/')
        val notes = mutableMapOf<Long, String>()
        for (e in assignments) {
            val cmid = cmidOf(e) ?: continue
            val a = runCatching { client.assignment(cmid) }.onFailure { log.warn("PLMS assignment {}: {}", cmid, it.toString()) }.getOrNull() ?: continue
            val files = a.attachments.map { f ->
                val link = copies[PlmsFiles.assignmentKey(cmid, f.name)]?.let { path -> "$base/files/" + path.removePrefix("Projects/").split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") } }
                    ?: f.url
                "- [${f.name.replace("[", "\\[").replace("]", "\\]")}]($link)"
            }
            val body = listOfNotNull(htmlToMarkdown(a.introHtml).ifBlank { null }, files.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "Files:\n")).joinToString("\n\n")
            if (body.isNotBlank()) notes[e.path("instance").asLong()] = body
        }
        log.info("PLMS assignment descriptions: {} of {} assignments", notes.size, assignments.size)
        return notes
    }

    companion object {
        /** "확률및통계 (2026-092, 01400415_01660701)" → "확률및통계". Merged sections end up with the same name. */
        fun courseName(fullName: String): String =
            fullName.replace(COURSE_SUFFIX, "").trim().ifEmpty { fullName.trim() }

        /**
         * The semester a course starting on [start] belongs to, as POSTECH's calendar runs: spring from
         * February, summer session from June, fall from August, winter session from December.
         */
        fun semester(start: java.time.LocalDate): String = when (start.monthValue) {
            in 2..5 -> "${start.year} Spring"
            6, 7 -> "${start.year} Summer"
            in 8..11 -> "${start.year} Fall"
            12 -> "${start.year} Winter"
            else -> "${start.year - 1} Winter"
        }

        /** "HW6 is due" → "HW6". */
        fun cleanTitle(name: String): String = name.replace(TITLE_SUFFIX, "").trim().ifEmpty { name.trim() }

        /** Moodle's HTML (an assignment's instructions) as Markdown-ish text: paragraphs, line breaks, lists and links kept. */
        fun htmlToMarkdown(html: String): String {
            var t = html.replace(Regex("""\s*\n\s*"""), " ")
            t = t.replace(Regex("""<a\s[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)) { m ->
                val label = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                if (label.isBlank()) m.groupValues[1] else "[$label](${m.groupValues[1]})"
            }
            t = t.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("""<li[^>]*>""", RegexOption.IGNORE_CASE), "\n- ")
                .replace(Regex("""</(p|div|h\d|ul|ol|table|tr)>""", RegexOption.IGNORE_CASE), "\n\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            return t.lines().joinToString("\n") { it.trim() }.replace(Regex("""\n{3,}"""), "\n\n").trim()
        }

        /** The course module id of an event's activity (its link is …/view.php?id=<cmid>). */
        fun cmidOf(event: JsonNode): Long? =
            Regex("""[?&]id=(\d+)""").find(event.path("url").asString(""))?.groupValues?.get(1)?.toLongOrNull()

        fun toRecord(event: JsonNode, zone: ZoneId, notes: String? = null): SourceRecord {
            val module = event.path("modulename").asString("")
            val instance = event.path("instance").asLong(0)
            val due = Instant.ofEpochSecond(event.path("timesort").asLong()).atZone(zone)
            val course = event.path("course")
            val project = if (!course.isMissingNode && course.has("id")) {
                SourceTag("course:${course.path("id").asLong()}", courseName(course.path("fullname").asString("")))
            } else null
            return SourceRecord(
                ref = if (module.isNotEmpty() && instance > 0) "$module:$instance" else "event:${event.path("id").asLong()}",
                title = cleanTitle(event.path("name").asString("")),
                dueDate = due.toLocalDate(),
                dueTime = due.toLocalTime().withSecond(0).withNano(0),
                url = event.path("url").asString(null)?.takeIf { it.isNotBlank() }
                    ?: event.path("action").path("url").asString(null),
                tags = listOfNotNull(MODULE_TAGS[module]?.let { SourceTag("module:$module", it) }),
                project = project,
                notes = notes,
            )
        }

        private val COURSE_SUFFIX = Regex("""\s*\(\d{4}-\d{3},.*$""", RegexOption.DOT_MATCHES_ALL)
        private val TITLE_SUFFIX = Regex("""\s+(is due|closes|is due to be graded|should be completed)$""")
        private val MODULE_TAGS = mapOf("assign" to "assignment", "quiz" to "quiz")
    }
}
