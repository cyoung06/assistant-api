package com.syeyoung.assistant.workspace

import com.syeyoung.assistant.dav.Workspace
import com.syeyoung.assistant.source.SourceRepository
import com.syeyoung.assistant.source.SourceType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RestructureReport(val movedToAssistant: Int, val movedToPlms: Int, val feedEventsDropped: Int, val calendarsRemoved: Int)

/**
 * One-time move from "a calendar per course" to "a calendar per source": PLMS tasks go to the PLMS
 * calendar, everything else to the assistant's calendar, the old calendar's name (the course)
 * becomes a category, and feed events are dropped so their feed refills its own calendar.
 * Running it again finds nothing to move.
 */
@Service
class Restructure(private val ws: Workspace, private val sources: SourceRepository, private val db: DatabaseClient) {

    suspend fun run(): RestructureReport {
        val assistant = ws.assistantCalendar()
        val plms = ws.snapshot().calendars.find { it.id == PLMS_SLUG } ?: ws.createCalendar("PLMS", "#E0642F", slug = PLMS_SLUG)
        sources.mapCalendar(SourceType.PLMS, SourceType.PLMS.wire, plms.id)

        var toAssistant = 0
        var toPlms = 0
        var dropped = 0
        val old = ws.snapshot().calendars.filter { it.id != assistant.id && !it.id.startsWith("src-") }
        for (cal in old) {
            val course = cal.name.takeUnless { it.equals("Inbox", true) || it.equals("holiday", true) }
            val s = ws.snapshot()
            for (e in s.events.filter { it.calendarId == cal.id }) {
                if (e.sourceRef?.startsWith("ical:") == true) {
                    ws.delete(e.href, e.etag); dropped++; continue
                }
                ws.updateEvent(e) { f -> f.copy(categories = (listOfNotNull(course) + f.categories).distinct()) }
                ws.findEvent(e.uid)!!.let { moved -> ws.move(moved.href, moved.etag, moved.uid, assistant.id) }
                toAssistant++
            }
            for (t in s.tasks.filter { it.calendarId == cal.id }) {
                ws.updateTask(t) { f -> f.copy(categories = (listOfNotNull(course) + f.categories).distinct()) }
                val target = if (t.sourceRef?.startsWith("plms:") == true) plms.id.also { toPlms++ } else assistant.id.also { toAssistant++ }
                ws.findTask(t.uid)!!.let { moved -> ws.move(moved.href, moved.etag, moved.uid, target) }
            }
            ws.deleteCalendar(cal)
        }
        // Feeds start over in their own calendars; PLMS keeps its references (its tasks kept their UIDs).
        db.sql("delete from source_refs where source_type = 'ical'").fetch().awaitRowsUpdated()
        db.sql("delete from source_calendars where not (source_type = 'plms' and calendar_id = :plms)").bind("plms", plms.id).fetch().awaitRowsUpdated()
        return RestructureReport(toAssistant, toPlms, dropped, old.size)
    }

    companion object {
        const val PLMS_SLUG = "src-plms"
    }
}

@RestController
@RequestMapping("/api/v1/admin")
class RestructureController(private val restructure: Restructure) {
    /** Moves from a calendar per course to a calendar per source. Safe to run again. */
    @PostMapping("/restructure")
    suspend fun run(): RestructureReport = restructure.run()
}
