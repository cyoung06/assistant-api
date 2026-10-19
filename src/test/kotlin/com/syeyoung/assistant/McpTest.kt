package com.syeyoung.assistant

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class McpTest : ApiTestBase() {
    private var id = 0

    /** One JSON-RPC call to /mcp, as an MCP client (ChatGPT through the tunnel) makes it. */
    @Suppress("UNCHECKED_CAST")
    private fun rpc(method: String, params: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        client.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .bodyValue(mapOf("jsonrpc" to "2.0", "id" to ++id, "method" to method, "params" to params))
            .exchange().expectStatus().isOk.expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun call(tool: String, args: Map<String, Any?> = emptyMap()): String {
        val result = rpc("tools/call", mapOf("name" to tool, "arguments" to args))["result"] as Map<String, Any?>
        return ((result["content"] as List<Map<String, Any?>>).single()["text"] as String)
    }

    @Test
    fun `ChatGPT reads tasks, projects and events, and can only send things to Triage`() {
        val init = rpc("initialize", mapOf("protocolVersion" to "2025-06-18", "capabilities" to emptyMap<String, Any>(), "clientInfo" to mapOf("name" to "test", "version" to "1")))
        @Suppress("UNCHECKED_CAST")
        val info = (init["result"] as Map<String, Any?>)["serverInfo"] as Map<String, Any?>
        assertThat(info).containsEntry("name", "assistant")

        @Suppress("UNCHECKED_CAST")
        val tools = ((rpc("tools/list")["result"] as Map<String, Any?>)["tools"] as List<Map<String, Any?>>).map { it["name"] }
        assertThat(tools).containsExactlyInAnyOrder("list_tasks", "get_task", "list_projects", "list_events", "search", "list_triage", "send_to_triage")

        val course = post("/api/v1/projects", mapOf("name" to "확률및통계"))
        post("/api/v1/tasks", mapOf("title" to "HW7", "projectId" to course["id"], "due" to mapOf("date" to "2026-10-20", "time" to "23:59"), "description" to "Problems 1–5"))
        post("/api/v1/tasks", mapOf("title" to "Old", "status" to "done"))
        assertThat(call("list_tasks")).contains("#1 HW7 · todo · due 2026-10-20 23:59 · 확률및통계").doesNotContain("Old")
        assertThat(call("list_tasks", mapOf("view" to "due_soon"))).contains("#1 HW7")
        assertThat(call("get_task", mapOf("number" to "#1"))).contains("Problems 1–5")
        assertThat(call("list_projects")).contains("확률및통계 · in_progress · 0/1 done")
        post("/api/v1/events", mapOf("title" to "Midterm", "start" to "2026-10-30T10:00:00+09:00", "end" to "2026-10-30T12:00:00+09:00", "projectId" to course["id"]))
        assertThat(call("list_events", mapOf("from" to "2026-10-30", "to" to "2026-10-30"))).contains("2026-10-30 10:00–12:00 · Midterm · Assistant · 확률및통계")
        assertThat(call("search", mapOf("query" to "HW7"))).contains("task · #1 HW7")

        assertThat(call("send_to_triage", mapOf("text" to "Team meeting Friday 3pm", "title" to "Meeting"))).startsWith("Sent to Triage")
        assertThat(getList("/api/v1/triage").single()).containsEntry("source", "chatgpt").containsEntry("title", "Meeting")
        assertThat(getList("/api/v1/tasks")).hasSize(2)

        // A bad argument is a tool error, not a failed call.
        @Suppress("UNCHECKED_CAST")
        val bad = rpc("tools/call", mapOf("name" to "list_events", "arguments" to mapOf("from" to "soon", "to" to "later")))["result"] as Map<String, Any?>
        assertThat(bad["isError"]).isEqualTo(true)
    }
}
