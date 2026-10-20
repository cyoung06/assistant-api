package com.syeyoung.assistant.mail

import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.NotFoundException
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.Properties

/** A mail file as Capture needs it: headers decoded, and its plain text. */
data class ParsedMail(val subject: String, val from: String, val messageId: String?, val text: String)

@RestController
@RequestMapping("/api/v1/mail")
class MailController(private val accounts: MailAccounts, private val mailbox: Mailbox) {

    @GetMapping("/account")
    suspend fun mailAccount(): MailAccountStatus = accounts.status()

    /** Starts the Microsoft sign-in; the response has the code to enter at the verification URL. */
    @PostMapping("/connect")
    suspend fun mailConnect(): MailAccountStatus = accounts.connect()

    /** One inbox message to read, by its Message-ID (without angle brackets). */
    @GetMapping("/message")
    suspend fun mailMessage(@RequestParam id: String): MailMessage {
        val (email, token) = try {
            accounts.accessToken()
        } catch (e: MailNotConnected) {
            throw ConflictException("not_connected", e.message ?: "Not connected")
        }
        return mailbox.message(email, token, id) ?: throw NotFoundException("Message", id)
    }

    /**
     * A mail file (an .eml dragged out of a mail app: RFC 5322 with MIME) read the way the mailbox
     * reads mail: subject and sender decoded, the Message-ID, and the first plain-text part.
     */
    @PostMapping("/parse", consumes = ["message/rfc822"])
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        content = [Content(mediaType = "message/rfc822", schema = Schema(type = "string", format = "binary"))],
    )
    suspend fun mailParse(@RequestBody raw: ByteArray): ParsedMail = withContext(Dispatchers.IO) {
        val m = MimeMessage(Session.getInstance(Properties()), raw.inputStream())
        val parts = MailParts().also { it.collect(m) }
        ParsedMail(m.subject?.trim().orEmpty(), senderOf(m), messageIdOf(m), parts.text?.trim().orEmpty())
    }

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun mailDisconnect() = accounts.disconnect()
}
