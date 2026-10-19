package com.syeyoung.assistant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class InitiativesTest : ApiTestBase() {
    @Test
    fun `initiatives group projects and add up their progress`() {
        val algo = post("/api/v1/projects", mapOf("name" to "고급 알고리즘"))
        val bio = post("/api/v1/projects", mapOf("name" to "일반생명과학"))
        val fall = post("/api/v1/initiatives", mapOf("name" to "2026 Fall", "targetDate" to "2026-12-18", "projectIds" to listOf(algo["id"])))
        assertThat(fall).containsEntry("status", "active").containsEntry("targetDate", "2026-12-18").containsEntry("projectIds", listOf(algo["id"]))

        // From the project's side too; a project can be in several.
        val ugrp = post("/api/v1/initiatives", mapOf("name" to "UGRP"))
        patch("/api/v1/projects/${bio["id"]}", mapOf("initiativeIds" to listOf(fall["id"], ugrp["id"])))
        assertThat(get("/api/v1/projects/${bio["id"]}")["initiativeIds"] as List<*>).containsExactlyInAnyOrder(fall["id"], ugrp["id"])

        post("/api/v1/tasks", mapOf("title" to "HW1", "projectId" to algo["id"], "status" to "done"))
        post("/api/v1/tasks", mapOf("title" to "HW2", "projectId" to bio["id"]))
        post("/api/v1/tasks", mapOf("title" to "Dropped", "projectId" to bio["id"], "status" to "canceled"))
        assertThat(get("/api/v1/initiatives/${fall["id"]}")).containsEntry("taskCount", 2).containsEntry("doneCount", 1)

        // Completing, clearing the date, and setting its projects.
        val done = patch("/api/v1/initiatives/${fall["id"]}", mapOf("status" to "completed", "targetDate" to null, "projectIds" to listOf(bio["id"])))
        assertThat(done).containsEntry("status", "completed").containsEntry("targetDate", null).containsEntry("projectIds", listOf(bio["id"]))

        // Deleting it leaves its projects.
        delete("/api/v1/initiatives/${fall["id"]}")
        assertThat(get("/api/v1/projects/${bio["id"]}")["initiativeIds"]).isEqualTo(listOf(ugrp["id"]))
        assertThat(getList("/api/v1/projects")).hasSize(2)
    }

    @Test
    fun `bad input is refused`() {
        client.post().uri("/api/v1/initiatives").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("name" to " "))
            .exchange().expectStatus().isBadRequest
        val i = post("/api/v1/initiatives", mapOf("name" to "UGRP"))
        client.patch().uri("/api/v1/initiatives/${i["id"]}").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("status" to "paused"))
            .exchange().expectStatus().isBadRequest
        client.patch().uri("/api/v1/initiatives/${i["id"]}").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("projectIds" to listOf("00000000-0000-0000-0000-000000000000"))).exchange().expectStatus().isBadRequest
    }
}
