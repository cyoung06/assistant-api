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
 * Links to things, as written into text. The app's own things are pages of the app, so plain web
 * URLs (`<base>/task/<id>`); recognized by their path whatever the host, and in the old `#task/…`
 * form too. Mail isn't ours: a mail is `mid:<Message-ID>` (RFC 2392), opened by whatever reads mail
 * on each platform. PLMS things are `plms:` links ([PlmsLinks]).
 */
object AppLinks {
    data class Target(val kind: RefKind, val id: String)

    fun task(base: String, id: Any) = "${base.trimEnd('/')}/task/$id"
    fun project(base: String, id: Any) = "${base.trimEnd('/')}/tasks/project/$id"
    fun event(base: String, uid: String) = "${base.trimEnd('/')}/event/${URLEncoder.encode(uid, Charsets.UTF_8)}"

    /** RFC 2392: the Message-ID without angle brackets, escaped except for its @. */
    fun mail(messageId: String) = "mid:" + URLEncoder.encode(messageId.trim().removePrefix("<").removeSuffix(">"), Charsets.UTF_8).replace("%40", "@")

    /** Where the web app shows a mail, for places that only open web pages. */
    fun mailPage(base: String, messageId: String) = "${base.trimEnd('/')}/mail/${URLEncoder.encode(messageId, Charsets.UTF_8)}"

    private val PATTERN = Regex("""[/#](task|tasks/project|event|mail)/([^\s)#?]+)""")

    fun parse(url: String): Target? {
        if (url.startsWith("mid:")) return Target(RefKind.MAIL, URLDecoder.decode(url.removePrefix("mid:"), Charsets.UTF_8))
        if (!url.startsWith("http") && !url.startsWith("#") && !url.startsWith("/")) return null
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

    /** The link as a web page, for places that only open those (the iCal feed): mail in the web app, PLMS on its site. */
    fun webUrl(url: String, base: String, plmsBase: String): String = when {
        url.startsWith("mid:") -> mailPage(base, parse(url)!!.id)
        url.startsWith("plms:") -> PlmsLinks.toWeb(url, plmsBase) ?: url
        else -> url
    }

    /** Every link in [markdown] as a web page. */
    fun webLinks(markdown: String, base: String, plmsBase: String): String =
        MD_LINK_TARGET.replace(markdown) { m -> "](" + webUrl(m.groupValues[1], base, plmsBase) + ")" }

    private val MD_LINK_TARGET = Regex("""]\(((?:mid|plms):[^()\s]+)\)""")
}

/**
 * PLMS (Moodle) things as `plms:` links, independent of the site's URL layout: `plms:course/<id>`,
 * `plms:mod/<module>/<cmid>` (files, assignments, boards…), `plms:post/<cmid>/<bwid>` (board posts).
 */
object PlmsLinks {
    private val COURSE = Regex("""^/course/view\.php\?(?:.*&)?id=(\d+)""")
    private val MOD = Regex("""^/mod/(\w+)/view\.php\?(?:.*&)?id=(\d+)""")
    private val POST_ID = Regex("""[?&]id=(\d+)""")
    private val POST_BWID = Regex("""[?&]bwid=(\d+)""")

    /** The `plms:` link for a PLMS page under [base], or the URL unchanged when it's another kind of page. */
    fun fromWeb(url: String, base: String): String {
        val b = base.trimEnd('/')
        if (!url.startsWith(b)) return url
        val rest = url.removePrefix(b)
        if (rest.startsWith("/mod/ubboard/article.php")) {
            val cmid = POST_ID.find(rest)?.groupValues?.get(1)
            val bwid = POST_BWID.find(rest)?.groupValues?.get(1)
            if (cmid != null && bwid != null) return "plms:post/$cmid/$bwid"
        }
        COURSE.find(rest)?.let { return "plms:course/${it.groupValues[1]}" }
        MOD.find(rest)?.let { return "plms:mod/${it.groupValues[1]}/${it.groupValues[2]}" }
        return url
    }

    fun toWeb(link: String, base: String): String? {
        val b = base.trimEnd('/')
        val parts = link.removePrefix("plms:").split('/')
        return when {
            parts.size == 2 && parts[0] == "course" -> "$b/course/view.php?id=${parts[1]}"
            parts.size == 3 && parts[0] == "mod" -> "$b/mod/${parts[1]}/view.php?id=${parts[2]}"
            parts.size == 3 && parts[0] == "post" -> "$b/mod/ubboard/article.php?id=${parts[1]}&bwid=${parts[2]}"
            else -> null
        }
    }
}

/** `[text](url)` → `text`, for places that show plain text (iCalendar SUMMARY, …). */
fun plainText(markdown: String): String = MD_LINK.replace(markdown) { it.groupValues[1].replace(ESCAPED_BRACKET, "$1") }

/** The text may contain escaped brackets, the URL balanced parentheses. */
private val MD_LINK = Regex("""\[((?:\\.|[^\]\\])*)]\((?:[^()\s]|\([^()\s]*\))+\)""")
private val ESCAPED_BRACKET = Regex("""\\([\[\]])""")
