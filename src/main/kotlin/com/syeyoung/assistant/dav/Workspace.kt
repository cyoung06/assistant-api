package com.syeyoung.assistant.dav

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.refs.plainText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Period
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.StringReader
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal
import java.util.UUID

/** A calendar collection: one per source (Assistant, holidays, …), with its color. */
data class Cal(val id: String, val href: String, val name: String, val color: String?)

/** A VTODO DUE: a date, with a time unless all-day. */
data class Due(val date: LocalDate, val time: LocalTime?)

/** A VEVENT (the master, for repeating ones). */
data class Event(
    val uid: String,
    val href: String,
    val etag: String?,
    val calendarId: String,
    val title: String,
    val notes: String,
    val start: Instant?,
    val end: Instant?,
    /** All-day: inclusive dates. */
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val repeats: Boolean,
    val categories: List<String>,
    val url: String?,
    val sourceRef: String?,
    /** The project it belongs to (e.g. a course's lectures or exam), kept in [Workspace.PROJECT_PROP]. */
    val projectId: String? = null,
    /** Where it is (a lecture's room): LOCATION, as every calendar app shows and edits it. */
    val location: String? = null,
)

/** One concrete occurrence of an event within a range. */
data class Occurrence(val event: Event, val start: Instant?, val end: Instant?, val startDate: LocalDate?, val endDate: LocalDate?)

/** Everything in CalDAV; [docs] holds every stored object (VTODOs from other clients included), by href. */
data class Snapshot(val calendars: List<Cal>, val events: List<Event>, val docs: Map<String, IcsDocument>)

/**
 * The calendar as the interop layer sees it: calendars and events read from CalDAV, and the
 * writes that keep every client in agreement. Reads are cached briefly; every write refreshes.
 */
@Component
class Workspace(private val dav: CalDav, private val clock: Clock, props: AppProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    val zone: ZoneId = props.zone
    private val lock = Mutex()
    @Volatile private var cached: Pair<Instant, Snapshot>? = null

    suspend fun snapshot(): Snapshot = lock.withLock {
        val now = Instant.now(clock)
        cached?.let { (at, s) -> if (now.isBefore(at.plusSeconds(CACHE_SECONDS))) return s }
        load().also { cached = now to it }
    }

    fun invalidate() {
        cached = null
    }

    private suspend fun load(): Snapshot {
        val calendars = dav.collections().map { Cal(slugOf(it.href), it.href, it.name, it.color?.uppercase()) }
        val events = mutableListOf<Event>()
        val docs = mutableMapOf<String, IcsDocument>()
        for (cal in calendars) {
            for (obj in dav.objects(cal.href)) {
                val doc = runCatching { IcsDocument.parse(obj.data) }.getOrElse {
                    log.warn("Skipping unparsable {}: {}", obj.href, it.message); null
                } ?: continue
                docs[obj.href] = doc
                val main = doc.main ?: continue
                if (main.name == "VEVENT") toEvent(main, obj, cal)?.let(events::add)
            }
        }
        return Snapshot(calendars, events, docs)
    }

    // ---- reading ---------------------------------------------------------------

    private fun toEvent(c: IcsDocument.Comp, obj: DavObject, cal: Cal): Event? {
        val startProp = c.prop("DTSTART") ?: return null
        val allDay = isDate(startProp)
        val endProp = c.prop("DTEND")
        return Event(
            uid = c.value("UID") ?: obj.href,
            href = obj.href,
            etag = obj.etag,
            calendarId = cal.id,
            title = titleOf(c),
            notes = c.text("DESCRIPTION").orEmpty(),
            start = if (allDay) null else instantOf(startProp),
            end = if (allDay) null else (endProp?.let { instantOf(it) } ?: instantOf(startProp)),
            startDate = if (allDay) dateOf(startProp) else null,
            endDate = if (allDay) (endProp?.let { dateOf(it)?.minusDays(1) } ?: dateOf(startProp)) else null,
            repeats = c.prop("RRULE") != null || c.prop("RDATE") != null,
            categories = categoriesOf(c),
            url = c.value("URL"),
            sourceRef = c.value(SOURCE_PROP),
            projectId = c.value(PROJECT_PROP),
            location = c.text("LOCATION")?.takeIf { it.isNotBlank() },
        )
    }

    /** Every occurrence of every event touching [from]..[to] (dates inclusive), repeats expanded. */
    fun occurrences(s: Snapshot, from: LocalDate, to: LocalDate): List<Occurrence> {
        val rangeStart = from.atStartOfDay(zone)
        val rangeEnd = to.plusDays(1).atStartOfDay(zone)
        val result = mutableListOf<Occurrence>()
        for (e in s.events) {
            if (!e.repeats) {
                val hit = if (e.startDate != null) e.startDate <= to && e.endDate!! >= from
                else e.start!!.isBefore(rangeEnd.toInstant()) && e.end!!.isAfter(rangeStart.toInstant().minusSeconds(1))
                if (hit) result += Occurrence(e, e.start, e.end, e.startDate, e.endDate)
                continue
            }
            result += expand(e, s.docs[e.href] ?: continue, from, to)
        }
        return result
    }

    private fun expand(e: Event, doc: IcsDocument, from: LocalDate, to: LocalDate): List<Occurrence> = runCatching {
        val cal = CalendarBuilder().build(StringReader(doc.serialize()))
        val masters = cal.getComponents<VEvent>("VEVENT").filter { it.getProperty<Property>("RECURRENCE-ID").isEmpty }
        val master = masters.firstOrNull() ?: return emptyList()
        val period: Period<out Temporal> = if (e.startDate != null) Period(from.minusDays(SPAN_SLACK), to.plusDays(1))
        else Period(from.atStartOfDay(zone), to.plusDays(1).atStartOfDay(zone))
        // Occurrences replaced by an override (RECURRENCE-ID) are left out here and added from the override.
        val overridden = doc.overrides.mapNotNull { o -> o.prop("RECURRENCE-ID")?.let { dateOf(it) ?: instantOf(it)?.atZone(zone)?.toLocalDate() } }.toSet()
        val list = master.calculateRecurrenceSet<Temporal>(period).mapNotNull { p ->
            val start = p.start
            if (start is LocalDate) {
                val end = (p.end as? LocalDate)?.minusDays(1)?.takeIf { it >= start } ?: start
                if (start in overridden || end < from || start > to) null else Occurrence(e, null, null, start, end)
            } else {
                val s = toInstant(start)
                val en = toInstant(p.end)
                if (s.atZone(zone).toLocalDate() in overridden) null else Occurrence(e, s, if (en.isBefore(s)) s else en, null, null)
            }
        }
        val overrides = doc.overrides.filter { it.name == "VEVENT" && it.value("STATUS")?.uppercase() != "CANCELLED" }.mapNotNull { o ->
            val obj = DavObject(e.href, e.etag, "")
            toEvent(o, obj, Cal(e.calendarId, "", "", null))?.let { oe ->
                val d = oe.startDate ?: oe.start!!.atZone(zone).toLocalDate()
                if (d < from || d > to) null else Occurrence(e.copy(title = oe.title.ifBlank { e.title }), oe.start, oe.end, oe.startDate, oe.endDate)
            }
        }
        list + overrides
    }.getOrElse {
        log.warn("Could not expand {}: {}", e.href, it.toString())
        emptyList()
    }

    // ---- writing ---------------------------------------------------------------

    data class EventFields(
        val title: String,
        val notes: String = "",
        val start: Instant? = null,
        val end: Instant? = null,
        val startDate: LocalDate? = null,
        /** Inclusive. */
        val endDate: LocalDate? = null,
        val categories: List<String> = emptyList(),
        val url: String? = null,
        val sourceRef: String? = null,
        /** An RRULE value, e.g. "FREQ=WEEKLY;BYDAY=TU,TH;UNTIL=20261225T145959Z". */
        val rrule: String? = null,
        val exdates: List<LocalDate> = emptyList(),
        val projectId: String? = null,
        val location: String? = null,
    )

    suspend fun createEvent(calendarId: String, f: EventFields, uid: String = newUid()): Event {
        val c = IcsDocument.Comp("VEVENT")
        c.set("UID", uid)
        c.set("CREATED", utc(Instant.now(clock)))
        applyEvent(c, f)
        val doc = IcsDocument.create(c)
        if (f.rrule != null) doc.root.children.add(0, seoulTimezone())
        put(calendarId, uid, doc, null)
        return findEvent(uid)!!
    }

    suspend fun updateEvent(event: Event, update: (EventFields) -> EventFields) {
        val s = snapshot()
        val doc = s.docs[event.href]?.let { IcsDocument.parse(it.serialize()) } ?: error("no document for ${event.href}")
        val c = doc.main!!
        val f = update(fieldsOf(event, c))
        applyEvent(c, f)
        if (f.rrule != null && doc.root.children.none { it.name == "VTIMEZONE" && it.value("TZID") == zone.id }) doc.root.children.add(0, seoulTimezone())
        c.set("SEQUENCE", ((c.value("SEQUENCE")?.toIntOrNull() ?: 0) + 1).toString())
        dav.put(event.href, doc.serialize(), event.etag)
        invalidate()
    }

    suspend fun delete(href: String, etag: String?) {
        dav.delete(href, etag)
        invalidate()
    }

    suspend fun createCalendar(name: String, color: String?, slug: String = UUID.randomUUID().toString()): Cal {
        val c = dav.createCollection(slug, name, color)
        invalidate()
        return Cal(slugOf(c.href), c.href, name, color)
    }

    suspend fun updateCalendar(cal: Cal, name: String?, color: String?) {
        dav.updateCollection(cal.href, name, color)
        invalidate()
    }

    /** The assistant's own calendar: where everything created through the app goes. */
    suspend fun assistantCalendar(): Cal =
        snapshot().calendars.find { it.id == ASSISTANT_SLUG } ?: createCalendar("Assistant", "#2F5BE0", slug = ASSISTANT_SLUG)

    suspend fun findEvent(uid: String): Event? = snapshot().events.find { it.uid == uid }

    private suspend fun put(calendarId: String, uid: String, doc: IcsDocument, etag: String?): Pair<String, String?> {
        val cal = snapshot().calendars.find { it.id == calendarId } ?: error("no calendar $calendarId")
        val href = cal.href + safeName(uid) + ".ics"
        val tag = dav.put(href, doc.serialize(), etag)
        invalidate()
        return href to tag
    }

    private fun applyEvent(c: IcsDocument.Comp, f: EventFields) {
        val now = Instant.now(clock)
        c.set("DTSTAMP", utc(now))
        c.set("LAST-MODIFIED", utc(now))
        setTitle(c, f.title)
        c.setText("DESCRIPTION", f.notes.ifBlank { null })
        c.setText("LOCATION", f.location?.ifBlank { null })
        if (f.startDate != null) {
            c.set("DTSTART", DATE.format(f.startDate), mapOf("VALUE" to "DATE"))
            c.set("DTEND", DATE.format((f.endDate ?: f.startDate).plusDays(1)), mapOf("VALUE" to "DATE"))
        } else if (f.rrule != null) {
            // Repeating timed events keep local wall-clock time, so they need a time zone.
            c.set("DTSTART", LOCAL.format(f.start!!.atZone(zone)), mapOf("TZID" to zone.id))
            c.set("DTEND", LOCAL.format(f.end!!.atZone(zone)), mapOf("TZID" to zone.id))
        } else {
            c.set("DTSTART", utc(f.start!!))
            c.set("DTEND", utc(f.end ?: f.start))
        }
        c.set("RRULE", f.rrule)
        c.props.removeAll { it.name == "EXDATE" }
        if (f.rrule != null) {
            f.exdates.forEach { d ->
                c.props += if (f.startDate != null) IcsDocument.Prop("EXDATE", mapOf("VALUE" to "DATE"), DATE.format(d))
                else IcsDocument.Prop("EXDATE", mapOf("TZID" to zone.id), LOCAL.format(d.atTime(f.start!!.atZone(zone).toLocalTime())))
            }
        }
        setCategories(c, f.categories)
        c.set("URL", f.url)
        c.set(SOURCE_PROP, f.sourceRef)
        c.set(PROJECT_PROP, f.projectId)
    }

    fun fieldsOf(e: Event, c: IcsDocument.Comp) = EventFields(
        e.title, e.notes, e.start, e.end, e.startDate, e.endDate, e.categories, e.url, e.sourceRef,
        rrule = c.value("RRULE"),
        projectId = e.projectId,
        location = e.location,
        exdates = c.all("EXDATE").flatMap { p -> p.value.split(',').mapNotNull { v -> dateOf(IcsDocument.Prop("EXDATE", p.params, v)) ?: instantOf(IcsDocument.Prop("EXDATE", p.params, v))?.atZone(zone)?.toLocalDate() } },
    )

    /**
     * Titles may hold references (`[text](url)`). SUMMARY gets the plain text other apps can show; the
     * linked title is kept in [TITLE_PROP] and used while SUMMARY still matches it (another app
     * renaming the event wins).
     */
    private fun setTitle(c: IcsDocument.Comp, title: String) {
        val plain = plainText(title)
        c.setText("SUMMARY", plain)
        c.setText(TITLE_PROP, title.takeIf { it != plain })
    }

    private fun titleOf(c: IcsDocument.Comp): String {
        val summary = c.text("SUMMARY").orEmpty()
        return c.text(TITLE_PROP)?.takeIf { plainText(it) == summary } ?: summary
    }

    private fun setCategories(c: IcsDocument.Comp, categories: List<String>) {
        val clean = categories.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        c.set("CATEGORIES", clean.takeIf { it.isNotEmpty() }?.joinToString(",") { it.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;") })
    }

    fun categoriesOf(c: IcsDocument.Comp): List<String> =
        c.all("CATEGORIES").flatMap { splitUnescaped(it.value) }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun splitUnescaped(value: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                ch == '\\' && i + 1 < value.length -> { cur.append(if (value[i + 1] == 'n') '\n' else value[i + 1]); i++ }
                ch == ',' -> { parts += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        parts += cur.toString()
        return parts
    }

    private fun isDate(p: IcsDocument.Prop) = p.param("VALUE").equals("DATE", true) || p.value.length == 8

    fun dueOf(p: IcsDocument.Prop): Due? {
        if (isDate(p)) return dateOf(p)?.let { Due(it, null) }
        val at = instantOf(p)?.atZone(zone) ?: return null
        return Due(at.toLocalDate(), at.toLocalTime().withSecond(0).withNano(0))
    }

    private fun dateOf(p: IcsDocument.Prop): LocalDate? =
        if (isDate(p)) runCatching { LocalDate.parse(p.value.take(8), DATE) }.getOrNull() else null

    fun instantOf(p: IcsDocument.Prop): Instant? = runCatching {
        if (isDate(p)) return@runCatching LocalDate.parse(p.value.take(8), DATE).atStartOfDay(zone).toInstant()
        if (p.value.endsWith("Z")) return@runCatching LocalDateTime.parse(p.value.dropLast(1), LOCAL).toInstant(ZoneOffset.UTC)
        val tz = p.param("TZID")?.let { runCatching { ZoneId.of(it.trim('"')) }.getOrNull() } ?: zone
        LocalDateTime.parse(p.value, LOCAL).atZone(tz).toInstant()
    }.getOrNull()

    private fun toInstant(t: Temporal): Instant = when (t) {
        is Instant -> t
        is LocalDateTime -> t.atZone(zone).toInstant()
        else -> Instant.from(t)
    }

    private fun seoulTimezone() = IcsDocument.Comp(
        "VTIMEZONE",
        mutableListOf(IcsDocument.Prop("TZID", emptyMap(), zone.id)),
        mutableListOf(
            IcsDocument.Comp(
                "STANDARD",
                mutableListOf(
                    IcsDocument.Prop("DTSTART", emptyMap(), "19700101T000000"),
                    IcsDocument.Prop("TZOFFSETFROM", emptyMap(), "+0900"),
                    IcsDocument.Prop("TZOFFSETTO", emptyMap(), "+0900"),
                    IcsDocument.Prop("TZNAME", emptyMap(), "KST"),
                ),
            ),
        ),
    )

    companion object {
        /** Our own property marking an exporter's reference; other clients keep it untouched. */
        const val SOURCE_PROP = "X-ASSISTANT-SOURCE"
        /** The title with its references, when it has any (SUMMARY holds the plain text). */
        const val TITLE_PROP = "X-ASSISTANT-TITLE"
        /** The project an event belongs to; other calendar apps ignore it. */
        const val PROJECT_PROP = "X-ASSISTANT-PROJECT"
        const val ASSISTANT_SLUG = "assistant"
        private const val CACHE_SECONDS = 15L
        private const val SPAN_SLACK = 31L
        private val DATE = DateTimeFormatter.BASIC_ISO_DATE
        private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        fun utc(i: Instant): String = UTC_FORMAT.format(i)
        fun newUid() = "${UUID.randomUUID()}@assistant"
        fun slugOf(href: String) = URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), Charsets.UTF_8)
        /** A file name for a UID: safe characters only, so hrefs never need escaping. */
        fun safeName(uid: String) = uid.replace(Regex("[^A-Za-z0-9._@-]"), "_")
    }
}
