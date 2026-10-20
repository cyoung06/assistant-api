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

/** How far a folder has been read: its UIDVALIDITY and the highest UID seen. */
data class FolderMark(val uidValidity: Long, val lastUid: Long)

/** A new message from a watched folder, read without marking it seen. */
data class IncomingMail(
    val folder: String,
    val messageId: String?,
    val subject: String,
    /** "Name <address>". */
    val from: String,
    val fromAddress: String,
    val to: List<String>,
    val cc: List<String>,
    val received: Instant?,
    /** An automatic reply (Auto-Submitted: auto-replied), e.g. "out of office". */
    val autoReply: Boolean,
    val text: String?,
    val html: String?,
    val attachments: List<String>,
)

/** New mail since [marks], and where each folder has been read up to now. */
data class IncomingBatch(val mail: List<IncomingMail>, val marks: Map<String, FolderMark>)

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

    /**
     * Mail that arrived in the watched folders (all but sent, drafts, deleted, junk and the like)
     * after [marks]; a folder without a mark (new, or its UIDVALIDITY changed) is read from
     * [since]. Folders are opened read-only, so nothing is marked as read.
     */
    suspend fun incoming(user: String, accessToken: String, marks: Map<String, FolderMark>, since: Instant, limit: Int): IncomingBatch
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
            val parts = MailParts().also { it.collect(m) }
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

    override suspend fun incoming(user: String, accessToken: String, marks: Map<String, FolderMark>, since: Instant, limit: Int): IncomingBatch =
        withStore(user, accessToken) { store ->
            val mail = mutableListOf<IncomingMail>()
            val now = mutableMapOf<String, FolderMark>()
            val folders = store.defaultFolder.list("*").filterIsInstance<IMAPFolder>().filter { watched(it) }
            for (f in folders) {
                // Full: the folders not reached keep their marks (or, without one, are read from [since] next time).
                if (mail.size >= limit) break
                f.open(Folder.READ_ONLY)
                try {
                    val validity = f.uidValidity
                    val mark = marks[f.fullName]?.takeIf { it.uidValidity == validity }
                    val found = if (mark != null) f.getMessagesByUID(mark.lastUid + 1, IMAPFolder.LASTUID).filter { f.getUID(it) > mark.lastUid }
                    else f.search(ReceivedDateTerm(ComparisonTerm.GE, Date.from(since))).toList()
                    val messages = found.toTypedArray()
                    f.fetch(messages, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(UIDFolderItem) })
                    var last = mark?.lastUid ?: 0
                    var complete = true
                    for (m in messages.sortedBy { f.getUID(it) }) {
                        if (mail.size >= limit) { complete = false; break }
                        mail += read(f.fullName, m)
                        last = maxOf(last, f.getUID(m))
                    }
                    // A folder read to the end is marked up to its newest mail (older ones before [since] stay unread).
                    if (complete && mark == null) last = maxOf(last, f.uidNext - 1)
                    // (One without a mark that nothing was read from yet stays without: a mark of 0 would read its whole history.)
                    if (mark != null || last > 0) now[f.fullName] = FolderMark(validity, last)
                } finally {
                    f.close(false)
                }
            }
            IncomingBatch(mail, now)
        }

    /** Folders that hold incoming mail: not sent, drafts, deleted, junk, outbox, or Outlook's non-mail folders. */
    private fun watched(f: IMAPFolder): Boolean {
        if (f.type and Folder.HOLDS_MESSAGES == 0) return false
        if (f.attributes.any { it.lowercase() in SPECIAL_USE }) return false
        val name = f.fullName.substringAfterLast(f.separator).lowercase()
        return name !in SKIPPED_NAMES && SKIPPED_NAMES.none { f.fullName.lowercase().startsWith("$it${f.separator}") }
    }

    private fun read(folder: String, m: jakarta.mail.Message): IncomingMail {
        val parts = MailParts().also { it.collect(m) }
        val sender = m.from?.firstOrNull() as? InternetAddress
        return IncomingMail(
            folder = folder,
            messageId = messageIdOf(m),
            subject = m.subject?.trim().orEmpty(),
            from = senderOf(m),
            fromAddress = sender?.address.orEmpty(),
            to = m.getRecipients(jakarta.mail.Message.RecipientType.TO)?.map { it.toString() }.orEmpty(),
            cc = m.getRecipients(jakarta.mail.Message.RecipientType.CC)?.map { it.toString() }.orEmpty(),
            received = (m.receivedDate ?: m.sentDate)?.toInstant(),
            autoReply = m.getHeader("Auto-Submitted")?.any { it.trim().lowercase().startsWith("auto-replied") } == true,
            text = parts.text,
            html = parts.html,
            attachments = parts.attachments,
        )
    }

    private suspend fun <T> inbox(user: String, accessToken: String, mode: Int, block: (IMAPFolder) -> T): T = withStore(user, accessToken) { store ->
        val folder = store.getFolder("INBOX") as IMAPFolder
        folder.open(mode)
        try {
            block(folder)
        } finally {
            folder.close(false)
        }
    }

    private suspend fun <T> withStore(user: String, accessToken: String, block: (jakarta.mail.Store) -> T): T = withContext(Dispatchers.IO) {
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
            block(store)
        }
    }

    private companion object {
        val UIDFolderItem: FetchProfile.Item = jakarta.mail.UIDFolder.FetchProfileItem.UID

        /** IMAP special-use attributes (RFC 6154) of folders that aren't incoming mail. */
        val SPECIAL_USE = setOf("\\sent", "\\drafts", "\\trash", "\\junk", "\\all", "\\archive", "\\noselect")

        /** The same by name, for servers that don't mark them (Exchange in English and Korean, and the usual others). */
        val SKIPPED_NAMES = setOf(
            "sent", "sent items", "sent messages", "drafts", "deleted items", "deleted messages", "trash", "junk", "junk email", "junk e-mail", "spam",
            "outbox", "conversation history", "sync issues", "calendar", "contacts", "tasks", "notes", "journal", "rss feeds", "rss subscriptions",
            "보낸 편지함", "임시 보관함", "지운 편지함", "정크 메일", "보낼 편지함", "대화 기록", "동기화 문제", "일정", "연락처", "작업", "메모", "업무 일지",
        )
    }
}

/** The first plain-text and the first HTML body part, and the attachments' names. */
internal class MailParts {
    var text: String? = null
    var html: String? = null
    val attachments = mutableListOf<String>()

    fun collect(p: Part) {
        if (Part.ATTACHMENT.equals(p.disposition, true)) {
            p.fileName?.let { attachments += runCatching { jakarta.mail.internet.MimeUtility.decodeText(it) }.getOrDefault(it) }
            return
        }
        when {
            p.isMimeType("text/plain") && text == null -> text = p.content as? String
            p.isMimeType("text/html") && html == null -> html = p.content as? String
            p.isMimeType("multipart/*") -> (p.content as Multipart).let { mp -> for (i in 0 until mp.count) collect(mp.getBodyPart(i)) }
            p.isMimeType("message/rfc822") -> {}
        }
    }
}

internal fun messageIdOf(m: jakarta.mail.Message) = m.getHeader("Message-ID")?.firstOrNull()?.trim()?.removePrefix("<")?.removeSuffix(">")

internal fun senderOf(m: jakarta.mail.Message): String {
    val sender = m.from?.firstOrNull() as? InternetAddress
    return sender?.personal?.takeIf { it.isNotBlank() }?.let { "$it <${sender.address}>" } ?: sender?.address.orEmpty()
}
