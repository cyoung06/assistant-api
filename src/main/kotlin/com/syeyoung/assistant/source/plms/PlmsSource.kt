package com.syeyoung.assistant.source.plms

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.source.SourceType
import com.syeyoung.assistant.source.DataSource
import com.syeyoung.assistant.source.SourceRecord
import com.syeyoung.assistant.source.SourceSnapshot
import com.syeyoung.assistant.source.SourceTag
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * PLMS (POSTECH's Moodle): every assignment, quiz and other deadline on your PLMS to-do list
 * becomes a task due at the PLMS deadline, in its course's project and labeled by type. Once
 * something is submitted it leaves the to-do list, and the task is marked done.
 */
@Component
class PlmsSource(props: AppProperties, private val mapper: ObjectMapper) : DataSource {
    private val config = props.sources.plms
    private val zone = props.zone

    override val type = SourceType.PLMS
    override val displayName = "PLMS"
    override val enabled = config.enabled && config.username.isNotBlank() && config.password.isNotBlank()
    override val interval: Duration = config.interval

    private val client by lazy { PlmsClient(config.baseUrl.trimEnd('/'), config.username, config.password, mapper) }

    override suspend fun fetch(now: Instant): SourceSnapshot {
        val from = now.minus(config.lookback)
        val events = client.actionEvents(from, now.plus(config.lookahead))
        // Deadlines in the window that are no longer listed were submitted (or removed): done.
        return SourceSnapshot(events.map { toRecord(it, zone) }, completeMissingDueFrom = from)
    }

    companion object {
        /** "확률및통계 (2026-092, 01400415_01660701)" → "확률및통계". Merged sections end up with the same name. */
        fun courseName(fullName: String): String =
            fullName.replace(COURSE_SUFFIX, "").trim().ifEmpty { fullName.trim() }

        /** "HW6 is due" → "HW6". */
        fun cleanTitle(name: String): String = name.replace(TITLE_SUFFIX, "").trim().ifEmpty { name.trim() }

        fun toRecord(event: JsonNode, zone: ZoneId): SourceRecord {
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
            )
        }

        private val COURSE_SUFFIX = Regex("""\s*\(\d{4}-\d{3},.*$""", RegexOption.DOT_MATCHES_ALL)
        private val TITLE_SUFFIX = Regex("""\s+(is due|closes|is due to be graded|should be completed)$""")
        private val MODULE_TAGS = mapOf("assign" to "assignment", "quiz" to "quiz")
    }
}
