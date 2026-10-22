package com.syeyoung.assistant.drive

import com.syeyoung.assistant.core.RenderException
import com.syeyoung.assistant.core.coverPdf
import com.syeyoung.assistant.core.noteImages
import com.syeyoung.assistant.core.notePdf
import com.syeyoung.assistant.core.registerFont
import com.syeyoung.assistant.sync.SyncDocs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import org.apache.pdfbox.util.Matrix
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/** A file in an export: a PDF ([pages] of it, from 0; all if none) or a note (all of it). */
data class ExportItem(val path: String, val pages: List<Int>? = null)

/** The first page of an export: what it is, and whose. */
data class ExportCover(val title: String, val subtitle: String? = null, val author: String? = null, val date: String? = null)

/** What to export: [items] in order, after a [cover] if there's one. */
data class ExportRequest(val items: List<ExportItem>, val cover: ExportCover? = null)

/**
 * Notebooks, PDFs and notes made into one PDF, to hand in: each PDF's pages picked, its writing drawn
 * into the page (some readers and course sites leave annotations out), each note typeset by the core
 * (Typst, in the app's fonts, math and diagrams as the apps draw them), after a cover page if wanted.
 */
@Component
class DriveExport(private val live: SyncDocs) {
    /** The app's fonts, registered with the core once: what notes are set in. */
    private val fonts by lazy {
        for (name in FONTS) registerFont(javaClass.getResourceAsStream("/fonts/$name")!!.use { it.readBytes() })
    }

    /** The export, as one PDF. Fails (IllegalArgumentException) on what can't be exported. */
    suspend fun export(request: ExportRequest): ByteArray {
        require(request.items.isNotEmpty()) { "Nothing to export" }
        fonts
        val parts = mutableListOf<ByteArray>()
        request.cover?.let { c -> parts += typeset { coverPdf(c.title, listOfNotNull(c.subtitle, c.author, c.date)) } }
        for (item in request.items) {
            val name = item.path.substringAfterLast('/')
            // What's being written live goes into the file first.
            live.flush(item.path)
            val (bytes, _) = live.read(item.path) ?: throw IllegalArgumentException("$name isn't there")
            parts += when {
                name.endsWith(".pdf", ignoreCase = true) -> withContext(Dispatchers.Default) { pick(bytes, item.pages) }
                Regex("""\.(md|markdown)$""", RegexOption.IGNORE_CASE).containsMatchIn(name) -> note(bytes.decodeToString(), item.path)
                else -> throw IllegalArgumentException("$name isn't a PDF or a note")
            }
        }
        return withContext(Dispatchers.Default) { merge(parts) }
    }

    /** A note as PDF pages, with the images it shows (from the drive; one not there shows as its description). */
    private suspend fun note(text: String, path: String): ByteArray {
        val folder = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val images = noteImages(text, folder).mapNotNull { p -> live.read(p)?.let { p to it.first } }.toMap()
        return typeset { notePdf(text, folder, images) }
    }

    private suspend fun typeset(make: () -> ByteArray): ByteArray = withContext(Dispatchers.Default) {
        try {
            make()
        } catch (e: RenderException) {
            throw IllegalArgumentException("Couldn't set it: ${e.message}")
        }
    }

    companion object {
        private val FONTS = listOf("Pretendard-Regular.otf", "Pretendard-SemiBold.otf", "Pretendard-Bold.otf", "JetBrainsMono-Regular.ttf")

        /** [pages] of [pdf] (all if none), with what's written on them drawn into them. */
        fun pick(pdf: ByteArray, pages: List<Int>?): ByteArray = Loader.loadPDF(pdf).use { doc ->
            val keep = (pages ?: (0 until doc.numberOfPages).toList()).filter { it in 0 until doc.numberOfPages }
            require(keep.isNotEmpty()) { "No pages picked" }
            PDDocument().use { out ->
                for (i in keep) flatten(out, out.importPage(doc.getPage(i)))
                ByteArrayOutputStream().also { out.save(it) }.toByteArray()
            }
        }

        /**
         * Draws [page]'s annotations (ink, pictures, notes: whatever shows) into its content, as each
         * one's appearance is placed (PDF 32000 12.5.5), and takes them off. Links and form fields stay.
         */
        fun flatten(doc: PDDocument, page: PDPage) {
            val annotations = page.annotations
            val drawn = annotations.filter { a ->
                a !is PDAnnotationLink && a !is PDAnnotationWidget && a !is PDAnnotationPopup && !a.isHidden && !a.isNoView && a.normalAppearanceStream != null
            }
            if (drawn.isEmpty()) return
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                for (a in drawn) {
                    val ap = a.normalAppearanceStream
                    val rect = a.rectangle ?: continue
                    val bbox = ap.bBox ?: continue
                    // The appearance's box, through its matrix, fitted to the annotation's rectangle.
                    val m = ap.matrix.createAffineTransform()
                    val box = m.createTransformedShape(java.awt.geom.Rectangle2D.Float(bbox.lowerLeftX, bbox.lowerLeftY, bbox.width, bbox.height)).bounds2D
                    if (box.width <= 0 || box.height <= 0) continue
                    val sx = rect.width / box.width.toFloat()
                    val sy = rect.height / box.height.toFloat()
                    cs.saveGraphicsState()
                    cs.transform(Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - box.x.toFloat() * sx, rect.lowerLeftY - box.y.toFloat() * sy))
                    cs.drawForm(PDFormXObject(ap.cosObject))
                    cs.restoreGraphicsState()
                }
            }
            page.annotations = annotations - drawn.toSet()
        }

        /** The parts, one after another. */
        fun merge(parts: List<ByteArray>): ByteArray {
            val docs = parts.map { Loader.loadPDF(it) }
            try {
                PDDocument().use { out ->
                    for (d in docs) for (p in d.pages) out.importPage(p)
                    return ByteArrayOutputStream().also { out.save(it) }.toByteArray()
                }
            } finally {
                docs.forEach { it.close() }
            }
        }
    }
}
