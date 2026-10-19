package com.syeyoung.assistant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class GitHubRefsTest : ApiTestBase() {
    @Suppress("UNCHECKED_CAST")
    private fun search(q: String) = client.get().uri { it.path("/api/v1/refs").queryParam("q", "{q}").build(q) }.exchange().expectStatus().isOk
        .expectBody(List::class.java).returnResult().responseBody as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun resolve(url: String) = client.get().uri { it.path("/api/v1/refs/resolve").queryParam("url", "{u}").build(url) }.exchange().expectStatus().isOk
        .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Test
    fun `with a token, repos, issues and PRs are searched and pasted links named`() {
        assertThat(get("/api/v1/github/account")).containsEntry("connected", false)
        assertThat(search("tasks").filter { (it["kind"] as String).startsWith("github") }).isEmpty()

        client.put().uri("/api/v1/github/account").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("token" to "bad")).exchange().expectStatus().isBadRequest
        client.put().uri("/api/v1/github/account").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("token" to "good")).exchange().expectStatus().isOk
        assertThat(get("/api/v1/github/account")).containsEntry("connected", true).containsEntry("login", "cyoung06")

        val found = search("tasks").filter { (it["kind"] as String).startsWith("github") }
        assertThat(found.map { it["kind"] to it["title"] }).contains(
            "github_repo" to "PLUS-POSTECH/tasks", "github_pr" to "PLUS-POSTECH/tasks#42 Triage page", "github_issue" to "PLUS-POSTECH/tasks#7 Triage keyboard",
        )
        assertThat(found.first { it["kind"] == "github_pr" }["context"]).isEqualTo("merged PR")

        assertThat(resolve("https://github.com/PLUS-POSTECH/tasks/pull/42/files")).containsEntry("title", "PLUS-POSTECH/tasks#42 Triage page").containsEntry("kind", "github_pr")
        assertThat(resolve("https://github.com/PLUS-POSTECH/tasks/issues/7")).containsEntry("context", "open issue")
        assertThat(resolve("https://github.com/PLUS-POSTECH/tasks")).containsEntry("kind", "github_repo")

        delete("/api/v1/github/account")
        assertThat(search("tasks").filter { (it["kind"] as String).startsWith("github") }).isEmpty()
    }
}
