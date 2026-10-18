package com.syeyoung.assistant.mail

import com.syeyoung.assistant.config.AppProperties
import com.syeyoung.assistant.refs.AppLinks
import com.syeyoung.assistant.refs.Ref
import com.syeyoung.assistant.refs.RefKind
import com.syeyoung.assistant.refs.RefProvider
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Mail, searched live on the server (subject or sender, last 90 days); nothing is stored. */
@Component
class MailRefs(private val accounts: MailAccounts, private val mailbox: Mailbox, private val clock: Clock, props: AppProperties) : RefProvider {
    private val base = props.publicUrl

    override suspend fun search(query: String, limit: Int): List<Ref> {
        if (query.isBlank() || !accounts.configured) return emptyList()
        val (email, token) = runCatching { accounts.accessToken() }.getOrNull() ?: return emptyList()
        return mailbox.search(email, token, query.trim(), Instant.now(clock).minus(WINDOW), limit)
            .map { Ref(RefKind.MAIL, it.subject.ifBlank { "(no subject)" }, AppLinks.mail(base, it.messageId), it.from, it.received) }
    }

    override suspend fun resolve(url: String): Ref? {
        val target = AppLinks.parse(url)?.takeIf { it.kind == RefKind.MAIL } ?: return null
        val (email, token) = runCatching { accounts.accessToken() }.getOrNull() ?: return null
        val m = mailbox.message(email, token, target.id) ?: return null
        return Ref(RefKind.MAIL, m.subject.ifBlank { "(no subject)" }, AppLinks.mail(base, m.messageId), m.from, m.received)
    }

    private companion object {
        val WINDOW: Duration = Duration.ofDays(90)
    }
}
