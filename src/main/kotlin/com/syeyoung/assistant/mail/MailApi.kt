package com.syeyoung.assistant.mail

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/mail")
class MailController(private val accounts: MailAccounts) {

    @GetMapping("/account")
    suspend fun account(): MailAccountStatus = accounts.status()

    /** Starts the Microsoft sign-in; the response has the code to enter at the verification URL. */
    @PostMapping("/connect")
    suspend fun connect(): MailAccountStatus = accounts.connect()

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun disconnect() = accounts.disconnect()
}
