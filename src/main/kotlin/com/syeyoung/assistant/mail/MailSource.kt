package com.syeyoung.assistant.mail

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.source.DataSource
import com.syeyoung.assistant.source.SourceRecord
import com.syeyoung.assistant.source.SourceSnapshot
import com.syeyoung.assistant.source.SourceTag
import com.syeyoung.assistant.source.SourceType
import com.syeyoung.assistant.tasks.TaskClosed
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reply later: every flagged mail in the inbox is a task (label "reply", project "Email").
 * Unflagging it or replying in any mail app completes the task; completing the task here clears
 * the mail's flag. Only the flags are read; the mail stays on the server.
 */
@Component
class MailSource(
    private val accounts: MailAccounts,
    private val mailbox: Mailbox,
    props: AppProperties,
) : DataSource {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = props.zone
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val type = SourceType.EMAIL
    override val displayName = "Outlook mail"
    override val enabled = props.sources.mail.clientId.isNotBlank()
    override val interval: Duration = props.sources.mail.interval

    override suspend fun fetch(now: Instant): SourceSnapshot {
        val (email, token) = accounts.accessToken()
        val project = SourceTag("mail:$email", "Email")
        val records = mailbox.flagged(email, token).filter { !it.answered }.map { toRecord(it, project, zone) }
        // Unflagged or answered mail drops out, which completes its task.
        return SourceSnapshot(records, completeMissing = true)
    }

    /** A task closed here clears the flag on its mail, so it doesn't stay "reply later" elsewhere. */
    @EventListener
    fun onTaskClosed(e: TaskClosed) {
        if (e.sourceType != type.wire) return
        val messageId = e.sourceRef?.removePrefix("${type.wire}:")?.takeIf { it.startsWith("msg:") }?.removePrefix("msg:") ?: return
        scope.launch {
            runCatching {
                val (email, token) = accounts.accessToken()
                if (!mailbox.unflag(email, token, messageId)) log.info("Mail {} is no longer in the inbox; left as is", messageId)
            }.onFailure { log.warn("Couldn't unflag mail {}: {}", messageId, it.toString()) }
        }
    }

    @PreDestroy
    fun stop() = scope.cancel()

    companion object {
        private val RECEIVED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun toRecord(m: FlaggedMail, project: SourceTag, zone: ZoneId) = SourceRecord(
            ref = m.messageId?.let { "msg:$it" } ?: "uid:${m.uidValidity}:${m.uid}",
            title = m.subject.ifBlank { "(no subject)" },
            notes = listOfNotNull("From: ${m.from}", m.received?.let { "Received: ${RECEIVED.format(it.atZone(zone))}" }).joinToString("\n"),
            ownsNotes = false,
            tags = listOf(SourceTag("reply", "reply")),
            project = project,
        )
    }
}
