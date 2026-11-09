package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.data.AttemptRequest
import com.jonasschnell.quickscope.data.Exam
import com.jonasschnell.quickscope.data.LocalData
import com.jonasschnell.quickscope.data.SessionEntry
import com.jonasschnell.quickscope.data.SessionLesson
import com.jonasschnell.quickscope.data.Item
import com.jonasschnell.quickscope.logic.AttemptQueue
import com.jonasschnell.quickscope.logic.NEEDS_INTERNET
import com.jonasschnell.quickscope.logic.QueueStorage
import com.jonasschnell.quickscope.logic.canListen
import com.jonasschnell.quickscope.logic.nextOfflineLesson
import com.jonasschnell.quickscope.logic.offlineRetryIndex
import com.jonasschnell.quickscope.logic.offlineRound
import com.jonasschnell.quickscope.logic.parseOfflinePack
import com.jonasschnell.quickscope.logic.OfflinePack
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTest {
    private class Mem : QueueStorage {
        val m = mutableMapOf<String, String>()
        override fun read(key: String) = m[key]
        override fun write(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    private fun q(id: String) = SessionEntry(item = Item(id = id, type = "card", estSeconds = 20))
    private fun l(id: String, s: Int = 60) = SessionEntry(lesson = SessionLesson(id, "T", listOf("a"), "x", null, s))

    @Test fun packParsingFollowsTheContract() {
        val json = """{"generatedAt":"2026-11-09T19:47:48Z","version":"abc123","exam":{"id":"e"},"queue":[{"id":"a","type":"card","estSeconds":8},{"type":"lesson","lesson":{"id":"L1","title":"T","estSeconds":40}},{"kaputt":true}],
            "reserveItems":[{"id":"r1","type":"card"}],"topics":[{"id":"t1","title":"X"}],"extra":1}"""
        val p = parseOfflinePack(json)!!
        assertEquals("abc123", p.version)
        assertEquals(listOf("a", "L1"), p.entries.map { it.item?.id ?: it.lesson?.id })
        assertEquals(1, p.reserve.size)
        assertEquals(listOf("t1"), p.topics.map { it.id })
        assertNull(parseOfflinePack("""{"items":[{"id":"x","type":"card"}]}"""))   // keine Alt-Pfade mehr
        assertNull(parseOfflinePack("kein json"))
    }

    @Test fun roundFollowsPackOrderSkipsDoneAndFillsFromReserve() {
        val pack = OfflinePack(listOf(q("a"), q("b"), l("L1", 60), q("c")), listOf(q("r1"), q("r2")))
        assertEquals(listOf("a", "b"), offlineRound(pack, emptySet(), 40).map { it.item!!.id })
        assertEquals(listOf("b", "L1", "c"), offlineRound(pack, setOf("q:a"), 60 + 20 + 20).map { it.item?.id ?: it.lesson!!.id })
        assertEquals(listOf("a", "b", "c"), offlineRound(pack, emptySet(), 10_000, questionsOnly = true).take(3).map { it.item!!.id })
        assertEquals(listOf("r1", "r2"), offlineRound(pack, setOf("q:a", "q:b", "l:L1", "q:c"), 10_000).map { it.item!!.id })
        assertTrue(offlineRound(pack, emptySet(), 0).isEmpty())
    }

    @Test fun wrongAnswerComesBackAfterFourToEightItems() {
        for (key in listOf("a", "bb", "ccc", "item-42", "zzz")) {
            val at = offlineRetryIndex(2, 100, key)
            assertTrue("$key -> $at", at - 2 - 1 in 4..8)
        }
        assertEquals(5, offlineRetryIndex(2, 5, "a"))   // nie hinter das Ende
    }

    @Test fun dontKnowPicksNextUnseenLesson() {
        val pack = OfflinePack(listOf(q("a"), l("L1"), l("L2")), listOf(l("L3")))
        assertEquals("L2", nextOfflineLesson(pack, setOf("L1"), emptySet())!!.id)
        assertEquals("L3", nextOfflineLesson(pack, setOf("L1"), setOf("l:L2"))!!.id)
        assertNull(nextOfflineLesson(pack, setOf("L1", "L2", "L3"), emptySet()))
    }

    @Test fun attemptsAreIdempotentByOfflineId() {
        val mem = Mem()
        val queue = AttemptQueue(mem)
        val a = AttemptRequest("i1", "correct", 900, "2026-11-09T10:00:00Z", clientId = "id-1")
        queue.add("e", a)
        queue.add("e", a)   // doppelt vorgemerkt: ignoriert
        queue.add("e", AttemptRequest("i2", "wrong", 500, null, clientId = "id-2"))
        assertEquals(listOf("id-1", "id-2"), queue.pending("e").map { it.clientId })
        // Senden scheitert (offline): nichts wird entfernt, derselbe Stapel mit denselben Ids geht spaeter erneut
        val first = queue.pending("e")
        val again = queue.pending("e")
        assertEquals(first, again)
        queue.removeSent("e", 2)   // erst nach Erfolg
        assertTrue(queue.pending("e").isEmpty())
    }

    @Test fun localStoreIsAtomicVersionedAndWipes() {
        val dir = Files.createTempDirectory("qs").toFile()
        val local = LocalData(java.io.File(dir, "offline"))
        assertNull(local.exams())
        local.saveExams(listOf(Exam(id = "e1", name = "IHK")))
        assertEquals("IHK", local.exams()!!.first().name)
        local.savePack("e1", """{"version":"v1","queue":[{"id":"a","type":"card"}]}""", 3, "\"v1\"")
        assertEquals("\"v1\"", local.packEtag("e1"))
        assertNotNull(local.pack("e1"))
        assertEquals(3, local.packDays("e1"))
        local.markDone("e1", "q:a"); local.markDone("e1", "q:b")
        assertEquals(setOf("q:a", "q:b"), local.doneKeys("e1"))
        local.clearDone("e1")
        assertTrue(local.doneKeys("e1").isEmpty())
        // fremde Version / kaputte Datei = nichts da
        java.io.File(dir, "offline/exams.json").writeText("""{"v":99,"d":[]}""")
        assertNull(local.exams())
        java.io.File(dir, "offline/pack_e1.json").writeText("kaputt")
        assertNull(local.pack("e1"))
        local.clear()
        assertNull(local.packDays("e1"))
        assertFalse(java.io.File(dir, "offline").exists())
    }

    @Test fun gatingTextAndListenAvailability() {
        assertEquals("Dafür brauchst du Internet.", NEEDS_INTERNET)
        assertTrue(canListen(online = true, audioEnabled = true, hasLessons = true))
        assertFalse(canListen(online = false, audioEnabled = true, hasLessons = true))
        assertFalse(canListen(true, audioEnabled = false, hasLessons = true))
        assertFalse(canListen(true, true, hasLessons = false))
    }
}
