package com.syeyoung.assistant.refs

import com.fasterxml.jackson.annotation.JsonValue
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant

enum class RefKind(@get:JsonValue val wire: String) {
    TASK("task"), PROJECT("project"), EVENT("event"), MAIL("mail"),
    PLMS_COURSE("plms_course"), PLMS_FILE("plms_file"), PLMS_NOTICE("plms_notice"), PLMS_ASSIGNMENT("plms_assignment"), PLMS_ACTIVITY("plms_activity"),
    LINK("link");

    companion object {
        fun of(wire: String) = entries.find { it.wire == wire } ?: LINK
    }
}

/** Something that can be referenced: written into text as a Markdown link `[title](url)`. */
data class Ref(
    val kind: RefKind,
    val title: String,
    val url: String,
    /** Where it belongs, e.g. the course, the sender or the project. */
    val context: String? = null,
    val date: Instant? = null,
)

/** One kind of referenceable thing: searched when typing @, recognized when its link is pasted. */
interface RefProvider {
    suspend fun search(query: String, limit: Int): List<Ref>

    /** The thing behind [url], when it's one of this provider's. */
    suspend fun resolve(url: String): Ref? = null
}

/**
 * Links to the app's own things. They are absolute (so they work wherever the text goes) and are
 * recognized by their fragment whatever the host.
 */
object AppLinks {
    data class Target(val kind: RefKind, val id: String)

    fun task(base: String, id: Any) = "${base.trimEnd('/')}/#task/$id"
    fun project(base: String, id: Any) = "${base.trimEnd('/')}/#tasks/project/$id"
    fun event(base: String, uid: String) = "${base.trimEnd('/')}/#event/${URLEncoder.encode(uid, Charsets.UTF_8)}"
    fun mail(base: String, messageId: String) = "${base.trimEnd('/')}/#mail/${URLEncoder.encode(messageId, Charsets.UTF_8)}"

    private val PATTERN = Regex("""#(task|tasks/project|event|mail)/([^\s)#?]+)""")

    fun parse(url: String): Target? {
        val m = PATTERN.find(url) ?: return null
        val id = URLDecoder.decode(m.groupValues[2], Charsets.UTF_8)
        val kind = when (m.groupValues[1]) {
            "task" -> RefKind.TASK
            "tasks/project" -> RefKind.PROJECT
            "event" -> RefKind.EVENT
            else -> RefKind.MAIL
        }
        return Target(kind, id)
    }
}

/** `[text](url)` → `text`, for places that show plain text (iCalendar SUMMARY, …). */
fun plainText(markdown: String): String = MD_LINK.replace(markdown) { it.groupValues[1].replace(ESCAPED_BRACKET, "$1") }

/** The text may contain escaped brackets, the URL balanced parentheses. */
private val MD_LINK = Regex("""\[((?:\\.|[^\]\\])*)]\((?:[^()\s]|\([^()\s]*\))+\)""")
private val ESCAPED_BRACKET = Regex("""\\([\[\]])""")
