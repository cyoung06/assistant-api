package com.syeyoung.assistant.source.ical

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.ical.IcalEvent
import com.syeyoung.assistant.ical.IcalParser
import com.syeyoung.assistant.source.SourceType
import com.syeyoung.assistant.ical.Freq
import com.syeyoung.assistant.ical.Recurrence
import com.syeyoung.assistant.ical.RepeatRule
import com.syeyoung.assistant.source.DataSource
import com.syeyoung.assistant.source.SourceRecord
import com.syeyoung.assistant.source.SourceSnapshot
import com.syeyoung.assistant.source.SourceTag
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Subscribed iCal feeds (e.g. Korean public holidays). Each event becomes an item tagged with the
 * subscription's tag; the feed owns its items, so changed events update and removed ones are deleted.
 * Repeating events are expanded into one item per occurrence within the window.
 */
@Component
class IcalSource(props: AppProperties) : DataSource {
    private val config = props.sources.ical
    private val zone = props.zone

    override val type = SourceType.ICAL
    override val displayName = "Calendars"
    override val enabled = config.subscriptions.any { it.url.isNotBlank() }
    override val interval: Duration = config.interval

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()

    override suspend fun fetch(now: Instant): SourceSnapshot {
        val today = now.atZone(zone).toLocalDate()
        val from = today.minus(config.lookback)
        val to = today.plus(config.lookahead)
        val records = config.subscriptions.filter { it.url.isNotBlank() }.flatMap { sub ->
            val response = http.sendAsync(
                HttpRequest.newBuilder(URI(sub.url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "assistant-sync/1.0").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).await()
            if (response.statusCode() != 200) throw IllegalStateException("${sub.name}: feed returned HTTP ${response.statusCode()}")
            val tag = SourceTag("ical:${sub.key}", sub.tag)
            IcalParser.parse(response.body(), zone).flatMap { toRecords(sub.key, it, tag, from, to, zone) }
        }
        return SourceSnapshot(records, deleteMissing = true)
    }

    companion object {
        /** The event (or each of its occurrences in [from]..[to]) as records. Refs: "<key>:<uid>[#<date>]". */
        fun toRecords(key: String, event: IcalEvent, tag: SourceTag, from: LocalDate, to: LocalDate, zone: ZoneId): List<SourceRecord> {
            val firstDate = event.startDate ?: event.start!!.atZone(zone).toLocalDate()
            val rule = event.rrule?.let { parseRule(it, zone) }
            val dates = if (rule == null) {
                listOf(firstDate)
            } else {
                Recurrence.dates(rule, firstDate, from, to).filter { it !in event.exdates }
            }
            return dates.filter { it in from..to }.map { date ->
                val shift = Duration.between(firstDate.atStartOfDay(), date.atStartOfDay()).toDays()
                SourceRecord(
                    ref = "$key:${event.uid}" + if (rule != null) "#$date" else "",
                    title = event.summary.ifBlank { "Untitled" },
                    atStartDate = event.startDate?.plusDays(shift),
                    // iCalendar all-day ends are exclusive; ours are inclusive.
                    atEndDate = event.endDate?.plusDays(shift - 1),
                    atStart = event.start?.atZone(zone)?.plusDays(shift)?.toInstant(),
                    atEnd = event.end?.atZone(zone)?.plusDays(shift)?.toInstant(),
                    notes = event.description,
                    url = event.url,
                    tags = listOf(tag),
                )
            }
        }

        /** The RRULE subset our recurrence supports; null for anything else (only the first occurrence is kept). */
        fun parseRule(rrule: String, zone: ZoneId): RepeatRule? {
            val parts = rrule.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()
            val freq = when (parts["FREQ"]?.uppercase()) {
                "DAILY" -> Freq.DAILY
                "WEEKLY" -> Freq.WEEKLY
                "MONTHLY" -> Freq.MONTHLY
                // Yearly repeats (birthdays, anniversaries) as every 12 months.
                "YEARLY" -> Freq.MONTHLY
                else -> return null
            }
            val supported = setOf("FREQ", "INTERVAL", "BYDAY", "UNTIL", "COUNT", "WKST")
            if (parts.keys.any { it !in supported }) return null
            val days = parts["BYDAY"]?.split(',')?.map { code ->
                // Positional forms like "1MO" (first Monday) aren't supported.
                DAYS[code.uppercase()] ?: return null
            }.orEmpty()
            if (days.isNotEmpty() && freq != Freq.WEEKLY) return null
            val interval = (parts["INTERVAL"]?.toIntOrNull() ?: 1) * if (parts["FREQ"].equals("YEARLY", true)) 12 else 1
            val until = parts["UNTIL"]?.let { u ->
                if (u.length == 8) LocalDate.parse(u, DateTimeFormatter.BASIC_ISO_DATE)
                else LocalDateTime.parse(u.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate()
            }
            if (interval !in 1..99) return null
            return RepeatRule(freq, interval, days.toSet(), until, parts["COUNT"]?.toIntOrNull())
        }

        private val DAYS = mapOf(
            "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
            "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
        )
    }
}
