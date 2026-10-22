package com.syeyoung.assistant

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import com.syeyoung.assistant.today.TodayService

/** The day at a glance (the test clock: Sunday 2026-10-18, 10:00 in Seoul). */
class TodayApiTest : ApiTestBase() {
    @Autowired lateinit var today: TodayService

    @Test
    fun `the next class with its room, today's events, and what's due soon`() {
        val course = post("/api/v1/projects", mapOf("name" to "확률및통계"))
        val lecture = post(
            "/api/v1/events",
            mapOf(
                "title" to "확률및통계 강의", "start" to "2026-10-19T12:30:00+09:00", "end" to "2026-10-19T13:45:00+09:00", "location" to "Science Bldg 101",
                "repeat" to mapOf("freq" to "weekly", "weekdays" to listOf("MONDAY", "WEDNESDAY")), "projectId" to course["id"],
            ),
        )
        // The room is iCalendar's LOCATION, as other calendar apps edit it.
        assertThat(runBlocking { dav.get(ws.findEvent(lecture["id"] as String)!!.href)!!.data }).contains("LOCATION:Science Bldg 101")
        post("/api/v1/events", mapOf("title" to "Study group", "start" to "2026-10-18T14:00:00+09:00", "end" to "2026-10-18T15:00:00+09:00"))
        post("/api/v1/events", mapOf("title" to "Festival", "startDate" to "2026-10-17", "endDate" to "2026-10-18"))
        post("/api/v1/events", mapOf("title" to "Yesterday", "start" to "2026-10-17T14:00:00+09:00", "end" to "2026-10-17T15:00:00+09:00"))
        post("/api/v1/tasks", mapOf("title" to "Overdue", "due" to mapOf("date" to "2026-10-16")))
        post("/api/v1/tasks", mapOf("title" to "Due Tuesday", "due" to mapOf("date" to "2026-10-20")))
        post("/api/v1/tasks", mapOf("title" to "Next week", "due" to mapOf("date" to "2026-10-25")))
        val done = post("/api/v1/tasks", mapOf("title" to "Done already", "due" to mapOf("date" to "2026-10-18")))
        patch("/api/v1/tasks/${done["id"]}", mapOf("status" to "done"))

        val today = get("/api/v1/today")
        assertThat(today["date"]).isEqualTo("2026-10-18")
        @Suppress("UNCHECKED_CAST")
        assertThat((today["events"] as List<Map<String, Any?>>).map { it["title"] }).containsExactly("Festival", "Study group")
        @Suppress("UNCHECKED_CAST")
        val next = (today["next"] as Map<String, Any?>)["event"] as Map<String, Any?>
        assertThat(next["title"]).isEqualTo("확률및통계 강의")
        assertThat(next["start"]).isEqualTo("2026-10-19T12:30:00+09:00")
        assertThat(next["location"]).isEqualTo("Science Bldg 101")
        assertThat((today["next"] as Map<*, *>)["projectName"]).isEqualTo("확률및통계")
        @Suppress("UNCHECKED_CAST")
        assertThat((today["due"] as List<Map<String, Any?>>).map { it["title"] }).containsExactly("Overdue", "Due Tuesday")
        assertThat(today["triage"]).isEqualTo(0)

        // The room changed, and cleared.
        assertThat(patch("/api/v1/events/${lecture["id"]}", mapOf("location" to "Online"))["location"]).isEqualTo("Online")
        assertThat(patch("/api/v1/events/${lecture["id"]}", mapOf("location" to null))).doesNotContainKey("location")
    }

    @Test
    fun `a lecture added by hand counts by its title`() {
        post("/api/v1/projects", mapOf("name" to "컴퓨터시스템 개론"))
        post("/api/v1/projects", mapOf("name" to "컴퓨터"))
        post("/api/v1/events", mapOf("title" to "Lunch", "start" to "2026-10-18T12:00:00+09:00", "end" to "2026-10-18T13:00:00+09:00"))
        post("/api/v1/events", mapOf("title" to "컴퓨터시스템 개론", "start" to "2026-10-18T14:00:00+09:00", "end" to "2026-10-18T15:15:00+09:00", "location" to "LG 104"))
        @Suppress("UNCHECKED_CAST")
        val next = get("/api/v1/today")["next"] as Map<String, Any?>
        assertThat((next["event"] as Map<*, *>)["title"]).isEqualTo("컴퓨터시스템 개론")
        // The project it names in full, not one whose name only starts it.
        assertThat(next["projectName"]).isEqualTo("컴퓨터시스템 개론")
    }

    @Test
    fun `a quick note goes to the class on now, from a quarter of an hour before it`() {
        val course = post("/api/v1/projects", mapOf("name" to "확률및통계"))
        post("/api/v1/events", mapOf("title" to "Lecture", "start" to "2026-10-18T10:10:00+09:00", "end" to "2026-10-18T11:00:00+09:00", "projectId" to course["id"]))
        assertThat(runBlocking { today.currentProject() }?.name).isEqualTo("확률및통계")
    }

    @Test
    fun `between classes there's none`() {
        val course = post("/api/v1/projects", mapOf("name" to "확률및통계"))
        post("/api/v1/events", mapOf("title" to "Lecture", "start" to "2026-10-18T10:30:00+09:00", "end" to "2026-10-18T11:00:00+09:00", "projectId" to course["id"]))
        post("/api/v1/events", mapOf("title" to "Earlier", "start" to "2026-10-18T08:00:00+09:00", "end" to "2026-10-18T09:59:00+09:00", "projectId" to course["id"]))
        assertThat(runBlocking { today.currentProject() }).isNull()
    }
}
