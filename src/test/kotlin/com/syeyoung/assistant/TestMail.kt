package com.syeyoung.assistant

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.sun.net.httpserver.HttpServer
import jakarta.mail.Flags
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mail for tests: an in-memory IMAP server (GreenMail, which takes the access token as the
 * password under XOAUTH2) and a fake Microsoft sign-in that approves after one "pending" poll.
 */
object TestMail {
    const val USER = "me@postech.ac.kr"
    const val ACCESS_TOKEN = "access-token"

    val greenMail: GreenMail by lazy {
        GreenMail(ServerSetup(freePort(), "127.0.0.1", ServerSetup.PROTOCOL_IMAP)).apply {
            start()
            setUser(USER, USER, ACCESS_TOKEN)
        }
    }

    /** Polls of the token endpoint so far; the first device-code poll is "pending". */
    val polls = AtomicInteger()
    @Volatile var refreshFails = false

    val oauthUrl: String by lazy {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/organizations/oauth2/v2.0/devicecode") { ex ->
            respond(ex, 200, """{"device_code":"dev-1","user_code":"ABCD-1234","verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":1}""")
        }
        server.createContext("/organizations/oauth2/v2.0/token") { ex ->
            val form = ex.requestBody.readAllBytes().decodeToString()
            when {
                "grant_type=refresh_token" in form && refreshFails ->
                    respond(ex, 400, """{"error":"invalid_grant","error_description":"AADSTS70008: The refresh token has expired."}""")
                "grant_type=refresh_token" in form -> respond(ex, 200, tokens())
                polls.getAndIncrement() == 0 -> respond(ex, 400, """{"error":"authorization_pending","error_description":"pending"}""")
                else -> respond(ex, 200, tokens())
            }
        }
        server.start()
        "http://127.0.0.1:${server.address.port}"
    }

    fun reset() {
        greenMail.purgeEmailFromAllMailboxes()
        val user = greenMail.userManager.getUser(USER)
        greenMail.managers.imapHostManager.listMailboxes(user, "*").filter { it.name != "INBOX" }
            .forEach { runCatching { greenMail.managers.imapHostManager.deleteMailbox(user, it.name) } }
        polls.set(0)
        refreshFails = false
    }

    /** Puts a message in the inbox with the given flags. */
    fun deliver(subject: String, messageId: String, flagged: Boolean, answered: Boolean = false) {
        // Keep the given Message-ID; saving would otherwise replace it with a generated one.
        val message = object : MimeMessage(Session.getInstance(Properties())) {
            override fun updateMessageID() {}
        }.apply {
            setFrom(InternetAddress("prof@postech.ac.kr", "Prof. Kim"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, USER)
            setSubject(subject)
            setText("Please reply.")
            setHeader("Message-ID", "<$messageId>")
        }
        val user = greenMail.userManager.getUser(USER)
        user.deliver(message)
        val inbox = greenMail.managers.imapHostManager.getInbox(user)
        val stored = inbox.messages.last()
        if (flagged) stored.setFlag(Flags.Flag.FLAGGED, true)
        if (answered) stored.setFlag(Flags.Flag.ANSWERED, true)
    }

    /** Puts a message in [folder] (made if missing), as a server rule or another client would. */
    fun deliverTo(folder: String, subject: String, messageId: String, to: String = USER, from: String = "prof@postech.ac.kr", body: String = "Please reply.") {
        val message = object : MimeMessage(Session.getInstance(Properties())) {
            override fun updateMessageID() {}
        }.apply {
            setFrom(InternetAddress(from, "Sender"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, to)
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
            setHeader("Message-ID", "<$messageId>")
        }
        val user = greenMail.userManager.getUser(USER)
        val manager = greenMail.managers.imapHostManager
        val target = if (folder == "INBOX") manager.getInbox(user) else runCatching { manager.getFolder(user, folder, true) }.getOrNull() ?: manager.createMailbox(user, folder)
        target.appendMessage(message, Flags(), java.util.Date())
    }

    /** Whether any copy of the message is marked read. */
    fun isSeen(messageId: String): Boolean {
        val user = greenMail.userManager.getUser(USER)
        return greenMail.managers.imapHostManager.listMailboxes(user, "*").flatMap { it.messages }
            .filter { it.mimeMessage.messageID == "<$messageId>" }.any { it.isSet(Flags.Flag.SEEN) }
    }

    fun setFlag(messageId: String, flag: Flags.Flag, value: Boolean) {
        val inbox = greenMail.managers.imapHostManager.getInbox(greenMail.userManager.getUser(USER))
        inbox.messages.first { it.mimeMessage.messageID == "<$messageId>" }.setFlag(flag, value)
    }

    fun isFlagged(messageId: String): Boolean {
        val inbox = greenMail.managers.imapHostManager.getInbox(greenMail.userManager.getUser(USER))
        return inbox.messages.first { it.mimeMessage.messageID == "<$messageId>" }.isSet(Flags.Flag.FLAGGED)
    }

    private fun tokens(): String {
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"preferred_username":"$USER"}""".toByteArray())
        return """{"access_token":"$ACCESS_TOKEN","refresh_token":"refresh-${System.nanoTime()}","expires_in":3600,"id_token":"e30.$claims.sig"}"""
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
}
