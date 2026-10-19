package com.syeyoung.assistant.triage

import com.syeyoung.assistant.calendar.CalendarService
import com.syeyoung.assistant.calendar.CreateEventRequest
import com.syeyoung.assistant.capture.AnalyzeRequest
import com.syeyoung.assistant.capture.CaptureModel
import com.syeyoung.assistant.capture.CaptureService
import com.syeyoung.assistant.capture.Proposal
import com.syeyoung.assistant.common.ApiException
import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.NotFoundException
import com.syeyoung.assistant.common.ValidationException
import com.syeyoung.assistant.common.opt
import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.refs.AppLinks
import com.syeyoung.assistant.tasks.AttachmentInfo
import com.syeyoung.assistant.tasks.AttachmentRepository
import com.syeyoung.assistant.tasks.CreateTaskRequest
import com.syeyoung.assistant.tasks.TaskService
import io.r2dbc.postgresql.codec.Json
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ---- API shapes ------------------------------------------------------------------

data class SubmitRequest(
    /** share, capture, addin, … */
    val source: String = "share",
    val text: String = "",
    /** Uploaded screenshots (POST /api/v1/attachments first). */
    val attachmentIds: List<UUID> = emptyList(),
    /** A short label for the list; made up from the text when empty. */
    val title: String = "",
    /** Where it came from, e.g. mid:<Message-ID>; accepted tasks link back to it. */
    val link: String? = null,
)

data class TriageItemResponse(
    val id: UUID,
    val proposal: Proposal,
    /** The number of an open task this seems to repeat. */
    val sameAs: Long?,
    /** pending · accepted · declined */
    val status: String,
    /** What accepting made: a link to the task or event. */
    val result: String?,
)

data class TriageInputResponse(
    val id: UUID,
    val source: String,
    val title: String,
    val text: String,
    val link: String?,
    /** pending (waiting for the model) · done · failed */
    val status: String,
    val error: String?,
    val attachments: List<AttachmentInfo>,
    val items: List<TriageItemResponse>,
    val createdAt: Instant,
    val analyzedAt: Instant?,
)

// ---- service -----------------------------------------------------------------------

/**
 * Triage: everything shared or sent in is kept as an input, read by the model in the background,
 * and turned into suggestions that become tasks or events only when accepted. The model never
 * acts; at worst it suggests something to decline.
 */
@Service
class TriageService(
    private val db: DatabaseClient,
    private val attachments: AttachmentRepository,
    private val capture: CaptureService,
    private val model: CaptureModel,
    private val tasks: TaskService,
    private val calendar: CalendarService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val reading = Mutex()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!props.sources.schedulerEnabled) return
        scope.launch {
            while (true) {
                runCatching { drain() }.onFailure { log.warn("Triage failed: {}", it.toString()) }
                withTimeoutOrNull(props.llm.triageInterval.toMillis()) { wake.receive() }
            }
        }
    }

    /** Keeps an input for the model to read soon; returns at once. */
    suspend fun submit(req: SubmitRequest): TriageInputResponse {
        if (req.text.isBlank() && req.attachmentIds.isEmpty()) throw ValidationException("Nothing to triage")
        val id = UUID.randomUUID()
        val title = req.title.trim().ifEmpty {
            req.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80)
                ?: if (req.attachmentIds.size > 1) "${req.attachmentIds.size} screenshots" else "Screenshot"
        }
        db.sql("insert into triage_inputs (id, source, text, title, link, created_at, retry_at) values (:id, :source, :text, :title, :link, :now, :now)")
            .bind("id", id).bind("source", req.source.take(20)).bind("text", req.text.take(TEXT_LIMIT)).bind("title", title)
            .let { s -> req.link?.let { s.bind("link", it) } ?: s.bindNull("link", String::class.java) }
            .bind("now", Instant.now(clock)).fetch().awaitRowsUpdated()
        attachments.holdForInput(id, req.attachmentIds)
        wake.trySend(Unit)
        return input(id)
    }

    /** Inputs of the last two weeks, newest first, with their suggestions. */
    suspend fun list(): List<TriageInputResponse> {
        val since = Instant.now(clock).minus(Duration.ofDays(14))
        val rows = db.sql("select * from triage_inputs where created_at >= :since or status = 'pending' order by created_at desc")
            .bind("since", since).map(::inputRow).flow().toList()
        return rows.map { render(it) }
    }

    suspend fun input(id: UUID): TriageInputResponse =
        render(db.sql("select * from triage_inputs where id = :id").bind("id", id).map(::inputRow).awaitOneOrNull() ?: throw NotFoundException("Input", id))

    /** Makes the task or event, with any edits, and links the input's screenshots to a task. */
    suspend fun accept(itemId: UUID, edited: Proposal?): TriageItemResponse {
        val item = item(itemId)
        if (item.status != "pending") throw ConflictException("decided", "This suggestion was already ${item.status}")
        val input = db.sql("select * from triage_inputs where id = :id").bind("id", item.inputId).map(::inputRow).awaitOneOrNull()!!
        val p = edited ?: item.proposal
        val from = input.link?.let { "From: [${input.title.replace(Regex("""([\[\]])"""), "\\\\$1")}]($it)" }
        val description = listOfNotNull(p.description.ifBlank { null }, from).joinToString("\n\n")
        val result = if (p.kind == "event") {
            val e = calendar.create(
                CreateEventRequest(p.title, description, p.start, p.end, p.startDate, p.endDate ?: p.startDate, projectId = p.projectId),
            )
            AppLinks.event(props.publicUrl, e.id)
        } else {
            val held = attachments.forInput(input.id).filter { it.id !in attachedElsewhere(input.id) }.map { it.id }
            val t = tasks.create(
                CreateTaskRequest(p.title, description, priority = p.priority, due = p.due, projectId = p.projectId, labelIds = p.labelIds, attachmentIds = held),
            )
            AppLinks.task(props.publicUrl, t.id)
        }
        db.sql("update triage_items set status = 'accepted', result = :result, proposal = :proposal, decided_at = :now where id = :id")
            .bind("result", result).bind("proposal", Json.of(mapper.writeValueAsString(p))).bind("now", Instant.now(clock)).bind("id", itemId)
            .fetch().awaitRowsUpdated()
        return item(itemId).response()
    }

    suspend fun decline(itemId: UUID): TriageItemResponse {
        val item = item(itemId)
        if (item.status != "pending") throw ConflictException("decided", "This suggestion was already ${item.status}")
        db.sql("update triage_items set status = 'declined', decided_at = :now where id = :id").bind("now", Instant.now(clock)).bind("id", itemId)
            .fetch().awaitRowsUpdated()
        return item(itemId).response()
    }

    /** Reads the input again: its undecided suggestions are replaced. */
    suspend fun retry(inputId: UUID): TriageInputResponse {
        input(inputId)
        db.sql("delete from triage_items where input_id = :id and status = 'pending'").bind("id", inputId).fetch().awaitRowsUpdated()
        db.sql("update triage_inputs set status = 'pending', attempts = 0, error = null, retry_at = :now where id = :id")
            .bind("now", Instant.now(clock)).bind("id", inputId).fetch().awaitRowsUpdated()
        wake.trySend(Unit)
        return input(inputId)
    }

    suspend fun dismiss(inputId: UUID) {
        input(inputId)
        db.sql("delete from triage_inputs where id = :id").bind("id", inputId).fetch().awaitRowsUpdated()
    }

    /** Reads every input that's due, one at a time; stops for now when the model isn't there or the day's reading is used up. */
    suspend fun drain() = reading.withLock {
        while (true) {
            val now = Instant.now(clock)
            val next = db.sql("select * from triage_inputs where status = 'pending' and retry_at <= :now order by created_at limit 1")
                .bind("now", now).map(::inputRow).awaitOneOrNull() ?: return@withLock
            if (!model.available()) return@withLock postpone(next.id, now.plus(WAIT_FOR_MODEL), null)
            val dayStart = LocalDate.now(clock.withZone(props.zone)).atStartOfDay(props.zone).toInstant()
            val readToday = db.sql("select count(*) as n from triage_inputs where analyzed_at >= :start").bind("start", dayStart)
                .map { r -> r.req<Long>("n") }.awaitOneOrNull() ?: 0
            if (readToday >= props.llm.triageDailyLimit) return@withLock postpone(next.id, dayStart.plus(Duration.ofDays(1)), null)
            read(next)
        }
    }

    private suspend fun read(input: InputRow) {
        val now = Instant.now(clock)
        val images = attachments.forInput(input.id).filter { it.contentType.startsWith("image/") }.map { it.id }
        try {
            val result = capture.analyze(AnalyzeRequest(input.text, images), openTasks = true)
            result.items.forEachIndexed { i, p ->
                db.sql("insert into triage_items (id, input_id, position, proposal, same_as) values (:id, :input, :pos, :proposal, :same)")
                    .bind("id", UUID.randomUUID()).bind("input", input.id).bind("pos", i).bind("proposal", Json.of(mapper.writeValueAsString(p)))
                    .let { s -> p.sameAs?.let { s.bind("same", it) } ?: s.bindNull("same", java.lang.Long::class.java) }
                    .fetch().awaitRowsUpdated()
            }
            db.sql("update triage_inputs set status = 'done', error = null, analyzed_at = :now where id = :id").bind("now", now).bind("id", input.id)
                .fetch().awaitRowsUpdated()
        } catch (e: ApiException) {
            // Not connected (or connection lost): wait without counting it as a failed try.
            if (e is ConflictException && e.code == "not_connected") return postpone(input.id, now.plus(WAIT_FOR_MODEL), e.message)
            fail(input, e.message ?: e.toString(), now)
        } catch (e: Exception) {
            fail(input, e.message ?: e.toString(), now)
        }
    }

    private suspend fun fail(input: InputRow, error: String, now: Instant) {
        val attempts = input.attempts + 1
        log.warn("Triage of {} failed ({}): {}", input.id, attempts, error)
        val status = if (attempts >= MAX_ATTEMPTS) "failed" else "pending"
        db.sql("update triage_inputs set status = :status, attempts = :attempts, error = :error, retry_at = :retry where id = :id")
            .bind("status", status).bind("attempts", attempts).bind("error", error.take(500))
            .bind("retry", now.plus(Duration.ofMinutes(5L * attempts * attempts))).bind("id", input.id).fetch().awaitRowsUpdated()
    }

    private suspend fun postpone(id: UUID, until: Instant, reason: String?) {
        db.sql("update triage_inputs set retry_at = :until, error = :error where id = :id").bind("until", until).bind("id", id)
            .let { s -> reason?.let { s.bind("error", it.take(500)) } ?: s.bindNull("error", String::class.java) }
            .fetch().awaitRowsUpdated()
    }

    /** Screenshots already given to an earlier accepted task from this input stay there. */
    private suspend fun attachedElsewhere(inputId: UUID): Set<UUID> =
        db.sql("select id from attachments where triage_input_id = :id and task_id is not null").bind("id", inputId)
            .map { r -> r.req<UUID>("id") }.flow().toList().toSet()

    // ---- rows ----------------------------------------------------------------------

    private data class InputRow(
        val id: UUID, val source: String, val text: String, val title: String, val link: String?, val status: String,
        val attempts: Int, val error: String?, val createdAt: Instant, val analyzedAt: Instant?,
    )

    private inner class ItemRow(val id: UUID, val inputId: UUID, val proposal: Proposal, val sameAs: Long?, val status: String, val result: String?) {
        fun response() = TriageItemResponse(id, proposal, sameAs, status, result)
    }

    private fun inputRow(r: io.r2dbc.spi.Readable) = InputRow(
        r.req("id"), r.req("source"), r.req("text"), r.req("title"), r.opt("link"), r.req("status"),
        r.req("attempts"), r.opt("error"), r.req("created_at"), r.opt("analyzed_at"),
    )

    private fun itemRow(r: io.r2dbc.spi.Readable) = ItemRow(
        r.req("id"), r.req("input_id"), mapper.readValue(r.req<Json>("proposal").asString(), Proposal::class.java),
        r.opt("same_as"), r.req("status"), r.opt("result"),
    )

    private suspend fun item(id: UUID): ItemRow =
        db.sql("select * from triage_items where id = :id").bind("id", id).map(::itemRow).awaitOneOrNull() ?: throw NotFoundException("Suggestion", id)

    private suspend fun render(i: InputRow): TriageInputResponse {
        val items = db.sql("select * from triage_items where input_id = :id order by position").bind("id", i.id).map(::itemRow).flow().toList()
        return TriageInputResponse(
            i.id, i.source, i.title, i.text, i.link, i.status, i.error, attachments.forInput(i.id), items.map { it.response() }, i.createdAt, i.analyzedAt,
        )
    }

    @PreDestroy
    fun stop() = scope.cancel()

    private companion object {
        const val TEXT_LIMIT = 20_000
        const val MAX_ATTEMPTS = 4
        val WAIT_FOR_MODEL: Duration = Duration.ofMinutes(5)
    }
}

// ---- controller -------------------------------------------------------------------

@RestController
@RequestMapping("/api/v1/triage")
class TriageController(private val service: TriageService) {

    /** Inputs of the last two weeks (and any still waiting), newest first, with their suggestions. */
    @GetMapping
    suspend fun triage(): List<TriageInputResponse> = service.list()

    /** Sends text and screenshots (uploaded first) to Triage; the model reads them in the background. */
    @PostMapping("/inputs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun submitTriage(@RequestBody req: SubmitRequest): TriageInputResponse = service.submit(req)

    @PostMapping("/inputs/{id}/retry")
    suspend fun retryTriage(@PathVariable id: UUID): TriageInputResponse = service.retry(id)

    @DeleteMapping("/inputs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun dismissTriage(@PathVariable id: UUID) = service.dismiss(id)

    /** Makes the suggestion a task or event; the body, when given, is the edited proposal. */
    @PostMapping("/items/{id}/accept")
    suspend fun acceptTriage(@PathVariable id: UUID, @RequestBody(required = false) edited: Proposal?): TriageItemResponse = service.accept(id, edited)

    @PostMapping("/items/{id}/decline")
    suspend fun declineTriage(@PathVariable id: UUID): TriageItemResponse = service.decline(id)
}
