package com.syeyoung.assistant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GitHubRefsTest : ApiTestBase() {
    @Suppress("UNCHECKED_CAST")
    private fun search(q: String) = (client.get().uri { it.path("/api/v1/refs").queryParam("q", "{q}").build(q) }.exchange().expectStatus().isOk
        .expectBody(List::class.java).returnResult().responseBody as List<Map<String, Any?>>).filter { (it["kind"] as String).startsWith("github") }

    @Suppress("UNCHECKED_CAST")
    private fun resolve(url: String) = client.get().uri { it.path("/api/v1/refs/resolve").queryParam("url", "{u}").build(url) }.exchange().expectStatus().isOk
        .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Test
    fun `the App's installations are searched, and pasted links named (private ones through the owner's installation)`() {
        assertThat(get("/api/v1/github/account")).containsEntry("configured", true).containsEntry("installations", listOf("cyoung06"))

        val found = search("tasks")
        assertThat(found.map { it["kind"] to it["title"] }).contains(
            "github_repo" to "cyoung06/tasks", "github_pr" to "cyoung06/tasks#42 Triage page", "github_issue" to "cyoung06/tasks#7 Triage keyboard",
        )
        assertThat(found.first { it["kind"] == "github_pr" }["context"]).isEqualTo("merged PR")
        assertThat(search("assistant").single { it["kind"] == "github_repo" }["context"]).isEqualTo("repo · private")

        assertThat(resolve("https://github.com/cyoung06/tasks/pull/42/files")).containsEntry("title", "cyoung06/tasks#42 Triage page")
        assertThat(resolve("https://github.com/cyoung06/assistant")).containsEntry("kind", "github_repo").containsEntry("context", "repo · private")
        // Other owners' public repos are read without a token.
        assertThat(resolve("https://github.com/PLUS-POSTECH/tasks")).containsEntry("title", "PLUS-POSTECH/tasks")
        assertThat(TestGitHub.appCalls).isGreaterThan(0)
    }
}
