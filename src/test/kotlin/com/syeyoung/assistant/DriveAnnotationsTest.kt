package com.syeyoung.assistant

import com.syeyoung.assistant.core.NotebookEdit
import com.syeyoung.assistant.core.NotebookPage
import com.syeyoung.assistant.drive.DriveAnnotations
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/** Carrying writing over to a course file's new version: which page goes where. */
class DriveAnnotationsTest {
    private fun pdf(vararg pages: String): ByteArray = PDDocument().use { doc ->
        for (text in pages) {
            val page = PDPage()
            doc.addPage(page)
            if (text.isNotEmpty()) PDPageContentStream(doc, page).use { c ->
                c.beginText()
                c.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                c.newLineAtOffset(72f, 700f)
                c.showText(text)
                c.endText()
            }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun page(id: String, base: Int) = NotebookPage(id, base, "", 612f, 792f)

    @Test
    fun `pages match by their words, not their place`() {
        val old = listOf("Intro to probability and sample spaces", "Bayes theorem with an example", "A slide taken out later")
        val new = listOf("Intro to probability and sample spaces", "Variance and expectation, new this year", "Bayes theorem with an example (fixed typo)")
        assertEquals(listOf(0, 2, null), DriveAnnotations.matchPages(old, new))
    }

    @Test
    fun `pages without text go by place when the counts agree`() {
        assertEquals(listOf(0, 1), DriveAnnotations.matchPages(listOf("", ""), listOf("", "")))
        assertEquals(listOf(null, null), DriveAnnotations.matchPages(listOf("", ""), listOf("", "", "")))
    }

    @Test
    fun `writing follows its page, a page gone is kept, and a new one goes in its place`() {
        val old = pdf("Intro to probability and sample spaces", "Bayes theorem with an example", "A slide taken out later")
        val new = pdf("Intro to probability and sample spaces", "Variance and expectation new this year", "Bayes theorem with an example")
        val newPages = (0 until 3).map { page("n$it", it) }
        // The notebook: the three slides, with a blank page written on between the second and third.
        val pages = listOf(page("a", 0), page("b", 1), NotebookPage("blank", -1, "grid", 612f, 792f), page("c", 2))
        val plan = DriveAnnotations.carryOver(old, new, newPages, pages)

        val puts = plan.edits.filterIsInstance<NotebookEdit.PutPage>().associate { it.page.id to it.page.base }
        assertEquals(mapOf("a" to 0, "b" to 2, "c" to 3), puts)
        val inserts = plan.edits.filterIsInstance<NotebookEdit.InsertPage>()
        assertEquals(1, inserts.size)
        // The new slide (base 1) after the first page.
        assertEquals(1, inserts[0].page.base)
        assertEquals(1u, inserts[0].index)
        // The slide taken out is kept, after the new version's pages.
        Loader.loadPDF(plan.base).use { doc ->
            assertEquals(4, doc.numberOfPages)
            val text = PDFTextStripper().apply { startPage = 4; endPage = 4 }.getText(doc)
            assertEquals("A slide taken out later", text.trim())
        }
    }
}
