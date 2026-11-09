package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.logic.ExportOutcome
import com.jonasschnell.quickscope.logic.copyExport
import com.jonasschnell.quickscope.logic.exportFailureReason
import com.jonasschnell.quickscope.logic.exportOutcome
import com.jonasschnell.quickscope.logic.LegalPage
import com.jonasschnell.quickscope.logic.MdBlock
import com.jonasschnell.quickscope.logic.MdSpan
import com.jonasschnell.quickscope.logic.parseMarkdown
import com.jonasschnell.quickscope.logic.parseSpans
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportLegalTest {
    @Test fun exportCopiesAllBytesAndCountsSaved() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val n = copyExport(ByteArrayInputStream(data), out)
        assertEquals(300_000L, n)
        assertEquals(data.toList(), out.toByteArray().toList())
        assertEquals(ExportOutcome.Saved, exportOutcome(n))
    }

    @Test fun emptyOrFailedExportIsAFailure() {
        assertEquals(ExportOutcome.Failed, exportOutcome(0))
        assertEquals(ExportOutcome.Failed, exportOutcome(null))
        assertEquals("IOException: kaputt", exportFailureReason(IOException("kaputt")))
    }

    @Test fun markdownRendersHeadingsListsAndBold() {
        val md = "# Titel\n\n~ klein\n\n## Abschnitt\n\nText mit **fett** und [Platzhalter].\nzweite Zeile\n\n- eins\n\n- **zwei** x\n"
        val b = parseMarkdown(md)
        assertEquals(MdBlock.H1("Titel"), b[0])
        assertEquals(MdBlock.Note("klein"), b[1])
        assertEquals(MdBlock.H2("Abschnitt"), b[2])
        val para = b[3] as MdBlock.Para
        assertEquals(listOf(MdSpan("Text mit "), MdSpan("fett", true), MdSpan(" und [Platzhalter].\nzweite Zeile")), para.spans)
        assertEquals(MdBlock.Bullet(listOf(MdSpan("eins"))), b[4])
        assertEquals(MdBlock.Bullet(listOf(MdSpan("zwei", true), MdSpan(" x"))), b[5])
        assertEquals(6, b.size)
        assertEquals(listOf(MdSpan("a ** b")), parseSpans("a ** b"))   // nicht geschlossen: normaler Text
    }

    @Test fun legalPagesExistAndKeepPlaceholders() {
        for (page in LegalPage.entries) {
            val f = java.io.File("src/main/assets/legal/${page.file}.md")
            assertTrue("${f.path} fehlt", f.exists())
            val blocks = parseMarkdown(f.readText(Charsets.UTF_8))
            assertTrue(blocks.first() is MdBlock.H1)
            assertTrue(blocks.size > 5)
            assertTrue(f.readText(Charsets.UTF_8).contains("["))   // Platzhalter bleiben sichtbar
            assertTrue(blocks.any { it is MdBlock.Note && it.text.startsWith("Entwurf") })
        }
        assertEquals(3, LegalPage.entries.size)
    }
}
