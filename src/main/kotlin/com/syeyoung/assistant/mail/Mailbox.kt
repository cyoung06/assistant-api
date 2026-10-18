package com.syeyoung.assistant.mail

import com.syeyoung.assistant.config.AppProperties
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.MessageIDTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import org.springframework.stereotype.Component
import java.time.Instant
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

interface Mailbox {
    suspend fun flagged(user: String, accessToken: String): List<FlaggedMail>

    /** Clears the flag on the inbox message with this Message-ID; false when it isn't in the inbox. */
    suspend fun unflag(user: String, accessToken: String, messageId: String): Boolean
}

/** The inbox over IMAP, signing in with an OAuth access token (SASL XOAUTH2). */
@Component
class ImapMailbox(props: AppProperties) : Mailbox {
    private val config = props.sources.mail

    override suspend fun flagged(user: String, accessToken: String): List<FlaggedMail> = inbox(user, accessToken, Folder.READ_ONLY) { f ->
        val messages = f.search(FlagTerm(Flags(Flags.Flag.FLAGGED), true))
        f.fetch(messages, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolderItem) })
        messages.map { m ->
            val sender = (m.from?.firstOrNull() as? InternetAddress)
            FlaggedMail(
                messageId = m.getHeader("Message-ID")?.firstOrNull()?.trim()?.removePrefix("<")?.removeSuffix(">"),
                uid = f.getUID(m),
                uidValidity = f.uidValidity,
                subject = m.subject?.trim().orEmpty(),
                from = sender?.personal?.takeIf { it.isNotBlank() }?.let { "$it <${sender.address}>" } ?: sender?.address.orEmpty(),
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
