package com.syeyoung.assistant.mcp

import com.syeyoung.assistant.calendar.CalendarService
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.refs.AppLinks
import com.syeyoung.assistant.refs.RefKind
import com.syeyoung.assistant.refs.RefService
import com.syeyoung.assistant.refs.plainText
import com.syeyoung.assistant.tasks.TaskFilter
import com.syeyoung.assistant.tasks.TaskResponse
import com.syeyoung.assistant.tasks.TaskService
import com.syeyoung.assistant.tasks.TaskStatus
import com.syeyoung.assistant.triage.SubmitRequest
import com.syeyoung.assistant.triage.TriageService
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpStatelessAsyncServer
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStatelessServerTransport
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * An MCP server for ChatGPT (through OpenAI's Secure MCP Tunnel, from inside the cluster): read
 * tasks, projects, events and Triage, search references, and send things to Triage, where they wait
 * for the user to accept. It changes nothing else; it is not served on the public host or the
 * tailnet (the web server doesn't pass /mcp on).
 */
@Configuration
class McpServerConfig {
    @Bean
    fun mcpJsonMapper(json: JsonMapper): McpJsonMapper = JacksonMcpJsonMapper(json)

    @Bean
    fun mcpTransport(mapper: McpJsonMapper): WebFluxStatelessServerTransport =
        WebFluxStatelessServerTransport.builder().jsonMapper(mapper).messageEndpoint("/mcp").build()

    @Bean
    fun mcpRoutes(transport: WebFluxStatelessServerTransport): RouterFunction<*> = transport.routerFunction

    @Bean(destroyMethod = "close")
    fun mcpServer(transport: WebFluxStatelessServerTransport, mapper: McpJsonMapper, tools: McpTools): McpStatelessAsyncServer =
        McpServer.async(transport)
            .jsonMapper(mapper)
            .serverInfo("assistant", "1.0")
            .instructions(
                """
                The user's own task and calendar app (a POSTECH student). Tasks have numbers (#12), statuses
                (backlog, todo, in_progress, done, canceled), due dates and projects (usually courses). To add
                something, use send_to_triage: it waits in the user's Triage until they accept it.
                """.trimIndent(),
            )
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .tools(tools.all(mapper))
            .build()
}

/**
 * Answers JSON-RPC requests the MCP SDK doesn't handle (e.g. "server/discover", which ChatGPT tries
 * first) with the standard "method not found" error, so the client falls back to "initialize";
 * the SDK's transport would answer them with an HTTP 500 instead.
 */
@org.springframework.stereotype.Component
class McpUnknownMethods(private val json: JsonMapper) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (exchange.request.path.value() != "/mcp" || exchange.request.method != HttpMethod.POST) return chain.filter(exchange)
        return DataBufferUtils.join(exchange.request.body).defaultIfEmpty(DefaultDataBufferFactory.sharedInstance.wrap(ByteArray(0))).flatMap { buffer ->
            val bytes = ByteArray(buffer.readableByteCount()).also(buffer::read)
            DataBufferUtils.release(buffer)
            val message = runCatching { json.readTree(bytes) }.getOrNull()
            val method = message?.path("method")?.asString(null)
            val id = message?.get("id")
            if (method != null && id != null && !id.isNull && method !in HANDLED) {
                val error = mapOf("jsonrpc" to "2.0", "id" to json.treeToValue(id, Any::class.java), "error" to mapOf("code" to -32601, "message" to "Method not found: $method"))
                exchange.response.headers.contentType = MediaType.APPLICATION_JSON
                exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(json.writeValueAsBytes(error))))
            } else {
                // The body was read here, so the request goes on with it again.
                val request = object : ServerHttpRequestDecorator(exchange.request) {
                    override fun getBody(): Flux<DataBuffer> = Flux.just(exchange.response.bufferFactory().wrap(bytes))
                }
                chain.filter(exchange.mutate().request(request).build())
            }
        }
    }

    private companion object {
        /** What the stateless server answers (notifications, having no id, pass through anyway). */
        val HANDLED = setOf("initialize", "ping", "tools/list", "tools/call")
    }
}

@org.springframework.stereotype.Component
class McpTools(
    private val tasks: TaskService,
    private val calendar: CalendarService,
    private val refs: RefService,
    private val triage: TriageService,
    private val clock: Clock,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val base = props.publicUrl

    fun all(m: McpJsonMapper): List<AsyncToolSpecification> = listOf(
        tool(
            m, "list_tasks", "List tasks",
            "Tasks, most urgent first. view: active (todo and in progress; the default), backlog, all (closed too), or due_soon (open, due in the next 7 days). Optionally only one project (its name) or titles containing a text.",
            """{"type":"object","properties":{"view":{"type":"string","enum":["active","backlog","all","due_soon"]},"project":{"type":"string"},"query":{"type":"string"}}}""",
            readOnly = true,
        ) { a ->
            val projects = tasks.projects()
            val project = (a["project"] as? String)?.let { name -> projects.find { it.name.equals(name.trim(), true) } ?: return@tool "No project named \"$name\"." }
            val today = LocalDate.now(clock.withZone(props.zone))
            val filter = when (a["view"] as? String ?: "active") {
                "backlog" -> TaskFilter(setOf(TaskStatus.BACKLOG))
                "all" -> TaskFilter()
                "due_soon" -> TaskFilter(setOf(TaskStatus.BACKLOG, TaskStatus.TODO, TaskStatus.IN_PROGRESS), dueTo = today.plusDays(7))
                else -> TaskFilter(setOf(TaskStatus.TODO, TaskStatus.IN_PROGRESS))
            }.copy(projectId = project?.id, q = (a["query"] as? String)?.takeIf { it.isNotBlank() })
            val names = projects.associate { it.id to it.name }
            val list = tasks.tasks(filter).sortedWith(compareBy({ it.due?.date ?: LocalDate.MAX }, { it.priority.level.let { p -> if (p == 0) 9 else p } }))
            if (list.isEmpty()) "No tasks." else list.take(100).joinToString("\n") { line(it, names) }
        },
        tool(
            m, "get_task", "Get a task", "A task by its number (e.g. 12 or \"#12\"): title, status, due date, project, description, sub-tasks and relations.",
            """{"type":"object","properties":{"number":{"type":"string"}},"required":["number"]}""",
            readOnly = true,
        ) { a ->
            val n = a["number"].toString().trim().removePrefix("#").toLongOrNull() ?: return@tool "Give a task number."
            val t = tasks.tasks(TaskFilter()).find { it.number == n } ?: return@tool "No task #$n."
            val names = tasks.projects().associate { it.id to it.name }
            val subtasks = tasks.tasks(TaskFilter(parentId = t.id))
            buildString {
                appendLine(line(t, names))
                if (t.description.isNotBlank()) appendLine("\n${t.description}")
                if (subtasks.isNotEmpty()) appendLine("\nSub-tasks:\n" + subtasks.joinToString("\n") { "- " + line(it, names) })
                if (t.relations.isNotEmpty()) appendLine("\nRelations: " + t.relations.joinToString { "${it.kind.wire} #${it.task.number} ${plainText(it.task.title)}" })
                t.url?.let { appendLine("Source: $it") }
            }.trim()
        },
        tool(
            m, "list_projects", "List projects", "Projects (mostly courses) with their state, initiatives and how many of their tasks are done.",
            """{"type":"object","properties":{}}""",
            readOnly = true,
        ) { _ ->
            val initiatives = tasks.initiatives().associate { it.id to it.name }
            tasks.projects().joinToString("\n") { p ->
                "${p.name} · ${p.state.wire} · ${p.doneCount}/${p.taskCount} done" +
                    p.initiativeIds.mapNotNull(initiatives::get).takeIf { it.isNotEmpty() }?.joinToString(prefix = " · ").orEmpty()
            }.ifEmpty { "No projects." }
        },
        tool(
            m, "list_events", "List events", "Calendar events (classes, exams, meetings, contests, holidays) from one date to another (YYYY-MM-DD, at most 62 days).",
            """{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"}},"required":["from","to"]}""",
            readOnly = true,
        ) { a ->
            val from = LocalDate.parse(a["from"].toString())
            val to = LocalDate.parse(a["to"].toString()).let { if (it.isAfter(from.plusDays(62))) from.plusDays(62) else it }
            val names = tasks.projects().associate { it.id to it.name }
            calendar.occurrences(from, to).joinToString("\n") { e ->
                val time = e.start?.let { s -> "${s.toLocalDate()} ${s.toLocalTime().toString().take(5)}–${e.end?.toLocalTime()?.toString()?.take(5)}" }
                    ?: "${e.startDate}${if (e.endDate != e.startDate) "..${e.endDate}" else ""} (all day)"
                "$time · ${plainText(e.title)} · ${e.calendarName}" + (e.projectId?.let(names::get)?.let { " · $it" } ?: "")
            }.ifEmpty { "No events." }
        },
        tool(
            m, "search", "Search", "Search tasks, projects, events, PLMS course files and notices, and GitHub repos, issues and PRs by words in their titles.",
            """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""",
            readOnly = true,
        ) { a ->
            // Mail stays out: only the user reads mail.
            refs.search(a["query"].toString(), 20).filter { it.kind != RefKind.MAIL }
                .joinToString("\n") { "${it.kind.wire} · ${it.title}" + (it.context?.let { c -> " · $c" } ?: "") + " · ${it.url}" }.ifEmpty { "Nothing found." }
        },
        tool(
            m, "list_triage", "List Triage", "What's waiting in Triage: suggested tasks and events (from shared screenshots, mail, …) the user hasn't accepted or declined yet.",
            """{"type":"object","properties":{}}""",
            readOnly = true,
        ) { _ ->
            triage.list().filter { i -> i.items.any { it.status == "pending" } }.joinToString("\n") { i ->
                "${i.source}: ${i.title}" + (i.reason?.let { " (${it})" } ?: "") + "\n" +
                    i.items.filter { it.status == "pending" }.joinToString("\n") { "  - ${it.proposal.kind}: ${it.proposal.title}" + (it.proposal.due?.let { d -> " · due ${d.date}" } ?: "") }
            }.ifEmpty { "Triage is empty." }
        },
        tool(
            m, "send_to_triage", "Send to Triage",
            "Add something for the user to track (a task, deadline or event, described in text). It is read like a shared note and waits in their Triage as a suggestion until they accept it; nothing is made directly.",
            """{"type":"object","properties":{"text":{"type":"string","description":"What to track, with dates, times and details"},"title":{"type":"string","description":"A short label"}},"required":["text"]}""",
            readOnly = false,
        ) { a ->
            val input = triage.submit(SubmitRequest(source = "chatgpt", text = a["text"].toString().take(20_000), title = (a["title"] as? String).orEmpty()))
            "Sent to Triage (\"${input.title}\"). The user will see the suggestions there and accept the ones they want."
        },
    )

    private fun line(t: TaskResponse, projects: Map<UUID, String>) = listOfNotNull(
        "#${t.number} ${plainText(t.title)}",
        t.status.wire,
        t.priority.wire.takeIf { it != "none" },
        t.due?.let { "due ${it.date}" + (it.time?.let { tm -> " ${tm.toString().take(5)}" } ?: "") + if (t.overdue) " (overdue)" else "" },
        t.projectId?.let(projects::get),
        AppLinks.task(base, t.id),
    ).joinToString(" · ")

    /** A tool whose handler returns text; errors come back as tool errors, not failures of the call. */
    private fun tool(
        m: McpJsonMapper, name: String, title: String, description: String, schema: String, readOnly: Boolean,
        handler: suspend (Map<String, Any?>) -> String,
    ): AsyncToolSpecification = AsyncToolSpecification.builder()
        .tool(
            McpSchema.Tool.builder().name(name).title(title).description(description).inputSchema(m, schema)
                .annotations(McpSchema.ToolAnnotations(title, readOnly, false, readOnly, false, false)).build(),
        )
        .callHandler { _, req ->
            mono {
                runCatching { handler(req.arguments().orEmpty()) }.fold(
                    { McpSchema.CallToolResult.builder().addTextContent(it).build() },
                    { e ->
                        log.warn("MCP tool {} failed: {}", name, e.toString())
                        McpSchema.CallToolResult.builder().addTextContent("Error: ${e.message ?: e.javaClass.simpleName}").isError(true).build()
                    },
                )
            }
        }
        .build()
}
