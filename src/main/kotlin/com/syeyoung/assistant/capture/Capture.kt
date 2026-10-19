package com.syeyoung.assistant.capture

import ai.koog.http.client.spring.SpringWebClientKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.collectText
import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.llm.ChatGptAccount
import com.syeyoung.assistant.llm.ChatGptNotConnected
import com.syeyoung.assistant.tasks.AttachmentRepository
import com.syeyoung.assistant.tasks.DueDto
import com.syeyoung.assistant.tasks.Priority
import com.syeyoung.assistant.tasks.TaskRepository
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// ---- what the model returns ---------------------------------------------------------

/** Everything the model found. Dates and times are local (the user's zone) ISO strings. */
@Serializable
data class LlmCapture(val items: List<LlmItem> = emptyList())

/**
 * One thing to do or attend. kind "task": something to get done, with [dueDate] (YYYY-MM-DD) and
 * optional [dueTime] (HH:MM). kind "event": something happening at a time, with [start]/[end]
 * (YYYY-MM-DDTHH:MM), or [allDayDate] for a whole day.
 */
@Serializable
data class LlmItem(
    val kind: String,
    val title: String,
    val description: String = "",
    val dueDate: String? = null,
    val dueTime: String? = null,
    val start: String? = null,
    val end: String? = null,
    val allDayDate: String? = null,
    val project: String? = null,
    val labels: List<String> = emptyList(),
    /** urgent, high, medium, low, or null. */
    val priority: String? = null,
)

/** An image (or more) and text to read. An interface so tests can stand in for the model. */
interface CaptureModel {
    /** Whether it can be used now (signed in). */
    suspend fun available(): Boolean
    suspend fun read(instructions: String, text: String, images: List<CaptureImage>): LlmCapture
}

class CaptureImage(val bytes: ByteArray, val mimeType: String, val fileName: String)

/**
 * The model through the ChatGPT sign-in: Koog's OpenAI client on the Responses API at the Codex
 * backend, which only streams and doesn't keep responses (store=false). Each request gets a fresh
 * access token and the account header from [ChatGptAccount]; the quota it reports is kept there.
 */
@Component
class KoogCaptureModel(webClient: WebClient.Builder, private val account: ChatGptAccount, private val props: AppProperties) : CaptureModel {
    // The model ID comes from config; capabilities (vision included) from Koog's closest known model.
    private val model = OpenAIModels.Chat.GPT5_6Luna.copy(id = props.llm.model)

    private val client by lazy {
        val signedIn = ExchangeFilterFunction.ofRequestProcessor { req ->
            mono {
                val c = account.credentials()
                ClientRequest.from(req).headers { h ->
                    h.setBearerAuth(c.accessToken)
                    h.set("ChatGPT-Account-Id", c.accountId)
                    // Koog's WebClient adapter labels streaming requests text/plain; the backend wants JSON.
                    h.contentType = MediaType.APPLICATION_JSON
                }.build()
            }
        }
        val quota = ExchangeFilterFunction.ofResponseProcessor { res ->
            account.recordQuota(res.headers().asHttpHeaders())
            if (res.statusCode().value() == 401) account.forgetAccessToken()
            Mono.just(res.mutate().body(::standardEvents).build())
        }
        OpenAILLMClient(
            // Replaced on each request by the filter above.
            apiKey = "chatgpt",
            settings = OpenAIClientSettings(baseUrl = props.llm.baseUrl, responsesAPIPath = "responses"),
            httpClientFactory = SpringWebClientKoogHttpClient.Factory(webClient.clone().filter(signedIn).filter(quota)),
        )
    }

    override suspend fun available() = account.connected()

    override suspend fun read(instructions: String, text: String, images: List<CaptureImage>): LlmCapture = withTimeout(props.llm.timeout.toMillis()) {
        // The Codex backend takes the system prompt as "instructions" (not a developer message).
        val params = OpenAIResponsesParams(
            schema = LLMParams.Schema.JSON.Standard("capture", SCHEMA),
            store = false,
            additionalProperties = mapOf("instructions" to JsonPrimitive(instructions)),
        )
        val request = prompt("capture", params) {
            user {
                text(text.ifBlank { "Read the attached image(s)." })
                for (i in images) {
                    image(AttachmentSource.Image(AttachmentContent.Binary.Bytes(i.bytes), i.mimeType.substringAfter('/'), i.mimeType, i.fileName))
                }
            }
        }
        val reply = client.executeStreaming(request, model, emptyList()).collectText()
        json.decodeFromString<LlmCapture>(reply)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The event stream without the Codex backend's own events (e.g. "response.metadata"), which
     * Koog's decoder rejects: only the standard events a text reply is made of pass. Events are cut
     * at blank lines on the raw bytes, so text split across network chunks stays whole.
     */
    private fun standardEvents(body: Flux<DataBuffer>): Flux<DataBuffer> = Flux.defer {
        val pending = java.io.ByteArrayOutputStream()
        val complete = { flush: Boolean ->
            val bytes = pending.toByteArray()
            val events = mutableListOf<ByteArray>()
            var start = 0
            var i = 0
            while (i < bytes.size - 1) {
                val end = when {
                    bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() -> i + 2
                    i < bytes.size - 3 && bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                        bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte() -> i + 4
                    else -> -1
                }
                if (end > 0) { events += bytes.copyOfRange(start, end); start = end; i = end } else i++
            }
            if (flush && start < bytes.size) { events += bytes.copyOfRange(start, bytes.size); start = bytes.size }
            pending.reset()
            pending.write(bytes, start, bytes.size - start)
            events
        }
        body.concatMapIterable { b ->
            val chunk = ByteArray(b.readableByteCount()).also(b::read)
            DataBufferUtils.release(b)
            pending.write(chunk)
            complete(false)
        }.concatWith(Flux.defer { Flux.fromIterable(complete(true)) })
            .filter { e ->
                val text = String(e, Charsets.UTF_8)
                val keep = EVENT_TYPE.find(text)?.groupValues?.get(1)?.let { it in STANDARD_EVENTS } ?: true
                if (!keep) log.debug("Dropped stream event: {}", text.trim().take(2000))
                keep
            }
            .map { DefaultDataBufferFactory.sharedInstance.wrap(it) }
    }

    companion object {
        private val EVENT_TYPE = Regex("""^data:\s*\{\s*"type"\s*:\s*"([^"]+)"""", RegexOption.MULTILINE)
        private val STANDARD_EVENTS = setOf(
            "response.created", "response.in_progress", "response.queued", "response.completed", "response.failed", "response.incomplete", "error",
            "response.output_item.added", "response.output_item.done", "response.content_part.added", "response.content_part.done",
            "response.output_text.delta", "response.output_text.done", "response.refusal.delta", "response.refusal.done",
        )

        /** [LlmCapture] as a strict JSON schema: every field listed, the optional ones nullable. */
        val SCHEMA = Json.parseToJsonElement(
            """
            {
              "type": "object", "additionalProperties": false, "required": ["items"],
              "properties": {
                "items": {
                  "type": "array",
                  "items": {
                    "type": "object", "additionalProperties": false,
                    "required": ["kind", "title", "description", "dueDate", "dueTime", "start", "end", "allDayDate", "project", "labels", "priority"],
                    "properties": {
                      "kind": { "type": "string", "enum": ["task", "event"] },
                      "title": { "type": "string" },
                      "description": { "type": "string" },
                      "dueDate": { "type": ["string", "null"], "description": "YYYY-MM-DD" },
                      "dueTime": { "type": ["string", "null"], "description": "HH:MM, 24h" },
                      "start": { "type": ["string", "null"], "description": "YYYY-MM-DDTHH:MM" },
                      "end": { "type": ["string", "null"], "description": "YYYY-MM-DDTHH:MM" },
                      "allDayDate": { "type": ["string", "null"], "description": "YYYY-MM-DD" },
                      "project": { "type": ["string", "null"] },
                      "labels": { "type": "array", "items": { "type": "string" } },
                      "priority": { "type": ["string", "null"], "enum": ["urgent", "high", "medium", "low", null] }
                    }
                  }
                }
              }
            }
            """,
        ).jsonObject
    }
}

// ---- API ---------------------------------------------------------------------------

data class AnalyzeRequest(val text: String = "", val attachmentIds: List<UUID> = emptyList())

/**
 * A proposed task (with [due]) or event (timed [start]/[end], or all-day [startDate]..[endDate]).
 * Nothing is created until the client creates the ones the user keeps.
 */
data class Proposal(
    val kind: String,
    val title: String,
    val description: String,
    val due: DueDto? = null,
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val projectId: UUID? = null,
    val labelIds: List<UUID> = emptyList(),
    val priority: Priority = Priority.NONE,
)

data class AnalyzeResponse(val items: List<Proposal>)

@Service
class CaptureService(
    private val model: CaptureModel,
    private val attachments: AttachmentRepository,
    private val tasks: TaskRepository,
    private val clock: Clock,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = props.zone

    /** Reads shared text and screenshots and proposes tasks and events. Runs only when asked. */
    suspend fun analyze(req: AnalyzeRequest): AnalyzeResponse {
        if (!model.available()) throw ConflictException("not_connected", "Connect ChatGPT in Sources to analyze")
        if (req.text.isBlank() && req.attachmentIds.isEmpty()) throw ValidationException("Nothing to analyze")
        val images = req.attachmentIds.map { id ->
            val a = attachments.get(id) ?: throw ValidationException("No attachment $id")
            if (!a.info.contentType.startsWith("image/")) throw ValidationException("${a.info.filename} isn't an image")
            CaptureImage(a.bytes, a.info.contentType, a.info.filename)
        }
        val projects = tasks.projects()
        val labels = tasks.labels()
        val now = ZonedDateTime.now(clock.withZone(zone))
        val instructions = """
            You turn what a university student shares (screenshots of notices, syllabi, chats, posters, emails, or text)
            into things to track. Return every distinct item; return none if there is nothing actionable.
            - kind "task": something the student must do or submit, with dueDate (YYYY-MM-DD) and dueTime (HH:MM, 24h) when stated.
            - kind "event": something happening at a time (class, exam, meeting, deadline-less event), with start and end
              (YYYY-MM-DDTHH:MM, 24h; if only a start is given, make it one hour), or allDayDate for whole-day events.
            - Now is ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd (EEEE) HH:mm"))} in ${zone.id}; resolve relative dates
              ("next Friday", "내일") from that. Times are in ${zone.id}.
            - title: short and specific, in the source's language. description: the useful details (place, scope, links),
              briefly.
            - project: one of these if it clearly fits, else null: ${projects.joinToString(", ") { "\"${it.name}\"" }.ifEmpty { "(none)" }}.
            - labels: any of these that fit: ${labels.joinToString(", ") { "\"${it.name}\"" }.ifEmpty { "(none)" }}.
            - priority: only if the source makes it clearly urgent or important.
        """.trimIndent()
        val result = try {
            model.read(instructions, req.text, images)
        } catch (e: ValidationException) {
            throw e
        } catch (e: ChatGptNotConnected) {
            throw ConflictException("not_connected", e.message ?: "Connect ChatGPT in Sources")
        } catch (e: Exception) {
            log.warn("Capture failed: {}", e.toString())
            throw ConflictException("model_failed", "The model couldn't read this: ${e.message ?: e.javaClass.simpleName}")
        }
        val projectByName = projects.associateBy { it.name.lowercase() }
        val labelByName = labels.associateBy { it.name.lowercase() }
        return AnalyzeResponse(result.items.mapNotNull { item ->
            val title = item.title.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val base = Proposal(
                kind = "task", title = title, description = item.description.trim(),
                projectId = item.project?.lowercase()?.let(projectByName::get)?.id,
                labelIds = item.labels.mapNotNull { labelByName[it.lowercase()]?.id }.distinct(),
                priority = item.priority?.let { p -> Priority.entries.find { it.wire == p.lowercase() } } ?: Priority.NONE,
            )
            if (item.kind == "event") event(item, base) ?: base.copy(due = due(item))
            else base.copy(due = due(item))
        })
    }

    private fun due(i: LlmItem): DueDto? {
        val date = i.dueDate?.let(::date) ?: return null
        return DueDto(date, i.dueTime?.let(::time))
    }

    /** A timed or all-day event; null when the model gave no usable time (it then becomes a task). */
    private fun event(i: LlmItem, base: Proposal): Proposal? {
        i.allDayDate?.let(::date)?.let { return base.copy(kind = "event", startDate = it, endDate = it) }
        val start = i.start?.let(::dateTime) ?: return null
        val end = i.end?.let(::dateTime)?.takeIf { it.isAfter(start) } ?: start.plusHours(1)
        return base.copy(kind = "event", start = start.atZone(zone).toOffsetDateTime(), end = end.atZone(zone).toOffsetDateTime())
    }

    private fun date(s: String) = runCatching { LocalDate.parse(s.trim().take(10)) }.getOrNull()
    private fun time(s: String) = runCatching { LocalTime.parse(s.trim().take(5)) }.getOrNull()
    private fun dateTime(s: String) = runCatching { LocalDateTime.parse(s.trim().take(16)) }.getOrNull()
}

@RestController
@RequestMapping("/api/v1/capture")
class CaptureController(private val service: CaptureService) {

    /** Proposes tasks and events from text and uploaded images; creates nothing. */
    @PostMapping("/analyze")
    suspend fun analyze(@RequestBody req: AnalyzeRequest): AnalyzeResponse = service.analyze(req)
}
