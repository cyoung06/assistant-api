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
import com.syeyoung.assistant.llm.ChatGptAccount
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import org.springframework.web.bind.annotation.PutMapping
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

/** What the model is told about the user, to judge which mail matters (edited in Triage). */
data class TriageProfile(val text: String)

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
    /** Mail: who sent it. */
    val sender: String?,
    /** Mail: act (something for me), maybe (unsure it applies) or nothing, and why. */
    val verdict: String?,
    val reason: String?,
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
    private val account: ChatGptAccount,
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
    suspend fun submit(req: SubmitRequest): TriageInputResponse = input(insert(req, sender = null))

    /** A mail, once: false when it was already taken in (by its mid: link). */
    suspend fun submitMail(title: String, text: String, link: String, sender: String): Boolean =
        submitOnce("mail", title.ifBlank { "(no subject)" }, text, link, sender)

    /** Something from [source] (mail, a PLMS post), once: false when it was already taken in (by its [link]). */
    suspend fun submitOnce(source: String, title: String, text: String, link: String, sender: String?): Boolean {
        val known = db.sql("select 1 from triage_inputs where source = :source and link = :link").bind("source", source).bind("link", link).map { _ -> 1 }.awaitOneOrNull()
        if (known != null) return false
        insert(SubmitRequest(source = source, text = text, title = title, link = link), sender)
        return true
    }

    private suspend fun insert(req: SubmitRequest, sender: String?): UUID {
        if (req.text.isBlank() && req.attachmentIds.isEmpty()) throw ValidationException("Nothing to triage")
        val id = UUID.randomUUID()
        val title = req.title.trim().ifEmpty {
            req.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80)
                ?: if (req.attachmentIds.size > 1) "${req.attachmentIds.size} screenshots" else "Screenshot"
        }
        db.sql("insert into triage_inputs (id, source, text, title, link, sender, created_at, retry_at) values (:id, :source, :text, :title, :link, :sender, :now, :now)")
            .bind("id", id).bind("source", req.source.take(20)).bind("text", req.text.take(TEXT_LIMIT)).bind("title", title)
            .let { s -> req.link?.let { s.bind("link", it) } ?: s.bindNull("link", String::class.java) }
            .let { s -> sender?.let { s.bind("sender", it) } ?: s.bindNull("sender", String::class.java) }
            .bind("now", Instant.now(clock)).fetch().awaitRowsUpdated()
        attachments.holdForInput(id, req.attachmentIds)
        wake.trySend(Unit)
        return id
    }

    // ---- what the model knows about the user --------------------------------------------

    suspend fun profile(): TriageProfile =
        TriageProfile(db.sql("select value from settings where key = 'triage.profile'").map { r -> r.req<String>("value") }.awaitOneOrNull() ?: DEFAULT_PROFILE)

    suspend fun setProfile(p: TriageProfile): TriageProfile {
        db.sql("insert into settings (key, value) values ('triage.profile', :v) on conflict (key) do update set value = :v")
            .bind("v", p.text.take(8_000)).fetch().awaitRowsUpdated()
        return profile()
    }

    /**
     * How to judge a mail: the user's profile (with this semester's courses), the bar for
     * suggesting anything, and how the user decided on similar mail before.
     */
    private suspend fun mailGuidance(input: InputRow): String {
        val courses = db.sql(
            """
            select distinct p.name from projects p join project_initiatives pi on pi.project_id = p.id join initiatives i on i.id = pi.initiative_id
            where i.status = 'active' and p.state in ('backlog', 'planned', 'in_progress') order by p.name
            """,
        ).map { r -> r.req<String>("name") }.flow().toList()
        val examples = pastDecisions(input)
        return buildString {
            append("This is a mail. About me:\n").append(profile().text.trim()).append("\n")
            if (courses.isNotEmpty()) append("My courses and projects this semester: ").append(courses.joinToString(", ")).append(".\n")
            append(
                """
                |
                |Decide whether this mail asks something of me or tells me something I must act on or attend:
                |- verdict "act": a deadline or required action that applies to me (submit, register, pay, reply, bring, prepare),
                |  an event I'm expected at (class change, exam, a meeting I'm invited to), or a change to something I already have
                |  (a moved deadline: give sameAsTask). Return the tasks/events to make.
                |- verdict "maybe": it might apply to me but the mail doesn't make it clear (audience, year, department). Return the items.
                |- verdict "nothing": no items. Informative, from the school, or mentioning a date is not enough on its own.
                |Check that it applies to me: its audience (학부/대학원, department, year, dorm building) and my courses.
                |Bulk mail (I'm not in To/Cc) is usually nothing and personal mail usually something, but follow the content.
                |reason: one short sentence why, in the mail's language.
                """.trimMargin(),
            )
            if (examples.isNotEmpty()) append("\n\nHow I decided on earlier mail (follow my taste):\n").append(examples.joinToString("\n"))
        }
    }

    /** Up to 20 earlier mail decisions, the same sender's first: "From …, "subject" → accepted: task "…"". */
    private suspend fun pastDecisions(input: InputRow): List<String> {
        val rows = db.sql(
            """
            select i.id, i.title, i.sender, i.verdict, i.reason from triage_inputs i
            where i.source = 'mail' and i.status = 'done' and i.id <> :id
              and (i.verdict = 'nothing' or not exists (select 1 from triage_items x where x.input_id = i.id and x.status = 'pending'))
            order by (i.sender is not distinct from :sender) desc, i.analyzed_at desc limit 20
            """,
        ).bind("id", input.id).let { s -> input.sender?.let { s.bind("sender", it) } ?: s.bindNull("sender", String::class.java) }
            .map { r -> listOf(r.req<UUID>("id").toString(), r.req<String>("title"), r.opt<String>("sender").orEmpty(), r.opt<String>("verdict").orEmpty(), r.opt<String>("reason").orEmpty()) }
            .flow().toList()
        return rows.map { (id, title, sender, verdict, reason) ->
            val items = db.sql("select proposal, status from triage_items where input_id = :id order by position").bind("id", UUID.fromString(id))
                .map { r -> mapper.readValue(r.req<Json>("proposal").asString(), Proposal::class.java) to r.req<String>("status") }.flow().toList()
            val outcome = when {
                verdict == "nothing" || items.isEmpty() -> "nothing" + reason.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                items.any { it.second == "accepted" } -> "accepted: " + items.filter { it.second == "accepted" }.joinToString("; ") { "${it.first.kind} \"${it.first.title}\"" }
                else -> "declined"
            }
            "- From $sender, \"$title\" → $outcome"
        }
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

    /**
     * Reads every input that's due, [AppProperties.Llm.triageConcurrency] at a time, oldest first.
     * Stops for now when the model isn't there, the backend asked to slow down (HTTP 429), or the
     * day's reading is used up; those inputs just wait, without counting as failed tries.
     */
    suspend fun drain() = reading.withLock {
        while (true) {
            val now = Instant.now(clock)
            account.limitedUntil?.takeIf { it.isAfter(now) }?.let { until -> return@withLock postponeDue(until) }
            val dayStart = LocalDate.now(clock.withZone(props.zone)).atStartOfDay(props.zone).toInstant()
            val readToday = db.sql("select count(*) as n from triage_inputs where analyzed_at >= :start").bind("start", dayStart)
                .map { r -> r.req<Long>("n") }.awaitOneOrNull() ?: 0
            val room = (props.llm.triageDailyLimit - readToday).toInt()
            val batch = db.sql("select * from triage_inputs where status = 'pending' and retry_at <= :now order by created_at limit :n")
                .bind("now", now).bind("n", props.llm.triageConcurrency.coerceAtLeast(1)).map(::inputRow).flow().toList()
            if (batch.isEmpty()) return@withLock
            if (!model.available()) return@withLock batch.forEach { postpone(it.id, now.plus(WAIT_FOR_MODEL), null) }
            if (room <= 0) return@withLock postponeDue(dayStart.plus(Duration.ofDays(1)))
            coroutineScope { batch.take(room).map { async { read(it) } }.awaitAll() }
        }
    }

    /** Every due input waits until [until]. */
    private suspend fun postponeDue(until: Instant) {
        db.sql("update triage_inputs set retry_at = :until where status = 'pending' and retry_at < :until").bind("until", until).fetch().awaitRowsUpdated()
    }

    private suspend fun read(input: InputRow) {
        val now = Instant.now(clock)
        val images = attachments.forInput(input.id).filter { it.contentType.startsWith("image/") }.map { it.id }
        try {
            val mail = input.source == "mail"
            val result = capture.analyze(AnalyzeRequest(input.text, images), openTasks = true, guidance = if (mail) mailGuidance(input) else null, light = mail)
            // "nothing" keeps its reason but no suggestions.
            val items = if (mail && result.verdict == "nothing") emptyList() else result.items
            items.forEachIndexed { i, p ->
                db.sql("insert into triage_items (id, input_id, position, proposal, same_as) values (:id, :input, :pos, :proposal, :same)")
                    .bind("id", UUID.randomUUID()).bind("input", input.id).bind("pos", i).bind("proposal", Json.of(mapper.writeValueAsString(p)))
                    .let { s -> p.sameAs?.let { s.bind("same", it) } ?: s.bindNull("same", java.lang.Long::class.java) }
                    .fetch().awaitRowsUpdated()
            }
            db.sql("update triage_inputs set status = 'done', error = null, analyzed_at = :now, verdict = :verdict, reason = :reason where id = :id")
                .bind("now", now).bind("id", input.id)
                .let { s -> result.verdict?.takeIf { mail }?.let { s.bind("verdict", it) } ?: s.bindNull("verdict", String::class.java) }
                .let { s -> result.reason?.let { s.bind("reason", it.take(500)) } ?: s.bindNull("reason", String::class.java) }
                .fetch().awaitRowsUpdated()
        } catch (e: ApiException) {
            // Not connected (or connection lost), or told to slow down: wait without counting it as a failed try.
            if (e is ConflictException && e.code == "not_connected") return postpone(input.id, now.plus(WAIT_FOR_MODEL), e.message)
            account.limitedUntil?.takeIf { it.isAfter(now) }?.let { return postpone(input.id, it, "Waiting: the model asked to slow down") }
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
        val sender: String?, val verdict: String?, val reason: String?,
    )

    private inner class ItemRow(val id: UUID, val inputId: UUID, val proposal: Proposal, val sameAs: Long?, val status: String, val result: String?) {
        fun response() = TriageItemResponse(id, proposal, sameAs, status, result)
    }

    private fun inputRow(r: io.r2dbc.spi.Readable) = InputRow(
        r.req("id"), r.req("source"), r.req("text"), r.req("title"), r.opt("link"), r.req("status"),
        r.req("attempts"), r.opt("error"), r.req("created_at"), r.opt("analyzed_at"),
        r.opt("sender"), r.opt("verdict"), r.opt("reason"),
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
            i.id, i.source, i.title, i.text, i.link, i.status, i.error, i.sender, i.verdict, i.reason, attachments.forInput(i.id), items.map { it.response() },
            i.createdAt, i.analyzedAt,
        )
    }

    @PreDestroy
    fun stop() = scope.cancel()

    private companion object {
        const val TEXT_LIMIT = 20_000
        const val MAX_ATTEMPTS = 4
        val WAIT_FOR_MODEL: Duration = Duration.ofMinutes(5)

        /** A starting point; the user edits it in Triage. */
        val DEFAULT_PROFILE = """
            I'm a POSTECH undergraduate student (무은재학부, going into CSE).
            Matters to me: deadlines, exams, class changes, anything asking me to submit, reply, register or pay, meetings I'm
            part of, notices for my dorm, tuition and scholarships.
            Doesn't matter: seminars and talks (unless CS or AI), notices for graduate students or other departments, job ads,
            office reorganizations, newsletters, optional surveys.
        """.trimIndent()
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

    /** What the model is told about you, to judge which mail matters. */
    @GetMapping("/profile")
    suspend fun triageProfile(): TriageProfile = service.profile()

    @PutMapping("/profile")
    suspend fun setTriageProfile(@RequestBody profile: TriageProfile): TriageProfile = service.setProfile(profile)

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
