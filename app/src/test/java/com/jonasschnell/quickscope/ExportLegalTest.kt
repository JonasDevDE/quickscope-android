package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.logic.ExportOutcome
import com.jonasschnell.quickscope.logic.copyExport
import com.jonasschnell.quickscope.logic.exportFailureReason
import com.jonasschnell.quickscope.logic.exportOutcome
import com.jonasschnell.quickscope.logic.legalUrl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
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

    @Test fun legalUrlsHaveNoDoubleSlash() {
        assertEquals("https://x.example/legal/datenschutz", legalUrl("https://x.example/", "datenschutz"))
        assertEquals("https://x.example/legal/agb", legalUrl("https://x.example", "agb"))
        assertEquals("https://x.example/legal/agb", legalUrl("https://x.example//", "/agb"))
        assertEquals("https://recht.example/p", legalUrl("https://x.example/", "datenschutz", " https://recht.example/p "))
    }
}
