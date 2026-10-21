package com.syeyoung.assistant.sync

import com.syeyoung.assistant.core.NotebookImage
import com.syeyoung.assistant.core.NotebookPage
import com.syeyoung.assistant.core.NotebookStroke
import com.syeyoung.assistant.core.blankPage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSFloat
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.contentstream.operator.Operator
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

/**
 * A notebook's PDF, as the Android app writes it (its NotebookFile): our ink as standard PDF ink
 * annotations (the points as the InkList, the exact outline as the appearance, so any reader shows
 * it as written) holding the app's own encoding of each stroke too; pictures as stamp annotations
 * holding their bytes; bookmarks as outline items, marked as ours. Plus, from here: ids for pages,
 * strokes and pictures, and a stroke's move as a transform, so devices editing it at once agree on
 * what's what.
 *
 * Read ([read]) into what the live document holds and a "base" (the PDF without any of ours);
 * written ([write]) back from them.
 */
object NotebookPdf {
    private val INK = COSName.getPDFName("AssistantInk")
    private val TOOL = COSName.getPDFName("AssistantTool")
    private val COLOR = COSName.getPDFName("AssistantColor")
    private val SIZE = COSName.getPDFName("AssistantSize")
    private val INK_LIST = COSName.getPDFName("InkList")
    private val CA = COSName.getPDFName("CA")
    private val LOWER_CA = COSName.getPDFName("ca")
    private val BOOKMARK = COSName.getPDFName("AssistantBookmark")
    private val IMAGE = COSName.getPDFName("AssistantImage")
    private val IMAGE_TYPE = COSName.getPDFName("AssistantImageType")
    private val IMAGE_CROP = COSName.getPDFName("AssistantImageCrop")
    private val ID = COSName.getPDFName("AssistantId")
    private val PAGE_ID = COSName.getPDFName("AssistantPageId")
    private val TRANSFORM = COSName.getPDFName("AssistantTransform")

    /** A picture on a page, and its bytes. */
    class Picture(val image: NotebookImage, val bytes: ByteArray)

    /** What [read] found: the base (no ink, pictures or bookmarks of ours; pages carrying their ids) and what's on it. */
    class Read(val base: ByteArray, val pages: List<NotebookPage>, val strokes: List<NotebookStroke>, val pictures: List<Picture>, val bookmarks: List<String>)

    fun read(pdf: ByteArray): Read = Loader.loadPDF(pdf).use { doc ->
        val pages = mutableListOf<NotebookPage>()
        val strokes = mutableListOf<NotebookStroke>()
        val pictures = mutableListOf<Picture>()
        doc.pages.forEachIndexed { index, page ->
            val id = page.cosObject.getString(PAGE_ID) ?: UUID.randomUUID().toString().also { page.cosObject.setString(PAGE_ID, it) }
            val (w, h) = sizeOf(page)
            pages += NotebookPage(id, index, "", w, h)
            val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return@forEachIndexed
            val crop = page.cropBox
            for (i in annots.size() - 1 downTo 0) {
                val a = annots.getObject(i) as? COSDictionary ?: continue
                when {
                    a.containsKey(INK) -> runCatching { strokes += strokeOf(a, id, crop) }.onSuccess { annots.remove(i) }
                    a.containsKey(IMAGE) -> runCatching { pictures += pictureOf(a, id, crop) }.onSuccess { annots.remove(i) }
                }
            }
        }
        val bookmarks = takeBookmarks(doc)
        val base = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        Read(base, pages, strokes.reversed(), pictures.reversed(), bookmarks)
    }

    /** The notebook as a PDF: [base]'s pages (or blank ones) as [pages] has them, and what's on them. */
    fun write(base: ByteArray, pages: List<NotebookPage>, strokes: List<NotebookStroke>, pictures: List<Picture>, bookmarks: List<String>): ByteArray =
        Loader.loadPDF(base).use { source ->
            PDDocument().use { doc ->
                val byId = HashMap<String, PDPage>()
                for (p in pages) {
                    val page = if (p.base >= 0 && p.base < source.numberOfPages) doc.importPage(source.getPage(p.base)) else blank(doc, p)
                    page.cosObject.setString(PAGE_ID, p.id)
                    byId[p.id] = page
                }
                // Pictures first, so the ink is drawn over them.
                for (pic in pictures) {
                    val page = byId[pic.image.page] ?: continue
                    runCatching { annotsOf(page).add(pictureAnnotation(doc, page, pic)) }
                }
                for (s in strokes) {
                    val page = byId[s.page] ?: continue
                    annotation(doc, page, s)?.let { annotsOf(page).add(it) }
                }
                if (bookmarks.isNotEmpty()) {
                    val outline = doc.documentCatalog.documentOutline ?: PDDocumentOutline().also { doc.documentCatalog.documentOutline = it }
                    pages.forEachIndexed { i, p ->
                        if (p.id !in bookmarks) return@forEachIndexed
                        outline.addLast(PDOutlineItem().apply {
                            title = "Page ${i + 1}"
                            destination = PDPageFitDestination().apply { setPage(byId[p.id]) }
                            cosObject.setBoolean(BOOKMARK, true)
                        })
                    }
                }
                ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
            }
        }

    private fun annotsOf(page: PDPage) = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: COSArray().also { page.cosObject.setItem(COSName.ANNOTS, it) }

    private fun blank(doc: PDDocument, p: NotebookPage): PDPage {
        val page = PDPage(PDRectangle(p.width, p.height))
        page.resources = PDResources()
        val content = PDStream(doc)
        content.createOutputStream(COSName.FLATE_DECODE).use { it.write(blankPage(p.style, p.width, p.height).toByteArray(Charsets.US_ASCII)) }
        page.setContents(content)
        doc.addPage(page)
        return page
    }

    /** A page's size in points as it shows: its crop box, turned with the page. */
    private fun sizeOf(page: PDPage): Pair<Float, Float> {
        val box = page.cropBox
        val turned = ((page.rotation % 360) + 360) % 360 in setOf(90, 270)
        return if (turned) box.height to box.width else box.width to box.height
    }

    // ---- reading ----

    private fun strokeOf(a: COSDictionary, page: String, crop: PDRectangle): NotebookStroke {
        fun x(v: Float) = v - crop.lowerLeftX
        fun y(v: Float) = crop.upperRightY - v
        val transform = (a.getDictionaryObject(TRANSFORM) as? COSArray)?.toFloatArray()?.takeIf { it.size == 6 }
        // Points and outline as written (moved, if it was), back to before the move.
        val undo = transform?.let(::invert)
        fun back(px: Float, py: Float): Pair<Float, Float> = undo?.let { m -> m[0] * px + m[2] * py + m[4] to m[1] * px + m[3] * py + m[5] } ?: (px to py)
        val inkList = (a.getDictionaryObject(INK_LIST) as? COSArray)?.getObject(0) as? COSArray
        val points = mutableListOf<Float>()
        if (inkList != null) for (i in 0 until inkList.size() - 1 step 2) {
            val (px, py) = back(x((inkList.getObject(i) as COSNumber).floatValue()), y((inkList.getObject(i + 1) as COSNumber).floatValue()))
            points += px; points += py; points += 0.5f
        }
        val outline = outlineOf(a).map { poly -> poly.chunked(2).flatMap { (px, py) -> back(x(px), y(py)).toList() } }
        val tool = (a.getDictionaryObject(TOOL) as? COSName)?.name?.lowercase() ?: "pen"
        return NotebookStroke(
            id = a.getString(ID) ?: UUID.randomUUID().toString(),
            page = page,
            tool = tool,
            color = a.getInt(COLOR).toUInt(),
            width = a.getFloat(SIZE, 1f),
            points = points,
            outline = outline,
            ink = (a.getDictionaryObject(INK) as? COSString)?.bytes,
            transform = transform?.toList(),
        )
    }

    /** The polygons the appearance fills (m … l … h, in the page's own coordinates), as [annotation] writes them. */
    private fun outlineOf(a: COSDictionary): List<List<Float>> {
        val ap = (a.getDictionaryObject(COSName.AP) as? COSDictionary)?.getDictionaryObject(COSName.N) as? COSStream ?: return emptyList()
        val polygons = mutableListOf<List<Float>>()
        var current = mutableListOf<Float>()
        val operands = mutableListOf<Float>()
        for (token in PDFStreamParser(org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(ap)).parse()) {
            when (token) {
                is COSNumber -> operands += token.floatValue()
                is Operator -> {
                    when (token.name) {
                        "m" -> { if (current.size >= 6) polygons += current; current = mutableListOf(operands[0], operands[1]) }
                        "l" -> { current += operands[0]; current += operands[1] }
                        "h", "f" -> { if (current.size >= 6) polygons += current; current = mutableListOf() }
                    }
                    operands.clear()
                }
                else -> operands.clear()
            }
        }
        if (current.size >= 6) polygons += current
        return polygons
    }

    private fun pictureOf(a: COSDictionary, page: String, crop: PDRectangle): Picture {
        val rect = (a.getDictionaryObject(COSName.RECT) as COSArray).toFloatArray()
        val bytes = (a.getDictionaryObject(IMAGE) as COSStream).createRawInputStream().use { it.readBytes() }
        val c = (a.getDictionaryObject(IMAGE_CROP) as? COSArray)?.toFloatArray()?.takeIf { it.size == 4 } ?: floatArrayOf(0f, 0f, 1f, 1f)
        val image = NotebookImage(
            id = a.getString(ID) ?: UUID.randomUUID().toString(),
            page = page,
            rect = listOf(rect[0] - crop.lowerLeftX, crop.upperRightY - rect[3], rect[2] - rect[0], rect[3] - rect[1]),
            crop = c.toList(),
            blob = Blobs.hashOf(bytes),
            mime = a.getString(IMAGE_TYPE) ?: "image/png",
        )
        return Picture(image, bytes)
    }

    /** Our bookmarks (outline items marked as ours): their pages' ids; taken out, the PDF's own stay. */
    private fun takeBookmarks(doc: PDDocument): List<String> {
        val outline = doc.documentCatalog.documentOutline ?: return emptyList()
        val items = outline.children().toList()
        val ours = items.filter { it.cosObject.getBoolean(BOOKMARK, false) }
        if (ours.isEmpty()) return emptyList()
        val pages = ours.mapNotNull { item -> runCatching { item.findDestinationPage(doc) }.getOrNull()?.cosObject?.getString(PAGE_ID) }
        val kept = items - ours.toSet()
        val fresh = PDDocumentOutline()
        kept.forEach { item ->
            listOf(COSName.PREV, COSName.NEXT, COSName.PARENT).forEach { item.cosObject.removeItem(it) }
            fresh.addLast(item)
        }
        doc.documentCatalog.documentOutline = if (kept.isEmpty()) null else fresh
        return pages
    }

    // ---- writing (as the Android app does) ----

    /** A stamp annotation showing [pic] (so any reader shows it), holding its bytes as imported. */
    private fun pictureAnnotation(doc: PDDocument, page: PDPage, pic: Picture): COSDictionary {
        val img = pic.image
        val crop = page.cropBox
        val (x, y, w, h) = img.rect
        val rect = floatArrayOf(crop.lowerLeftX + x, crop.upperRightY - (y + h), crop.lowerLeftX + x + w, crop.upperRightY - y)
        val xobject = PDImageXObject.createFromByteArray(doc, pic.bytes, img.id)
        val ap = PDAppearanceStream(doc)
        ap.bBox = PDRectangle(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
        ap.resources = PDResources()
        // The whole picture, clipped to the part shown.
        val (cl, ct, cr, cb) = img.crop
        val fullW = w / (cr - cl)
        val fullH = h / (cb - ct)
        val fullLeft = x - cl * fullW
        val fullTop = y - ct * fullH
        PDPageContentStream(doc, ap).use {
            it.saveGraphicsState()
            it.addRect(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
            it.clip()
            it.drawImage(xobject, crop.lowerLeftX + fullLeft, crop.upperRightY - (fullTop + fullH), fullW, fullH)
            it.restoreGraphicsState()
        }
        val raw = doc.document.createCOSStream()
        raw.createRawOutputStream().use { it.write(pic.bytes) }
        return COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
            setItem(COSName.RECT, floats(*rect))
            setItem(COSName.F, COSInteger.get(4)) // print
            setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, ap.cosObject) })
            setItem(IMAGE, raw)
            setString(IMAGE_TYPE, img.mime)
            setItem(IMAGE_CROP, floats(*img.crop.toFloatArray()))
            setString(ID, img.id)
        }
    }

    /** An ink annotation for [s]: its points as the InkList, its outline (moved, if it was) as the appearance. */
    private fun annotation(doc: PDDocument, page: PDPage, s: NotebookStroke): COSDictionary? {
        val crop = page.cropBox
        val m = s.transform?.toFloatArray()
        fun moved(px: Float, py: Float): Pair<Float, Float> = m?.let { t -> t[0] * px + t[2] * py + t[4] to t[1] * px + t[3] * py + t[5] } ?: (px to py)
        fun pdf(px: Float, py: Float): Pair<Float, Float> = moved(px, py).let { (a, b) -> crop.lowerLeftX + a to crop.upperRightY - b }
        val outline = s.outline.filter { it.size >= 6 }.map { poly -> poly.chunked(2).map { (px, py) -> pdf(px, py) } }
        val all = outline.flatten().ifEmpty { s.points.chunked(3).filter { it.size == 3 }.map { (px, py, _) -> pdf(px, py) } }
        if (all.isEmpty()) return null
        val rect = floatArrayOf(all.minOf { it.first } - 1, all.minOf { it.second } - 1, all.maxOf { it.first } + 1, all.maxOf { it.second } + 1)
        val color = s.color.toInt()
        val alpha = (color ushr 24) / 255f
        val (r, g, b) = listOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF).map { it / 255f }

        val drawing = StringBuilder()
        drawing.append(String.format(Locale.US, "%.3f %.3f %.3f rg\n", r, g, b))
        if (alpha < 1f) drawing.append("/GS0 gs\n")
        for (poly in outline) {
            poly.forEachIndexed { i, (px, py) -> drawing.append(String.format(Locale.US, "%.2f %.2f %s\n", px, py, if (i == 0) "m" else "l")) }
            drawing.append("h\n")
        }
        drawing.append("f\n")
        val appearance = doc.document.createCOSStream()
        appearance.setItem(COSName.TYPE, COSName.XOBJECT)
        appearance.setItem(COSName.SUBTYPE, COSName.FORM)
        appearance.setItem(COSName.BBOX, floats(*rect))
        if (alpha < 1f) {
            val gs = COSDictionary().apply {
                setItem(LOWER_CA, COSFloat(alpha))
                setItem(COSName.getPDFName("BM"), COSName.getPDFName("Multiply"))
            }
            appearance.setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.EXT_G_STATE, COSDictionary().apply { setItem(COSName.getPDFName("GS0"), gs) }) })
        }
        appearance.createOutputStream(COSName.FLATE_DECODE).use { it.write(drawing.toString().toByteArray(Charsets.US_ASCII)) }

        val points = COSArray()
        s.points.chunked(3).filter { it.size == 3 }.forEach { (px, py, _) -> val (a, b) = pdf(px, py); points.add(COSFloat(a)); points.add(COSFloat(b)) }
        return COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, COSName.getPDFName("Ink"))
            setItem(COSName.RECT, floats(*rect))
            setItem(COSName.F, COSInteger.get(4)) // print
            setItem(COSName.C, floats(r, g, b))
            if (alpha < 1f) setItem(CA, COSFloat(alpha))
            setItem(COSName.BS, COSDictionary().apply { setItem(COSName.W, COSFloat(s.width)) })
            setItem(INK_LIST, COSArray().apply { add(points) })
            setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, appearance) })
            // How to edit it again: the Android app's encoding (when written there), the pen, and where it was moved.
            if (s.ink != null) setItem(INK, COSString(s.ink)) else setItem(INK, COSString(ByteArray(0)))
            setItem(TOOL, COSName.getPDFName(s.tool.uppercase()))
            setInt(COLOR, color)
            setFloat(SIZE, s.width)
            setString(ID, s.id)
            s.transform?.let { setItem(TRANSFORM, floats(*it.toFloatArray())) }
        }
    }

    /** The inverse of an affine transform (a, b, c, d, e, f). */
    private fun invert(t: FloatArray): FloatArray {
        val (a, b, c, d) = t
        val e = t[4]
        val f = t[5]
        val det = a * d - b * c
        return floatArrayOf(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det)
    }

    private fun floats(vararg values: Float) = COSArray().apply { values.forEach { add(COSFloat(it)) } }
}
