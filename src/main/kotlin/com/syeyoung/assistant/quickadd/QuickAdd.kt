package com.syeyoung.assistant.quickadd

import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.workspace.DueDto
import kotlinx.coroutines.TimeoutCancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

data class QuickAddRequest(val text: String)

/**
 * A proposed task (has a deadline, or no time at all) or event (happens at a time). Nothing is saved
 * until the client POSTs it to /tasks or /events.
 */
data class QuickAddDraft(
    val kind: String,
    val title: String,
    val notes: String = "",
    val due: DueDto? = null,
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val categories: List<String> = emptyList(),
)

data class QuickAddResult(
    /** False when the LLM was unavailable or failed; [draft] then holds the raw text as the title. */
    val parsed: Boolean,
    val draft: QuickAddDraft,
)

@Service
class QuickAddService(
    private val extractor: ItemExtractor,
    private val ws: Workspace,
    private val clock: Clock,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun parse(text: String): QuickAddResult {
        val input = text.trim()
        if (input.isEmpty() || input.length > 2000) throw ValidationException("Text must be 1–2000 characters")
        val fallback = QuickAddResult(parsed = false, draft = QuickAddDraft(kind = "task", title = input))
        if (!extractor.configured) return fallback

        // Categories already in use (courses, "assignment", …) so the model reuses them.
        val known = ws.snapshot().let { s -> (s.tasks.flatMap { it.categories } + s.events.flatMap { it.categories }).distinct() }
        val draft = try {
            extractor.extract(instructions(known), input)
        } catch (e: TimeoutCancellationException) {
            log.warn("Quick add LLM call timed out")
            return fallback
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Quick add LLM call failed: {}", e.toString())
            return fallback
        }

        // Match the spelling of categories already in use.
        val categories = draft.tags.map { t -> known.find { it.equals(t, true) } ?: t }.distinct()
        val by = draft.by?.let(::toBy)
        val at = draft.at?.let(::toAt)
        val base = QuickAddDraft(kind = "task", title = draft.title.ifBlank { input }, notes = draft.notes, categories = categories)
        return QuickAddResult(
            parsed = true,
            draft = if (by == null && at != null) {
                base.copy(kind = "event", start = at.start, end = at.end, startDate = at.startDate, endDate = at.endDate)
            } else {
                base.copy(due = by?.let { DueDto(it.date, it.time) })
            },
        )
    }

    // The model's dates are checked here; anything unparseable is dropped rather than failing the whole parse.

    private data class ParsedBy(val date: LocalDate, val time: LocalTime?)

    private data class ParsedAt(val start: OffsetDateTime? = null, val end: OffsetDateTime? = null, val startDate: LocalDate? = null, val endDate: LocalDate? = null)

    private fun toBy(b: LlmBy): ParsedBy? {
        val date = b.date.toDateOrNull() ?: return null
        return ParsedBy(date, b.time?.toTimeOrNull())
    }

    private fun toAt(a: LlmAt): ParsedAt? {
        val zone = props.zone
        if (a.allDay) {
            val start = a.startDate?.toDateOrNull() ?: return null
            val end = a.endDate?.toDateOrNull()?.takeIf { it >= start } ?: start
            return ParsedAt(startDate = start, endDate = end)
        }
        val start = a.start?.toDateTimeOrNull() ?: return null
        // A start without a sensible end gets one hour.
        val end = a.end?.toDateTimeOrNull()?.takeIf { it.isAfter(start) } ?: start.plusHours(1)
        return ParsedAt(start = start.atZone(zone).toOffsetDateTime(), end = end.atZone(zone).toOffsetDateTime())
    }

    private fun String.toDateOrNull() = runCatching { LocalDate.parse(this) }.getOrNull()
    private fun String.toTimeOrNull() = runCatching { LocalTime.parse(this) }.getOrNull()
    private fun String.toDateTimeOrNull() = runCatching { LocalDateTime.parse(this) }.getOrNull()

    private fun instructions(tagNames: List<String>): String {
        val now = ZonedDateTime.now(clock)
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return """
            You turn one line of text from the user into a todo/calendar item. The text may be Korean, English or mixed.
            Now: $weekday ${now.toLocalDate()} ${now.toLocalTime().withNano(0)} (${props.zone}). Weeks start on Sunday.

            Fields:
            - title: short, in the user's language. Remove the date/time words that you moved into "by" or "at".
            - by: a deadline ("by", "due", "까지", "마감"). time is null when only a day is given.
            - at: when something happens or is scheduled ("at", "meeting", "exam", "시험", "회의", a time range).
              Use allDay with startDate/endDate when only days are given; otherwise start/end as local date-times.
              If no end is given, set end to null.
            - An item can have both by and at, or neither.
            - Resolve relative dates ("fri", "tomorrow", "다음주 월요일") to the next matching date on or after today.
            - tags: categories that clearly apply, preferring these existing ones (e.g. the course): ${tagNames.joinToString(", ").ifEmpty { "(none)" }}; plus short words like "assignment", "exam", "meeting" when obvious; otherwise []
            - notes: anything useful that doesn't fit the other fields, otherwise "".
        """.trimIndent()
    }
}

@RestController
@RequestMapping("/api/v1/quick-add")
class QuickAddController(private val service: QuickAddService) {
    /** Parses free text into a proposed item. Does not save anything. */
    @PostMapping("/parse")
    suspend fun parse(@RequestBody req: QuickAddRequest): QuickAddResult = service.parse(req.text)
}
