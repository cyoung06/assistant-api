package com.syeyoung.assistant.mail

import com.syeyoung.assistant.config.AppProperties
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.search.AndTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.ReceivedDateTerm
import jakarta.mail.search.SubjectTerm
import jakarta.mail.search.MessageIDTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date
import java.util.Properties

/** A flagged message in the inbox: what it takes to make a task of it and find it again. */
data class FlaggedMail(
    /** The Message-ID header without angle brackets, or null when the message has none. */
    val messageId: String?,
    val uid: Long,
    val uidValidity: Long,
    val subject: String,
    val from: String,
    val received: Instant?,
    /** Replied to (IMAP \Answered). */
    val answered: Boolean,
)

/** A message's headers, for search results. */
data class MailHeader(val messageId: String, val subject: String, val from: String, val received: Instant?)

/** A message to read: [html] when it has an HTML part, [text] when it has a plain one. */
data class MailMessage(
    val messageId: String,
    val subject: String,
    val from: String,
    val to: List<String>,
    val received: Instant?,
    val text: String?,
    val html: String?,
)

interface Mailbox {
    suspend fun flagged(user: String, accessToken: String): List<FlaggedMail>

    /** Clears the flag on the inbox message with this Message-ID; false when it isn't in the inbox. */
    suspend fun unflag(user: String, accessToken: String, messageId: String): Boolean

    /** Inbox messages since [since] with every word of [query] in the subject or sender, newest first. */
    suspend fun search(user: String, accessToken: String, query: String, since: Instant, limit: Int): List<MailHeader>

    suspend fun message(user: String, accessToken: String, messageId: String): MailMessage?
}

/** The inbox over IMAP, signing in with an OAuth access token (SASL XOAUTH2). */
@Component
class ImapMailbox(props: AppProperties) : Mailbox {
    private val config = props.sources.mail

    override suspend fun flagged(user: String, accessToken: String): List<FlaggedMail> = inbox(user, accessToken, Folder.READ_ONLY) { f ->
        val messages = f.search(FlagTerm(Flags(Flags.Flag.FLAGGED), true))
        f.fetch(messages, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolderItem) })
        messages.map { m ->
            FlaggedMail(
                messageId = messageIdOf(m),
                uid = f.getUID(m),
                uidValidity = f.uidValidity,
                subject = m.subject?.trim().orEmpty(),
                from = senderOf(m),
                received = (m.receivedDate ?: m.sentDate)?.toInstant(),
                answered = m.isSet(Flags.Flag.ANSWERED),
            )
        }
    }

    override suspend fun unflag(user: String, accessToken: String, messageId: String): Boolean = inbox(user, accessToken, Folder.READ_WRITE) { f ->
        val found = f.search(MessageIDTerm(messageId))
        found.forEach { it.setFlag(Flags.Flag.FLAGGED, false) }
        found.isNotEmpty()
    }

    override suspend fun search(user: String, accessToken: String, query: String, since: Instant, limit: Int): List<MailHeader> =
        inbox(user, accessToken, Folder.READ_ONLY) { f ->
            // Every word in the subject or the sender, in any order.
            val words = query.split(Regex("""\s+""")).filter { it.isNotBlank() }.map { OrTerm(SubjectTerm(it), FromStringTerm(it)) }
            val term = AndTerm((words + ReceivedDateTerm(ComparisonTerm.GE, Date.from(since))).toTypedArray())
            val found = f.search(term).sortedByDescending { it.receivedDate }.take(limit).toTypedArray()
            f.fetch(found, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) })
            found.mapNotNull { m ->
                val id = messageIdOf(m) ?: return@mapNotNull null
                MailHeader(id, m.subject?.trim().orEmpty(), senderOf(m), (m.receivedDate ?: m.sentDate)?.toInstant())
            }
        }

    override suspend fun message(user: String, accessToken: String, messageId: String): MailMessage? =
        inbox(user, accessToken, Folder.READ_ONLY) { f ->
            val m = f.search(MessageIDTerm(messageId)).firstOrNull() ?: return@inbox null
            val parts = Parts().also { it.collect(m) }
            MailMessage(
                messageId = messageId,
                subject = m.subject?.trim().orEmpty(),
                from = senderOf(m),
                to = m.getRecipients(jakarta.mail.Message.RecipientType.TO)?.map { it.toString() }.orEmpty(),
                received = (m.receivedDate ?: m.sentDate)?.toInstant(),
                text = parts.text,
                html = parts.html,
            )
        }

    /** The first plain-text and the first HTML body part (attachments left out). */
    private class Parts {
        var text: String? = null
        var html: String? = null

        fun collect(p: Part) {
            if (Part.ATTACHMENT.equals(p.disposition, true)) return
            when {
                p.isMimeType("text/plain") && text == null -> text = p.content as? String
                p.isMimeType("text/html") && html == null -> html = p.content as? String
                p.isMimeType("multipart/*") -> (p.content as Multipart).let { mp -> for (i in 0 until mp.count) collect(mp.getBodyPart(i)) }
                p.isMimeType("message/rfc822") -> {}
            }
        }
    }

    private fun messageIdOf(m: jakarta.mail.Message) = m.getHeader("Message-ID")?.firstOrNull()?.trim()?.removePrefix("<")?.removeSuffix(">")

    private fun senderOf(m: jakarta.mail.Message): String {
        val sender = m.from?.firstOrNull() as? InternetAddress
        return sender?.personal?.takeIf { it.isNotBlank() }?.let { "$it <${sender.address}>" } ?: sender?.address.orEmpty()
    }

    private suspend fun <T> inbox(user: String, accessToken: String, mode: Int, block: (IMAPFolder) -> T): T = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.imap.ssl.enable", config.imapSsl.toString())
            put("mail.imap.auth.mechanisms", "XOAUTH2")
            put("mail.imap.connectiontimeout", "15000")
            put("mail.imap.timeout", "60000")
        }
        Session.getInstance(props).getStore("imap").use { store ->
            // With XOAUTH2 the access token goes where the password would.
            try {
                store.connect(config.imapHost, config.imapPort, user, accessToken)
            } catch (e: AuthenticationFailedException) {
                throw AuthenticationFailedException(
                    "${config.imapHost} refused the sign-in (${e.message?.trim()}). IMAP may be turned off for this mailbox, or the app registration lacks IMAP.AccessAsUser.All",
                )
            }
            val folder = store.getFolder("INBOX") as IMAPFolder
            folder.open(mode)
            try {
                block(folder)
            } finally {
                folder.close(false)
            }
        }
    }

    private companion object {
        val UIDFolderItem: FetchProfile.Item = jakarta.mail.UIDFolder.FetchProfileItem.UID
    }
}
