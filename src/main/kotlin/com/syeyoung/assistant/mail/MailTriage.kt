package com.syeyoung.assistant.mail

import com.syeyoung.assistant.common.req
import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.refs.AppLinks
import com.syeyoung.assistant.triage.TriageService
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Mail into Triage: every new mail in the watched folders (all but sent, drafts, deleted, junk…)
 * becomes an input, once, with what tells whether it's personal (am I in To or Cc, which folder).
 * The mailbox is only read, never changed: nothing is marked as read.
 */
@Component
class MailTriage(
    private val accounts: MailAccounts,
    private val mailbox: Mailbox,
    private val triage: TriageService,
    private val db: DatabaseClient,
    private val clock: Clock,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val config = props.sources.mail
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!props.sources.schedulerEnabled || !config.triage || config.clientId.isBlank()) return
        scope.launch {
            delay(props.sources.initialDelay.toMillis())
            while (true) {
                runCatching { poll() }.onFailure { if (it !is MailNotConnected) log.warn("Mail triage failed: {}", it.toString()) }
                delay(config.triageInterval.toMillis())
            }
        }
    }

    /** Takes in mail that arrived since the last look; returns how many became inputs. */
    suspend fun poll(): Int = lock.withLock {
        val (user, token) = accounts.accessToken()
        val marks = db.sql("select * from mail_folders").map { r -> r.req<String>("folder") to FolderMark(r.req("uid_validity"), r.req("last_uid")) }
            .flow().toList().toMap()
        val batch = mailbox.incoming(user, token, marks, Instant.now(clock).minus(config.triageBacklog), BATCH)
        var taken = 0
        for (m in batch.mail.sortedBy { it.received }) {
            if (m.fromAddress.equals(user, ignoreCase = true) || m.autoReply) continue
            val id = m.messageId ?: continue
            if (triage.submitMail(m.subject, describe(m, user), AppLinks.mail(id), m.from)) taken++
        }
        for ((folder, mark) in batch.marks) {
            db.sql(
                """
                insert into mail_folders (folder, uid_validity, last_uid) values (:folder, :validity, :uid)
                on conflict (folder) do update set uid_validity = :validity, last_uid = :uid
                """,
            ).bind("folder", folder).bind("validity", mark.uidValidity).bind("uid", mark.lastUid).fetch().awaitRowsUpdated()
        }
        if (taken > 0) log.info("Mail triage: {} new mail", taken)
        taken
    }

    /** The mail as the model reads it: who, to whom (personal or bulk), where, and the text. */
    private fun describe(m: IncomingMail, user: String): String {
        val me = { list: List<String> -> list.any { it.contains(user, ignoreCase = true) } }
        val addressed = when {
            me(m.to) -> "I'm in To (personal)"
            me(m.cc) -> "I'm in Cc"
            else -> "I'm not in To or Cc (BCC or a mailing list: bulk)"
        }
        val body = (m.text?.takeIf { it.isNotBlank() } ?: m.html?.let(::htmlToText).orEmpty()).trim().take(BODY_LIMIT)
        return buildString {
            append("From: ").append(m.from).append('\n')
            append("To: ").append(m.to.take(5).joinToString(", ")).append(if (m.to.size > 5) " and ${m.to.size - 5} more" else "").append('\n')
            if (m.cc.isNotEmpty()) append("Cc: ").append(m.cc.take(5).joinToString(", ")).append('\n')
            append("Addressed: ").append(addressed).append('\n')
            append("Folder: ").append(m.folder).append('\n')
            m.received?.let { append("Received: ").append(STAMP.format(it.atZone(props.zone))).append('\n') }
            append("Subject: ").append(m.subject).append('\n')
            if (m.attachments.isNotEmpty()) append("Attachments: ").append(m.attachments.joinToString(", ")).append('\n')
            append('\n').append(body)
        }
    }

    @PreDestroy
    fun stop() = scope.cancel()

    companion object {
        private const val BATCH = 200
        private const val BODY_LIMIT = 8_000
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE) HH:mm")

        /** An HTML body as plain text: blocks become lines, tags go, common entities are decoded. */
        fun htmlToText(html: String): String = html
            .replace(Regex("""(?is)<(script|style|head)\b.*?</\1>"""), "")
            .replace(Regex("""(?i)<br\s*/?>|</(p|div|li|tr|h\d|table|blockquote)>"""), "\n")
            .replace(Regex("""(?s)<[^>]+>"""), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("""\n{3,}"""), "\n\n")
    }
}
