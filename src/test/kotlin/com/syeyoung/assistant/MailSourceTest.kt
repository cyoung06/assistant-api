package com.syeyoung.assistant

import com.syeyoung.assistant.mail.MailAccounts
import jakarta.mail.Flags
import org.springframework.beans.factory.annotation.Autowired
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MailSourceTest : ApiTestBase() {
    @Autowired lateinit var accounts: MailAccounts

    @BeforeEach
    fun resetMail() = TestMail.reset()

    @Suppress("UNCHECKED_CAST")
    private fun sync(): Map<String, Any?> =
        client.post().uri("/api/v1/sources/email/sync").exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun connect(): Map<String, Any?> =
        client.post().uri("/api/v1/mail/connect").exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody as Map<String, Any?>

    private fun connected() {
        assertThat(connect()).containsEntry("state", "pending").containsEntry("userCode", "ABCD-1234")
        waitFor { get("/api/v1/mail/account")["state"] == "connected" }
        assertThat(get("/api/v1/mail/account")["email"]).isEqualTo(TestMail.USER)
    }

    private fun mailTasks() = getList("/api/v1/tasks").filter { it["source"] == "email" }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) error("timed out")
            Thread.sleep(100)
        }
    }

    @Test
    fun `without an account the sync says to connect`() {
        assertThat(get("/api/v1/mail/account")).containsEntry("configured", true).containsEntry("state", "disconnected")
        assertThat(sync()["lastError"] as String).contains("connect your mail")
    }

    @Test
    fun `flagged mail becomes a reply task, and unflagging or replying completes it`() {
        connected()
        TestMail.deliver("Meeting next week?", "m1@postech.ac.kr", flagged = true)
        TestMail.deliver("Newsletter", "m2@postech.ac.kr", flagged = false)
        TestMail.deliver("Already answered", "m3@postech.ac.kr", flagged = true, answered = true)

        assertThat((sync()["lastReport"] as Map<*, *>)["created"]).isEqualTo(1)
        val task = mailTasks().single()
        assertThat(task["title"]).isEqualTo("Meeting next week?")
        assertThat(task["description"] as String).startsWith("From: Prof. Kim <prof@postech.ac.kr>")
        assertThat(getList("/api/v1/projects").single()["name"]).isEqualTo("Email")
        assertThat(getList("/api/v1/labels").single()["name"]).isEqualTo("reply")

        // The description is the user's once the task exists.
        patch("/api/v1/tasks/${task["id"]}", mapOf("description" to "ask about Thursday"))
        sync()
        assertThat(get("/api/v1/tasks/${task["id"]}")["description"]).isEqualTo("ask about Thursday")

        TestMail.setFlag("m1@postech.ac.kr", Flags.Flag.ANSWERED, true)
        assertThat((sync()["lastReport"] as Map<*, *>)["completed"]).isEqualTo(1)
        assertThat(get("/api/v1/tasks/${task["id"]}")["status"]).isEqualTo("done")
    }

    @Test
    fun `completing the task clears the flag on the mail`() {
        connected()
        TestMail.deliver("Can you review this?", "m4@postech.ac.kr", flagged = true)
        sync()
        val id = mailTasks().single()["id"]
        patch("/api/v1/tasks/$id", mapOf("status" to "done"))
        waitFor { !TestMail.isFlagged("m4@postech.ac.kr") }
        assertThat((sync()["lastReport"] as Map<*, *>)["completed"]).isEqualTo(0)
    }

    @Test
    fun `an expired sign-in asks to reconnect, and disconnecting forgets the account`() {
        connected()
        TestMail.refreshFails = true
        accounts.forgetAccessToken()
        assertThat(sync()["lastError"] as String).contains("expired")
        assertThat(get("/api/v1/mail/account")).containsEntry("state", "error")

        // Signing in again fixes it.
        TestMail.refreshFails = false
        connected()
        TestMail.deliver("Hello", "m5@postech.ac.kr", flagged = true)
        assertThat(sync()["lastError"]).isNull()

        client.delete().uri("/api/v1/mail/account").exchange().expectStatus().isNoContent
        assertThat(get("/api/v1/mail/account")["state"]).isEqualTo("disconnected")
    }
}
