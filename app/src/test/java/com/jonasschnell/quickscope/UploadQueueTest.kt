package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.logic.UPLOAD_MAX_EDGE
import com.jonasschnell.quickscope.logic.UploadItemState
import com.jonasschnell.quickscope.logic.needsImageCompression
import com.jonasschnell.quickscope.logic.runUploadQueue
import com.jonasschnell.quickscope.logic.scaledSize
import com.jonasschnell.quickscope.logic.uploadFailureText
import com.jonasschnell.quickscope.logic.uploadProgressText
import com.jonasschnell.quickscope.logic.uploadRetryable
import com.jonasschnell.quickscope.logic.uploadSummaryText
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class UploadQueueTest {
    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody("text/plain".toMediaType())))

    private suspend fun runQ(items: List<String>, upload: suspend (Int, String) -> Unit, log: MutableList<String> = mutableListOf(), waits: MutableList<Long> = mutableListOf()) =
        runUploadQueue(
            size = { items.size }, itemAt = { items[it] }, wait = { waits += it }, backoffMs = { 1000L shl it },
            onState = { i, st, _ -> log += "$i:${st.name}" }, upload = upload,
        )

    @Test fun filesAreUploadedStrictlyOneAfterTheOtherInOrder() = runBlocking {
        val order = mutableListOf<String>()
        var running = 0
        var maxParallel = 0
        val s = runQ(listOf("a", "b", "c", "d"), { _, f ->
            running++; maxParallel = maxOf(maxParallel, running)
            order += f
            running--
        })
        assertEquals(listOf("a", "b", "c", "d"), order)
        assertEquals(1, maxParallel)
        assertEquals(4, s.done)
        assertTrue(s.failed.isEmpty())
        assertEquals("4 von 4 hochgeladen", uploadSummaryText(s.done, s.total))
    }

    @Test fun oneFailureDoesNotStopTheOthersAndSummaryCountsThem() = runBlocking {
        val log = mutableListOf<String>()
        val s = runQ(listOf("a", "b", "c"), { _, f -> if (f == "b") throw http(413) }, log)
        assertEquals(2, s.done)
        assertEquals(listOf(1), s.failed)                     // nur "b"
        assertEquals("2 von 3 hochgeladen", uploadSummaryText(s.done, s.total))
        assertEquals(listOf("0:Uploading", "0:Done", "1:Uploading", "1:Failed", "2:Uploading", "2:Done"), log)   // c wird trotzdem hochgeladen
    }

    @Test fun retriesUpToThreeTimesWithBackoffThenMovesOn() = runBlocking {
        val attempts = HashMap<String, Int>()
        val waits = mutableListOf<Long>()
        val s = runQ(listOf("a", "b"), { _, f ->
            attempts[f] = (attempts[f] ?: 0) + 1
            if (f == "a") throw http(503)                       // immer 5xx
            if (f == "b" && attempts[f]!! < 3) throw IOException("Netz")   // klappt beim dritten Versuch
        }, waits = waits)
        assertEquals(3, attempts["a"])
        assertEquals(3, attempts["b"])
        assertEquals(1, s.done)
        assertEquals(listOf(0), s.failed)
        assertEquals(listOf(1000L, 2000L, 1000L, 2000L), waits)   // Backoff je Datei neu
    }

    @Test fun nonRetryableErrorsFailImmediately() = runBlocking {
        var tries = 0
        val s = runQ(listOf("a"), { _, _ -> tries++; throw http(400) })
        assertEquals(1, tries)
        assertEquals(listOf(0), s.failed)
        assertTrue(uploadRetryable(http(429)))
        assertTrue(uploadRetryable(http(500)))
        assertTrue(uploadRetryable(http(408)))
        assertTrue(uploadRetryable(IOException()))
        assertFalse(uploadRetryable(http(413)))
        assertFalse(uploadRetryable(http(404)))
        assertFalse(uploadRetryable(IllegalArgumentException()))
    }

    @Test fun filesAddedDuringTheRunAreProcessedAfterwards() = runBlocking {
        val items = mutableListOf("a", "b")
        val done = mutableListOf<String>()
        val s = runUploadQueue(size = { items.size }, itemAt = { items[it] }, upload = { _, f -> done += f; if (f == "a") items += "c" })
        assertEquals(listOf("a", "b", "c"), done)
        assertEquals(3, s.total)
    }

    @Test fun cancellationEndsTheRun() {
        var ended = false
        try {
            runBlocking {
                runUploadQueue(size = { 3 }, itemAt = { it }, upload = { i, _ -> if (i == 1) throw kotlinx.coroutines.CancellationException("x") })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            ended = true
        }
        assertTrue(ended)
    }

    @Test fun progressTextAndServerMessagesAreShownInPlainLanguage() {
        assertEquals("3 von 10", uploadProgressText(3, 10))
        assertEquals("Datei X ist größer als 40 MB.", uploadFailureText("Datei X ist größer als 40 MB.", 413, false))   // Servermeldung im Klartext
        assertEquals("Bitte weniger Dateien auf einmal.", uploadFailureText("Bitte weniger Dateien auf einmal.", 400, false))
        assertFalse(uploadFailureText(null, 413, false).contains("30"))                                                    // nie die feste Zahl
        assertEquals("Kein Netz.", uploadFailureText(null, null, true))
    }

    @Test fun photosAreScaledToTheLongestEdgeAndDocumentsAreUntouched() {
        assertEquals(2560 to 1920, scaledSize(4000, 3000))      // typisches 12-MP-Foto
        assertEquals(1920 to 2560, scaledSize(3000, 4000))      // Hochformat
        assertEquals(1000 to 800, scaledSize(1000, 800))        // nie vergroessern
        assertEquals(UPLOAD_MAX_EDGE, maxOf(scaledSize(8000, 6000).first, scaledSize(8000, 6000).second))
        // 8-12 MB-Foto wird neu kodiert, kleines Bild und GIF nicht, Dokumente nie
        assertTrue(needsImageCompression("image/jpeg", 10_000_000, 4000, 3000))
        assertTrue(needsImageCompression("image/heic", 6_000_000, 4000, 3000))
        assertTrue(needsImageCompression("image/jpeg", 900_000, 3200, 2400))     // Kante zu lang
        assertFalse(needsImageCompression("image/jpeg", 900_000, 1600, 1200))
        assertFalse(needsImageCompression("image/gif", 20_000_000, 4000, 3000))
        assertFalse(needsImageCompression("application/pdf", 20_000_000, 0, 0))
        // JPEG ~85 bei 2560 px liegt typischerweise bei 1-2 MB: Faustregel ~0,35 Byte je Pixel ergibt rund 1,7 MB
        val (w, h) = scaledSize(4000, 3000)
        assertTrue(w.toLong() * h * 0.35 < 2_000_000)
    }
}
