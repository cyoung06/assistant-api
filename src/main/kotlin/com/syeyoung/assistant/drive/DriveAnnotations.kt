package com.syeyoung.assistant.drive

import com.syeyoung.assistant.core.NotebookEdit
import com.syeyoung.assistant.core.NotebookPage
import com.syeyoung.assistant.sync.Blobs
import com.syeyoung.assistant.sync.NotebookPdf
import com.syeyoung.assistant.sync.SyncDocs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A copy of a course file to write on: where it is, the course file, and whether that's changed since. */
data class Annotation(val copy: String, val source: String, val sourceChanged: Boolean)

/**
 * Course files (kept as PLMS has them) are written on in a copy: "Annotate" makes one in the
 * project's Notes folder, and remembers which course file and which version of it it's of. When PLMS
 * updates the course file, the writing can be carried over to the new version ([carry]): each page
 * of the copy goes to the new page with its text, so what's written stays with what it's about.
 */
@Component
class DriveAnnotations(private val dav: WebDav, private val db: DatabaseClient, private val clock: Clock, private val live: SyncDocs, private val blobs: Blobs) {

    /** The copy of [source] to write on: the one made before (if it's still there), or a new one in the project's Notes. */
    suspend fun annotate(source: String): Annotation {
        latestCopyOf(source)?.let { return it }
        val parts = source.split('/')
        val stem = parts.last().substringBeforeLast('.')
        val notes = "${parts[0]}/${parts[1]}/Notes"
        dav.makeFolders(notes)
        var copy = "$notes/$stem (notes).pdf"
        var n = 2
        while (dav.exists(copy)) copy = "$notes/$stem (notes ${n++}).pdf"
        dav.copy(source, copy)
        db.sql("insert into drive_annotations (copy_path, source_path, source_etag, created_at) values (:copy, :source, :etag, :now)")
            .bind("copy", copy).bind("source", source).bindNullable("etag", dav.etag(source)).bind("now", Instant.now(clock))
            .fetch().awaitRowsUpdated()
        return Annotation(copy, source, false)
    }

    /** What [path] is part of: a copy and its course file, or a course file and its latest copy (if there is one). */
    suspend fun of(path: String): Annotation? {
        val row = db.sql("select copy_path, source_path, source_etag from drive_annotations where copy_path = :p")
            .bind("p", path).map { r -> Triple(r.get("copy_path", String::class.java)!!, r.get("source_path", String::class.java)!!, r.get("source_etag", String::class.java)) }
            .awaitOneOrNull()
        if (row != null) return Annotation(row.first, row.second, changed(row.second, row.third))
        return latestCopyOf(path)
    }

    private suspend fun latestCopyOf(source: String): Annotation? {
        val rows = db.sql("select copy_path, source_etag from drive_annotations where source_path = :s order by created_at desc")
            .bind("s", source).map { r -> r.get("copy_path", String::class.java)!! to r.get("source_etag", String::class.java) }.flow().toList()
        // One in the trash isn't written on any more (put back, it is again).
        for ((copy, etag) in rows) if (!copy.startsWith("${DriveTrash.TRASH}/") && dav.exists(copy)) return Annotation(copy, source, changed(source, etag))
        return null
    }

    private suspend fun changed(source: String, etag: String?) = dav.etag(source)?.let { it != etag } ?: false

    /**
     * Carries the writing in [copy] over to its course file as it is now: the new version becomes
     * what's written on, each page going to the new page with the same text (pages without a match
     * kept as they were, at the end of the new version's; pages new in it put in where they belong).
     * False if [copy] isn't a copy (or its course file is gone).
     */
    suspend fun carry(copy: String): Boolean {
        val a = of(copy)?.takeIf { it.copy == copy } ?: return false
        val (source, etag) = live.read(a.source) ?: return false
        val ok = live.editNotebook(copy) { doc ->
            val oldBase = blobs.get(doc.base())?.data ?: error("its base PDF isn't kept")
            val fresh = withContext(Dispatchers.Default) { NotebookPdf.read(source) }
            val plan = withContext(Dispatchers.Default) { carryOver(oldBase, fresh.base, fresh.pages, doc.pages()) }
            val hash = blobs.put(plan.base, "application/pdf")
            listOf(NotebookEdit.SetBase(hash)) + plan.edits
        }
        if (ok) keep(copy, etag)
        return ok
    }

    /** The course file's version now taken as the one [copy] is of, without carrying anything over ("keep as it is"). */
    suspend fun dismiss(copy: String): Boolean {
        val a = of(copy)?.takeIf { it.copy == copy } ?: return false
        keep(copy, dav.etag(a.source))
        return true
    }

    private suspend fun keep(copy: String, etag: String?) {
        db.sql("update drive_annotations set source_etag = :etag where copy_path = :copy").bindNullable("etag", etag).bind("copy", copy).fetch().awaitRowsUpdated()
    }

    /** [from] (a file, or a folder with what's in it) is now at [to]; null: gone for good. Copies and course files alike. */
    suspend fun moved(from: String, to: String?) {
        val f = from.trimEnd('/')
        val under = f.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%"
        if (to == null) {
            db.sql("delete from drive_annotations where copy_path = :f or copy_path like :under").bind("f", f).bind("under", under).fetch().awaitRowsUpdated()
            return
        }
        val t = to.trimEnd('/')
        for (column in listOf("copy_path", "source_path")) {
            db.sql("update drive_annotations set $column = :to || substr($column, length(:from) + 1) where $column = :from or $column like :under")
                .bind("to", t).bind("from", f).bind("under", under).fetch().awaitRowsUpdated()
        }
    }

    private fun DatabaseClient.GenericExecuteSpec.bindNullable(name: String, value: String?) =
        if (value != null) bind(name, value) else bindNull(name, String::class.java)

    /** The new base, and the edits that put the notebook's pages on it. */
    class Plan(val base: ByteArray, val edits: List<NotebookEdit>)

    companion object {
        /**
         * Puts [pages] (written on [oldBase]) on [newBase] (whose pages are [newPages]): each page of the
         * old base goes to the new page with its text ([matchPages]); those without one are added to
         * the end of the new base as they were, and new pages without an old one are put in after the
         * page before them.
         */
        fun carryOver(oldBase: ByteArray, newBase: ByteArray, newPages: List<NotebookPage>, pages: List<NotebookPage>): Plan =
            Loader.loadPDF(oldBase).use { old ->
                Loader.loadPDF(newBase).use { new ->
                    val match = matchPages(texts(old), texts(new))
                    val count = new.numberOfPages
                    // Old pages written on with no match: kept, after the new ones.
                    val kept = HashMap<Int, Int>()
                    for (p in pages) {
                        if (p.base < 0 || p.base >= match.size || match[p.base] != null || p.base in kept) continue
                        kept[p.base] = count + kept.size
                        new.importPage(old.getPage(p.base))
                    }
                    val edits = mutableListOf<NotebookEdit>()
                    val order = mutableListOf<Int?>()
                    val used = HashSet<Int>()
                    for (p in pages) {
                        val to = when {
                            p.base < 0 -> null
                            p.base < match.size && match[p.base] != null -> match[p.base]!!.also { used += it }
                            else -> kept[p.base]
                        }
                        order += to?.takeIf { it < count }
                        if (to == null) continue
                        val size = newPages.getOrNull(to)
                        edits += NotebookEdit.PutPage(p.copy(base = to, width = size?.width ?: p.width, height = size?.height ?: p.height))
                    }
                    for (j in 0 until count) {
                        if (j in used) continue
                        val at = order.indexOfLast { it != null && it < j } + 1
                        edits += NotebookEdit.InsertPage(at.toUInt(), NotebookPage(UUID.randomUUID().toString(), j, "", newPages[j].width, newPages[j].height))
                        order.add(at, j)
                    }
                    val base = if (kept.isEmpty()) newBase else ByteArrayOutputStream().also { new.save(it) }.toByteArray()
                    Plan(base, edits)
                }
            }

        private fun texts(doc: PDDocument): List<String> {
            val stripper = PDFTextStripper()
            return (1..doc.numberOfPages).map { i ->
                stripper.startPage = i
                stripper.endPage = i
                runCatching { stripper.getText(doc) }.getOrDefault("")
            }
        }

        private fun words(text: String) = Regex("""[\p{L}\p{N}]+""").findAll(text.lowercase()).map { it.value }.toSet()

        /**
         * For each old page, the new page with the same text (by the words on them: most in common,
         * at least 60%), or null. Pages with no text (scans, pictures) go by place when the two have
         * as many pages.
         */
        fun matchPages(old: List<String>, new: List<String>): List<Int?> {
            val ow = old.map(::words)
            val nw = new.map(::words)
            val pairs = mutableListOf<Triple<Double, Int, Int>>()
            for (i in ow.indices) for (j in nw.indices) {
                if (ow[i].isEmpty() || nw[j].isEmpty()) continue
                val common = ow[i].intersect(nw[j]).size.toDouble()
                val score = common / (ow[i].size + nw[j].size - common)
                // Ties go to the nearer page.
                if (score >= 0.6) pairs += Triple(score - kotlin.math.abs(i - j) * 1e-6, i, j)
            }
            val result = arrayOfNulls<Int>(old.size)
            val taken = HashSet<Int>()
            for ((_, i, j) in pairs.sortedByDescending { it.first }) {
                if (result[i] != null || j in taken) continue
                result[i] = j
                taken += j
            }
            if (old.size == new.size) for (i in ow.indices) if (result[i] == null && ow[i].isEmpty() && nw[i].isEmpty() && i !in taken) {
                result[i] = i
                taken += i
            }
            return result.toList()
        }
    }
}
