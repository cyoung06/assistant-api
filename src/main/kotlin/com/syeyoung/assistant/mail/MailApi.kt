package com.syeyoung.assistant.mail

import com.syeyoung.assistant.common.ConflictException
import com.syeyoung.assistant.common.NotFoundException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

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

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun mailDisconnect() = accounts.disconnect()
}
