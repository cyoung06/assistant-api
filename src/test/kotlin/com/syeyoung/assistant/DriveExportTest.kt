package com.syeyoung.assistant

import com.syeyoung.assistant.core.NotebookPage
import com.syeyoung.assistant.core.NotebookStroke
import com.syeyoung.assistant.drive.DriveExport
import com.syeyoung.assistant.sync.NotebookPdf
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream

/** Exports' PDFs: pages picked, the writing drawn into them, parts one after another. */
class DriveExportTest {
    private fun pdf(vararg pages: String): ByteArray = PDDocument().use { doc ->
        for (text in pages) {
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { c ->
                c.beginText()
                c.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                c.newLineAtOffset(72f, 700f)
                c.showText(text)
                c.endText()
            }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun text(pdf: ByteArray) = Loader.loadPDF(pdf).use { doc ->
        (1..doc.numberOfPages).map { PDFTextStripper().apply { startPage = it; endPage = it }.getText(doc).trim() }
    }

    /** How many of a page's pixels are darker than paper. */
    private fun inked(pdf: ByteArray, page: Int) = Loader.loadPDF(pdf).use { doc ->
        val image = PDFRenderer(doc).renderImageWithDPI(page, 36f)
        (0 until image.width).sumOf { x -> (0 until image.height).count { y -> (image.getRGB(x, y) and 0xff) < 128 } }
    }

    @Test
    fun `the pages picked, in that order, with what's written drawn into them`() {
        val read = NotebookPdf.read(pdf("one", "two", "three"))
        val page = read.pages[2]
        // A thick stroke across the third page (an ink annotation with its outline as the appearance).
        val stroke = NotebookStroke("s", page.id, "pen", 0xff000000u, 8f, listOf(100f, 400f, 0.5f, 500f, 400f, 0.5f), listOf(listOf(100f, 390f, 500f, 390f, 500f, 410f, 100f, 410f)), null, null)
        val notebook = NotebookPdf.write(read.base, read.pages, listOf(stroke), emptyList(), emptyList())
        Loader.loadPDF(notebook).use { assertEquals(1, it.getPage(2).annotations.size) }

        val picked = DriveExport.pick(notebook, listOf(2, 0))
        assertEquals(listOf("three", "one"), text(picked))
        Loader.loadPDF(picked).use { doc -> assertTrue(doc.pages.all { it.annotations.isEmpty() }, "the writing is part of the page now") }
        // As much ink on the page as before: drawn, where it was.
        assertEquals(inked(notebook, 2), inked(picked, 0))
        assertTrue(inked(picked, 0) > inked(picked, 1))
    }

    @Test
    fun `parts follow one another, and nothing picked is refused`() {
        val merged = DriveExport.merge(listOf(pdf("cover"), pdf("a", "b"), pdf("note")))
        assertEquals(listOf("cover", "a", "b", "note"), text(merged))
        assertThrows<IllegalArgumentException> { DriveExport.pick(pdf("a"), listOf(5)) }
    }
}
