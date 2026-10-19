package com.syeyoung.assistant

import com.syeyoung.assistant.capture.LlmCapture
import com.syeyoung.assistant.capture.LlmItem
import com.syeyoung.assistant.mail.MailTriage
import com.syeyoung.assistant.triage.TriageService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType

class MailTriageTest : ApiTestBase() {
    @Autowired lateinit var mailTriage: MailTriage
    @Autowired lateinit var triage: TriageService
    @Autowired lateinit var model: FakeCaptureModel

    @BeforeEach
    fun connect() {
        TestMail.reset()
        model.configured = true
        model.failure = null
        model.next = LlmCapture()
        client.post().uri("/api/v1/mail/connect").exchange().expectStatus().isOk
        val until = System.currentTimeMillis() + 10_000
        while (get("/api/v1/mail/account")["state"] != "connected") {
            check(System.currentTimeMillis() < until) { "not connected" }
            Thread.sleep(100)
        }
    }

    private fun poll() = runBlocking { mailTriage.poll() }
    private fun drain() = runBlocking { triage.drain() }

    @Suppress("UNCHECKED_CAST")
    private fun items(input: Map<String, Any?>) = input["items"] as List<Map<String, Any?>>

    @Test
    fun `new mail in every watched folder goes to Triage once, and stays unread`() {
        TestMail.deliverTo("INBOX", "UGRP meeting moved", "ugrp@postech.ac.kr")
        TestMail.deliverTo("교내회보", "[교내회보] 업무분장 안내", "bulletin@postech.ac.kr", to = "all-students@postech.ac.kr", body = "<p>Duties</p>")
        // A copy in another folder, mail I sent, and an auto-reply are not taken (again).
        TestMail.deliverTo("Archive2", "UGRP meeting moved", "ugrp@postech.ac.kr")
        TestMail.deliverTo("Sent", "Re: hello", "mine@postech.ac.kr", from = TestMail.USER)

        assertThat(poll()).isEqualTo(2)
        val inputs = getList("/api/v1/triage")
        assertThat(inputs.map { it["title"] to it["link"] }).containsExactlyInAnyOrder(
            "UGRP meeting moved" to "mid:ugrp@postech.ac.kr", "[교내회보] 업무분장 안내" to "mid:bulletin@postech.ac.kr",
        )
        assertThat(inputs.all { it["source"] == "mail" }).isTrue()
        assertThat(TestMail.isSeen("ugrp@postech.ac.kr") || TestMail.isSeen("bulletin@postech.ac.kr")).isFalse()

        // Only what's new next time.
        assertThat(poll()).isEqualTo(0)
        TestMail.deliverTo("교내회보", "[교내회보] 등록금 납부 안내", "tuition@postech.ac.kr", to = "all-students@postech.ac.kr")
        assertThat(poll()).isEqualTo(1)
    }

    @Test
    fun `the model judges mail with the profile, the signals and past decisions`() {
        client.put().uri("/api/v1/triage/profile").contentType(MediaType.APPLICATION_JSON).bodyValue(mapOf("text" to "I'm a CSE undergrad; seminars don't matter."))
            .exchange().expectStatus().isOk
        TestMail.deliverTo("교내회보", "[PIS] 혁신신약살롱 seminar", "seminar@postech.ac.kr", to = "all@postech.ac.kr", from = "seminar@postech.ac.kr")
        poll()
        model.next = LlmCapture(listOf(LlmItem("event", "Seminar", allDayDate = "2026-10-25")), verdict = "nothing", reason = "A seminar outside CSE")
        drain()
        val seminar = getList("/api/v1/triage").single()
        assertThat(seminar).containsEntry("verdict", "nothing").containsEntry("reason", "A seminar outside CSE").containsEntry("sender", "Sender <seminar@postech.ac.kr>")
        assertThat(items(seminar)).isEmpty()
        assertThat(model.lastLight).isTrue()
        assertThat(model.lastText).contains("Addressed: I'm not in To or Cc", "Folder: 교내회보", "Subject: [PIS] 혁신신약살롱 seminar")
        assertThat(model.lastInstructions).contains("I'm a CSE undergrad; seminars don't matter.", "verdict \"nothing\"")

        // The next mail from the same sender is judged with that decision in view.
        TestMail.deliverTo("INBOX", "[PIS] another seminar", "seminar2@postech.ac.kr", from = "seminar@postech.ac.kr")
        poll()
        model.next = LlmCapture(listOf(LlmItem("task", "Register")), verdict = "act", reason = "Registration needed")
        drain()
        assertThat(model.lastText).contains("Addressed: I'm in To (personal)")
        assertThat(model.lastInstructions).contains("How I decided on earlier mail", "\"[PIS] 혁신신약살롱 seminar\" → nothing (A seminar outside CSE)")
        val second = getList("/api/v1/triage").first { it["title"] == "[PIS] another seminar" }
        assertThat(second).containsEntry("verdict", "act")
        assertThat(items(second)).hasSize(1)
    }
}
