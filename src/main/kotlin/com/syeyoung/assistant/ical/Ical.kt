package com.syeyoung.assistant.ical

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One calendar event, as written to or read from an iCalendar (RFC 5545) file. */
data class IcalEvent(
    val uid: String,
    val summary: String,
    val description: String = "",
    val url: String? = null,
    /** Timed events. */
    val start: Instant? = null,
    val end: Instant? = null,
    /** All-day events; [endDate] is exclusive, as in iCalendar. */
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val stamp: Instant? = null,
    /** Raw RRULE value, if the event repeats. */
    val rrule: String? = null,
    val exdates: List<LocalDate> = emptyList(),
) {
    val allDay get() = startDate != null
}

object IcalWriter {
    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE = DateTimeFormatter.BASIC_ISO_DATE

    fun write(calendarName: String, events: List<IcalEvent>): String {
        val out = StringBuilder()
        fun line(text: String) = fold(text, out)
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//assistant//calendar feed//EN")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        line("X-WR-CALNAME:${escape(calendarName)}")
        line("X-WR-TIMEZONE:Asia/Seoul")
        // Calendar apps refresh subscriptions on this hint (where they honor it).
        line("REFRESH-INTERVAL;VALUE=DURATION:PT15M")
        line("X-PUBLISHED-TTL:PT15M")
        for (e in events) {
            line("BEGIN:VEVENT")
            line("UID:${escape(e.uid)}")
            line("DTSTAMP:${UTC.format(e.stamp ?: Instant.EPOCH)}")
            if (e.allDay) {
                line("DTSTART;VALUE=DATE:${DATE.format(e.startDate)}")
                line("DTEND;VALUE=DATE:${DATE.format(e.endDate ?: e.startDate!!.plusDays(1))}")
            } else {
                line("DTSTART:${UTC.format(e.start)}")
                line("DTEND:${UTC.format(e.end ?: e.start)}")
            }
            line("SUMMARY:${escape(e.summary)}")
            if (e.description.isNotBlank()) line("DESCRIPTION:${escape(e.description)}")
            e.url?.let { line("URL:${it}") }
            line("END:VEVENT")
        }
        line("END:VCALENDAR")
        return out.toString()
    }

    /** RFC 5545 text escaping. */
    fun escape(text: String) = text
        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace("\r\n", "\\n").replace("\n", "\\n")

    /** Writes [text] as a content line folded at 75 octets (never splitting a UTF-8 character), CRLF-terminated. */
    private fun fold(text: String, out: StringBuilder) {
        var used = 0
        var limit = 75
        // Code points, so a character outside the BMP (emoji) is never split across a fold.
        text.codePoints().forEach { cp ->
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + size > limit) {
                out.append("\r\n ")
                used = 0
                limit = 74 // the leading space counts
            }
            out.appendCodePoint(cp)
            used += size
        }
        out.append("\r\n")
    }
}

object IcalParser {
    private val DATE = DateTimeFormatter.BASIC_ISO_DATE
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private data class Prop(val name: String, val params: Map<String, String>, val value: String)

    /** Every VEVENT in [text]. Times without a zone are read in [defaultZone]. Malformed events are skipped. */
    fun parse(text: String, defaultZone: ZoneId): List<IcalEvent> {
        val lines = unfold(text)
        val events = mutableListOf<IcalEvent>()
        var props: MutableList<Prop>? = null
        var depth = 0
        for (raw in lines) {
            val p = parseLine(raw) ?: continue
            when {
                p.name == "BEGIN" && p.value.equals("VEVENT", true) -> { props = mutableListOf(); depth = 0 }
                // Nested components (VALARM) must not leak their properties into the event.
                p.name == "BEGIN" && props != null -> depth++
                p.name == "END" && p.value.equals("VEVENT", true) && props != null -> {
                    runCatching { toEvent(props, defaultZone) }.getOrNull()?.let(events::add)
                    props = null
                }
                p.name == "END" && props != null -> depth--
                props != null && depth == 0 -> props += p
            }
        }
        return events
    }

    private fun toEvent(props: List<Prop>, zone: ZoneId): IcalEvent? {
        fun first(name: String) = props.firstOrNull { it.name == name }
        val start = first("DTSTART") ?: return null
        val uid = first("UID")?.value ?: return null
        val summary = unescape(first("SUMMARY")?.value ?: "")
        val exdates = props.filter { it.name == "EXDATE" }.flatMap { p -> p.value.split(',').mapNotNull { v -> dateOf(v, p.params, zone) } }
        val common = IcalEvent(
            uid = uid,
            summary = summary,
            description = unescape(first("DESCRIPTION")?.value ?: ""),
            url = first("URL")?.value,
            rrule = first("RRULE")?.value,
            exdates = exdates,
        )
        val isDate = start.params["VALUE"].equals("DATE", true) || start.value.length == 8
        if (isDate) {
            val s = LocalDate.parse(start.value.take(8), DATE)
            val e = first("DTEND")?.let { LocalDate.parse(it.value.take(8), DATE) } ?: s.plusDays(1)
            return common.copy(startDate = s, endDate = if (e > s) e else s.plusDays(1))
        }
        val s = instantOf(start.value, start.params, zone) ?: return null
        val e = first("DTEND")?.let { instantOf(it.value, it.params, zone) }
            ?: first("DURATION")?.let { runCatching { s.plus(java.time.Duration.parse(it.value)) }.getOrNull() }
            ?: s
        return common.copy(start = s, end = if (e.isAfter(s)) e else s)
    }

    private fun instantOf(value: String, params: Map<String, String>, zone: ZoneId): Instant? = runCatching {
        if (value.endsWith("Z")) return@runCatching LocalDateTime.parse(value.dropLast(1), LOCAL).toInstant(ZoneOffset.UTC)
        val tz = params["TZID"]?.let { runCatching { ZoneId.of(it.trim('"')) }.getOrNull() } ?: zone
        LocalDateTime.parse(value, LOCAL).atZone(tz).toInstant()
    }.getOrNull()

    private fun dateOf(value: String, params: Map<String, String>, zone: ZoneId): LocalDate? =
        if (value.length == 8) runCatching { LocalDate.parse(value, DATE) }.getOrNull()
        else instantOf(value, params, zone)?.atZone(zone)?.toLocalDate()

    private fun unfold(text: String): List<String> {
        val result = mutableListOf<String>()
        for (line in text.split("\r\n", "\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && result.isNotEmpty()) {
                result[result.lastIndex] = result.last() + line.substring(1)
            } else if (line.isNotEmpty()) {
                result += line
            }
        }
        return result
    }

    private fun parseLine(line: String): Prop? {
        // The value starts at the first ':' that isn't inside a quoted parameter value.
        var quoted = false
        var colon = -1
        for ((i, c) in line.withIndex()) {
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) { colon = i; break }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                out.append(
                    when (text[i + 1]) {
                        'n', 'N' -> '\n'
                        else -> text[i + 1]
                    },
                )
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }
}
