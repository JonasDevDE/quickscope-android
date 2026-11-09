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
import org.junit.Assert.assertFalse
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
            val text = f.readText(Charsets.UTF_8)
            assertTrue(text.contains("[") || f.name == "impressum.md")   // restliche Platzhalter bleiben sichtbar (Datenschutz, AGB)
            assertFalse(text.contains("[Vor- und Nachname"))   // Verantwortlicher ist eingesetzt
            assertFalse(text.contains("[Anschrift]") || text.contains("[PLZ Ort]"))
            assertTrue(blocks.any { it is MdBlock.Note && it.text.startsWith("Entwurf") })
        }
        assertEquals(3, LegalPage.entries.size)
    }

    @Test fun legalTextsNameTheProviderAndKeepOpenPlaceholders() {
        val all = LegalPage.entries.associateWith { java.io.File("src/main/assets/legal/${it.file}.md").readText(Charsets.UTF_8) }
        for ((_, t) in all) {
            assertTrue(t.contains("Jonas Schnell"))
            assertTrue(t.contains("Wuhlestraße 7a"))
            assertTrue(t.contains("12683 Berlin"))
            assertTrue(t.contains("jonas.schnell@darkstone.de"))
            assertFalse(t.contains("[E-Mail-Adresse]"))
        }
        assertTrue(all.getValue(LegalPage.Privacy).contains("[___ Tage"))       // Speicherdauer der Protokolle
        assertTrue(all.getValue(LegalPage.Privacy).contains("[Mindestalter"))   // Kinder und Jugendliche
        assertTrue(all.getValue(LegalPage.Privacy).contains("Auftragsverarbeitungsvertrag"))   // Google-Tarif/AVV
        assertTrue(all.getValue(LegalPage.Terms).contains("[Haftungsregelung"))
        assertFalse(all.getValue(LegalPage.Imprint).contains("Telefon"))
        assertEquals("Allgemeine Geschäftsbedingungen", LegalPage.Terms.title)
    }
}
