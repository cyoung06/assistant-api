package com.syeyoung.assistant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class TasksApiTest : ApiTestBase() {

    private fun tasks(query: String = ""): List<Map<String, Any?>> = getList("/api/v1/tasks$query")

    @Test
    fun `tasks have numbers, status, priority, due, project and labels`() {
        val course = post("/api/v1/projects", mapOf("name" to "확률및통계"))
        val label = post("/api/v1/labels", mapOf("name" to "assignment", "color" to "#e0642f"))
        assertThat(label["color"]).isEqualTo("#E0642F")
        val hw = post(
            "/api/v1/tasks",
            mapOf(
                "title" to "HW6", "priority" to "high", "due" to mapOf("date" to "2026-10-20", "time" to "19:00"),
                "projectId" to course["id"], "labelIds" to listOf(label["id"]),
            ),
        )
        assertThat(hw["number"]).isEqualTo(1)
        assertThat(hw["status"]).isEqualTo("todo")
        assertThat(hw["priority"]).isEqualTo("high")
        assertThat(hw["due"]).isEqualTo(mapOf("date" to "2026-10-20", "time" to "19:00:00"))
        assertThat(hw["labelIds"]).isEqualTo(listOf(label["id"]))
        assertThat(post("/api/v1/tasks", mapOf("title" to "Read ch. 5"))["number"]).isEqualTo(2)

        val id = hw["id"]
        val done = patch("/api/v1/tasks/$id", mapOf("status" to "done"), ifMatch = "\"${hw["version"]}\"")
        assertThat(done["closedAt"]).isNotNull()
        assertThat(get("/api/v1/projects/${course["id"]}")).containsEntry("taskCount", 1).containsEntry("doneCount", 1)
        // A stale version is refused.
        client.patch().uri("/api/v1/tasks/$id").contentType(MediaType.APPLICATION_JSON).headers { it.ifMatch = listOf("\"${hw["version"]}\"") }
            .bodyValue(mapOf("title" to "x")).exchange().expectStatus().isEqualTo(412)
        assertThat(patch("/api/v1/tasks/$id", mapOf("status" to "in_progress"))["closedAt"]).isNull()
        assertThat(patch("/api/v1/tasks/$id", mapOf("labelIds" to emptyList<String>(), "projectId" to null, "due" to null)))
            .containsEntry("labelIds", emptyList<String>()).containsEntry("projectId", null).containsEntry("due", null)
    }

    @Test
    fun `filters, overdue and search`() {
        post("/api/v1/tasks", mapOf("title" to "late", "due" to mapOf("date" to "2026-10-17")))
        post("/api/v1/tasks", mapOf("title" to "today", "due" to mapOf("date" to "2026-10-18", "time" to "09:00")))
        post("/api/v1/tasks", mapOf("title" to "later", "due" to mapOf("date" to "2026-11-01"), "status" to "backlog"))
        post("/api/v1/tasks", mapOf("title" to "dropped", "due" to mapOf("date" to "2026-10-10"), "status" to "canceled"))
        val byTitle = tasks().associateBy { it["title"] }
        assertThat(byTitle.mapValues { it.value["overdue"] }).isEqualTo(mapOf("late" to true, "today" to true, "later" to false, "dropped" to false))
        assertThat(tasks("?status=backlog&status=canceled").map { it["title"] }).containsExactly("later", "dropped")
        assertThat(tasks("?dueFrom=2026-10-18&dueTo=2026-10-31").map { it["title"] }).containsExactly("today")
        assertThat(tasks("?q=LAT").map { it["title"] }).containsExactly("late", "later")
        val byNumber = client.get().uri { it.path("/api/v1/tasks").queryParam("q", "{q}").build("#3") }.exchange().expectStatus().isOk
            .expectBody(List::class.java).returnResult().responseBody!!
        assertThat(byNumber.map { (it as Map<*, *>)["title"] }).containsExactly("later")
    }

    @Test
    fun `sub-tasks are counted and deleted with their parent`() {
        val parent = post("/api/v1/tasks", mapOf("title" to "Project report"))
        val a = post("/api/v1/tasks", mapOf("title" to "Outline", "parentId" to parent["id"]))
        post("/api/v1/tasks", mapOf("title" to "Draft", "parentId" to a["id"]))
        patch("/api/v1/tasks/${a["id"]}", mapOf("status" to "done"))
        assertThat(get("/api/v1/tasks/${parent["id"]}")).containsEntry("subtasks", 2).containsEntry("subtasksDone", 1)
        // A task can't move under its own sub-task.
        client.patch().uri("/api/v1/tasks/${parent["id"]}").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("parentId" to a["id"])).exchange().expectStatus().isBadRequest
        delete("/api/v1/tasks/${parent["id"]}")
        assertThat(tasks()).isEmpty()
    }

    @Test
    fun `relations show from both sides and blocked follows open blockers`() {
        val design = post("/api/v1/tasks", mapOf("title" to "Design schema"))
        val build = post("/api/v1/tasks", mapOf("title" to "Build API"))
        val after = client.post().uri("/api/v1/tasks/${build["id"]}/relations").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("kind" to "blocked_by", "taskId" to design["id"])).exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!
        assertThat(after["blocked"]).isEqualTo(true)
        val relation = (after["relations"] as List<*>).single() as Map<*, *>
        assertThat(relation["kind"]).isEqualTo("blocked_by")
        assertThat((relation["task"] as Map<*, *>)["title"]).isEqualTo("Design schema")
        assertThat(((get("/api/v1/tasks/${design["id"]}")["relations"] as List<*>).single() as Map<*, *>)["kind"]).isEqualTo("blocks")

        patch("/api/v1/tasks/${design["id"]}", mapOf("status" to "done"))
        assertThat(get("/api/v1/tasks/${build["id"]}")["blocked"]).isEqualTo(false)
        val removed = client.delete().uri("/api/v1/tasks/${build["id"]}/relations/${relation["id"]}").exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!
        assertThat(removed["relations"] as List<*>).isEmpty()
    }

    @Test
    fun `labels are unique by name and deleting a project keeps its tasks`() {
        post("/api/v1/labels", mapOf("name" to "quiz"))
        client.post().uri("/api/v1/labels").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("name" to "Quiz")).exchange().expectStatus().isEqualTo(409)
        val p = post("/api/v1/projects", mapOf("name" to "Lab"))
        val t = post("/api/v1/tasks", mapOf("title" to "Order parts", "projectId" to p["id"]))
        delete("/api/v1/projects/${p["id"]}")
        assertThat(get("/api/v1/tasks/${t["id"]}")["projectId"]).isNull()
    }
}
