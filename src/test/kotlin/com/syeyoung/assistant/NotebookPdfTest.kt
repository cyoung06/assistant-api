package com.syeyoung.assistant

import com.syeyoung.assistant.core.NotebookImage
import com.syeyoung.assistant.core.NotebookPage
import com.syeyoung.assistant.core.NotebookStroke
import com.syeyoung.assistant.sync.Blobs
import com.syeyoung.assistant.sync.NotebookPdf
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSFloat
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** [NotebookPdf]: notebooks as the Android app writes them, read; and what's read, written back the same. */
class NotebookPdfTest {
    /** A two-page A4 PDF, as a course file would be. */
    private fun twoPages(): ByteArray = PDDocument().use { doc ->
        repeat(2) { doc.addPage(PDPage(PDRectangle.A4)) }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun pngBytes(): ByteArray {
        val img = BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 0xFF0000) }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun near(a: List<Float>, b: List<Float>) = a.size == b.size && a.indices.all { kotlin.math.abs(a[it] - b[it]) < 0.02f }

    @Test
    fun `a stroke as the tablet writes it is read, with its page, pen, colour, points, outline and Ink data`() {
        // The Android app's annotation (NotebookFile.annotation): no ids, no transform.
        val pdf = PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            val top = PDRectangle.A4.height
            val appearance = doc.document.createCOSStream()
            appearance.setItem(COSName.TYPE, COSName.XOBJECT)
            appearance.setItem(COSName.SUBTYPE, COSName.FORM)
            appearance.setItem(COSName.BBOX, COSArray().apply { listOf(9f, top - 41f, 31f, top - 19f).forEach { add(COSFloat(it)) } })
            appearance.createOutputStream(COSName.FLATE_DECODE).use {
                it.write("0.106 0.106 0.122 rg\n10.00 ${top - 20} m\n30.00 ${top - 40} l\n29.00 ${top - 41} l\nh\nf\n".toByteArray())
            }
            val annot = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.ANNOT)
                setItem(COSName.SUBTYPE, COSName.getPDFName("Ink"))
                setItem(COSName.RECT, COSArray().apply { listOf(9f, top - 41f, 31f, top - 19f).forEach { add(COSFloat(it)) } })
                setItem(COSName.F, COSInteger.get(4))
                setItem(COSName.getPDFName("InkList"), COSArray().apply { add(COSArray().apply { listOf(10f, top - 20f, 30f, top - 40f).forEach { add(COSFloat(it)) } }) })
                setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, appearance) })
                setItem(COSName.getPDFName("AssistantInk"), COSString(byteArrayOf(1, 2, 3)))
                setItem(COSName.getPDFName("AssistantTool"), COSName.getPDFName("PEN"))
                setInt(COSName.getPDFName("AssistantColor"), 0xFF1B1B1F.toInt())
                setFloat(COSName.getPDFName("AssistantSize"), 0.45f)
            }
            page.cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(annot) })
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val read = NotebookPdf.read(pdf)
        assertEquals(1, read.pages.size)
        val s = read.strokes.single()
        assertEquals(read.pages[0].id, s.page)
        assertEquals("pen", s.tool)
        assertEquals(0xFF1B1B1Fu, s.color)
        assertEquals(0.45f, s.width)
        assertTrue(near(s.points, listOf(10f, 20f, 0.5f, 30f, 40f, 0.5f)), s.points.toString())
        assertTrue(near(s.outline.single(), listOf(10f, 20f, 30f, 40f, 29f, 41f)), s.outline.toString())
        assertEquals(listOf<Byte>(1, 2, 3), s.ink?.toList())
        // The base keeps none of ours.
        Loader.loadPDF(read.base).use { assertEquals(null, it.getPage(0).cosObject.getDictionaryObject(COSName.ANNOTS).let { a -> (a as? COSArray)?.takeIf { it.size() > 0 } }) }
    }

    @Test
    fun `a notebook written and read again is the same, with pages, a blank page, a moved stroke, a highlighter, a picture and a bookmark`() {
        val first = NotebookPdf.read(twoPages())
        val (p1, p2) = first.pages
        val blank = NotebookPage("blank-1", -1, "grid", 595f, 842f)
        val pages = listOf(p1, blank, p2)
        val pen = NotebookStroke("s-pen", blank.id, "pen", 0xFF1E5BD8u, 0.6f, listOf(50f, 60f, 0.3f, 80f, 90f, 0.9f), listOf(listOf(49f, 59f, 81f, 89f, 79f, 91f)), byteArrayOf(7, 7), listOf(1f, 0f, 0f, 1f, 100f, 50f))
        val marker = NotebookStroke("s-hl", p2.id, "highlighter", 0x66FFD600u, 9f, listOf(10f, 10f, 0.5f, 200f, 10f, 0.5f), listOf(listOf(10f, 5f, 200f, 5f, 200f, 15f, 10f, 15f)), null, null)
        val png = pngBytes()
        val picture = NotebookPdf.Picture(NotebookImage("img-1", p1.id, listOf(100f, 200f, 80f, 40f), listOf(0f, 0f, 1f, 1f), Blobs.hashOf(png), "image/png"), png)

        val written = NotebookPdf.write(first.base, pages, listOf(pen, marker), listOf(picture), listOf(blank.id))
        val again = NotebookPdf.read(written)

        assertEquals(pages, again.pages.mapIndexed { i, p -> p.copy(base = pages[i].base, style = pages[i].style) })
        assertEquals(listOf(0, 1, 2), again.pages.map { it.base }, "the PDF's pages in order (the blank one now part of it)")
        val byId = again.strokes.associateBy { it.id }
        assertEquals(setOf("s-pen", "s-hl"), byId.keys)
        val p = byId.getValue("s-pen")
        assertEquals(blank.id, p.page)
        assertEquals(pen.transform, p.transform)
        // Read back as before the move: the same points and outline.
        assertTrue(near(p.points.chunked(3).flatMap { it.take(2) }, pen.points.chunked(3).flatMap { it.take(2) }), p.points.toString())
        assertTrue(near(p.outline.single(), pen.outline.single()), p.outline.toString())
        assertEquals(listOf<Byte>(7, 7), p.ink?.toList())
        val h = byId.getValue("s-hl")
        assertEquals("highlighter", h.tool)
        assertEquals(0x66FFD600u, h.color)
        val pic = again.pictures.single()
        assertEquals(picture.image, pic.image)
        assertEquals(png.toList(), pic.bytes.toList())
        assertEquals(listOf(blank.id), again.bookmarks)

        // Any reader shows it: the blank page's grid and the ink are drawn.
        Loader.loadPDF(written).use { doc ->
            val img = PDFRenderer(doc).renderImageWithDPI(1, 72f)
            // The pen stroke moved to (150, 110)..(180, 140): something dark blue near there.
            val px = img.getRGB(165, 125)
            assertNotNull(px)
            assertTrue((px and 0xFF) > (px shr 16 and 0xFF), "blue ink at the moved stroke: %06x".format(px and 0xFFFFFF))
        }
    }
}
