package com.syeyoung.assistant.dav

import com.syeyoung.assistant.ical.IcalParser
import com.syeyoung.assistant.ical.IcalWriter

/**
 * An iCalendar object as stored on the CalDAV server: components with their property lines kept in
 * order and verbatim. Edits replace only the properties we set, so whatever a client (Apple Calendar,
 * Tasks.org, …) wrote — alarms, X- properties, time zones — survives the round-trip.
 */
class IcsDocument(val root: Comp) {

    /** A component (VCALENDAR, VEVENT, VTODO, VALARM, VTIMEZONE, …). */
    class Comp(val name: String, val props: MutableList<Prop> = mutableListOf(), val children: MutableList<Comp> = mutableListOf()) {
        fun prop(name: String): Prop? = props.firstOrNull { it.name == name }
        fun all(name: String): List<Prop> = props.filter { it.name == name }
        fun value(name: String): String? = prop(name)?.value
        fun text(name: String): String? = prop(name)?.let { IcalParser.unescape(it.value) }

        /** Replaces every [name] property with one property, or removes them when [value] is null. */
        fun set(name: String, value: String?, params: Map<String, String> = emptyMap()) {
            val at = props.indexOfFirst { it.name == name }
            props.removeAll { it.name == name }
            if (value == null) return
            val p = Prop(name, params, value)
            if (at >= 0) props.add(at, p) else props += p
        }

        fun setText(name: String, text: String?) = set(name, text?.let(IcalWriter::escape))

        /** Replaces all [name] properties with one per value (e.g. CATEGORIES can repeat). */
        fun setAll(name: String, values: List<String>) {
            props.removeAll { it.name == name }
            values.forEach { props += Prop(name, emptyMap(), it) }
        }
    }

    data class Prop(val name: String, val params: Map<String, String>, val value: String) {
        fun param(key: String) = params[key]
    }

    /** The first VEVENT or VTODO without RECURRENCE-ID (the "master" of the object). */
    val main: Comp?
        get() = root.children.firstOrNull { (it.name == "VEVENT" || it.name == "VTODO") && it.prop("RECURRENCE-ID") == null }

    /** Single-occurrence overrides of a repeating master. */
    val overrides: List<Comp>
        get() = root.children.filter { (it.name == "VEVENT" || it.name == "VTODO") && it.prop("RECURRENCE-ID") != null }

    fun serialize(): String {
        val out = StringBuilder()
        fun write(c: Comp) {
            line(out, "BEGIN:${c.name}")
            c.props.forEach { p -> line(out, p.name + p.params.entries.joinToString("") { ";${it.key}=${it.value}" } + ":" + p.value) }
            c.children.forEach(::write)
            line(out, "END:${c.name}")
        }
        write(root)
        return out.toString()
    }

    companion object {
        fun parse(text: String): IcsDocument {
            val stack = ArrayDeque<Comp>()
            var root: Comp? = null
            for (line in unfold(text)) {
                val colon = valueColon(line)
                if (colon < 0) continue
                val head = line.substring(0, colon).split(';')
                val name = head[0].uppercase()
                val value = line.substring(colon + 1)
                when (name) {
                    "BEGIN" -> {
                        val c = Comp(value.uppercase())
                        stack.lastOrNull()?.children?.add(c) ?: run { root = c }
                        stack.addLast(c)
                    }
                    "END" -> stack.removeLastOrNull()
                    else -> {
                        val params = head.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }
                            .toMap(LinkedHashMap())
                        stack.lastOrNull()?.props?.add(Prop(name, params, value))
                    }
                }
            }
            return IcsDocument(root ?: throw IllegalArgumentException("not an iCalendar object"))
        }

        /** A new object holding one component. */
        fun create(component: Comp): IcsDocument = IcsDocument(
            Comp(
                "VCALENDAR",
                mutableListOf(Prop("VERSION", emptyMap(), "2.0"), Prop("PRODID", emptyMap(), "-//assistant//interop//EN")),
                mutableListOf(component),
            ),
        )

        private fun valueColon(line: String): Int {
            var quoted = false
            for ((i, c) in line.withIndex()) {
                if (c == '"') quoted = !quoted
                if (c == ':' && !quoted) return i
            }
            return -1
        }

        private fun unfold(text: String): List<String> {
            val result = mutableListOf<String>()
            for (line in text.split("\r\n", "\n")) {
                if ((line.startsWith(" ") || line.startsWith("\t")) && result.isNotEmpty()) {
                    result[result.lastIndex] = result.last() + line.substring(1)
                } else if (line.isNotEmpty()) {
                    result += line
                }
            }
            return result
        }

        /** Writes a content line folded at 75 octets without splitting UTF-8 characters, CRLF-terminated. */
        private fun line(out: StringBuilder, text: String) {
            var used = 0
            var limit = 75
            // Code points, so a character outside the BMP (emoji) is never split across a fold.
            text.codePoints().forEach { cp ->
                val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (used + size > limit) {
                    out.append("\r\n ")
                    used = 0
                    limit = 74 // the leading space counts
                }
                out.appendCodePoint(cp)
                used += size
            }
            out.append("\r\n")
        }
    }
}
