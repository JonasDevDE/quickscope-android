package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.data.Plan
import com.jonasschnell.quickscope.data.SessionLesson
import com.jonasschnell.quickscope.logic.AudiobookQueue
import com.jonasschnell.quickscope.data.AudiobookItem
import com.jonasschnell.quickscope.logic.Task
import com.jonasschnell.quickscope.logic.activeKeyPoint
import com.jonasschnell.quickscope.logic.chapterLabel
import com.jonasschnell.quickscope.logic.heardFraction
import com.jonasschnell.quickscope.logic.lessonInsertIndex
import com.jonasschnell.quickscope.logic.planLine
import com.jonasschnell.quickscope.logic.showMnemonic
import com.jonasschnell.quickscope.logic.teachItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookTest {
    @Test fun keyPointsAreHighlightedEvenlyOverTheLesson() {
        assertEquals(-1, activeKeyPoint(0, 1000, 60_000))
        assertEquals(-1, activeKeyPoint(4, 1000, 0))
        assertEquals(0, activeKeyPoint(4, 0, 60_000))
        assertEquals(0, activeKeyPoint(4, 14_999, 60_000))
        assertEquals(1, activeKeyPoint(4, 15_000, 60_000))
        assertEquals(2, activeKeyPoint(4, 40_000, 60_000))
        assertEquals(3, activeKeyPoint(4, 59_999, 60_000))
        assertEquals(3, activeKeyPoint(4, 90_000, 60_000)) // nach dem Ende bleibt das letzte
        assertEquals(0, activeKeyPoint(4, -5, 60_000))
    }

    @Test fun heardFractionAndMnemonicTiming() {
        assertEquals(1.0, heardFraction(10, 100, finished = true), 0.0)
        assertEquals(0.5, heardFraction(500, 1000, finished = false), 0.0001)
        assertEquals(1.0, heardFraction(5000, 1000, finished = false), 0.0)
        assertEquals(0.0, heardFraction(500, 0, finished = false), 0.0)
        assertFalse(showMnemonic(80_000, 100_000))
        assertTrue(showMnemonic(90_000, 100_000))
        assertFalse(showMnemonic(1, 0))
        assertEquals("Kapitel 2 von 6", chapterLabel(2, 6))
        assertEquals("", chapterLabel(0, 6))
    }

    @Test fun queueStaysInBoundsAndSkipsAhead() {
        val items = (1..5).map { AudiobookItem("l$it", "c1", "Kapitel", "T$it", 40) }
        val q = AudiobookQueue(items)
        assertFalse(q.previous())
        assertEquals(listOf("l1", "l2", "l3"), q.ahead())
        q.jumpTo(3)
        assertEquals(listOf("l4", "l5"), q.ahead())
        assertEquals(80, q.remainingSeconds())
        q.jumpTo(99)
        assertTrue(q.finished)
        assertNull(q.current)
    }

    @Test fun planLineIsShort() {
        assertNull(planLine(null))
        assertNull(planLine(Plan(emptyList(), 0, 0)))
        assertEquals("Heute: Rechnen", planLine(Plan(listOf("Rechnen"), 8, 3)))
        assertEquals("Heute: A, B und 1 weitere", planLine(Plan(listOf("A", "B", "C"), 8, 3)))
        assertEquals("3 von 8 Kapiteln gehört", planLine(Plan(emptyList(), 8, 3)))
    }

    @Test fun lessonIsInsertedRightAfterTheCurrentTaskOnlyOnce() {
        val lesson = SessionLesson("L1", "Titel", listOf("a"), "text", null, 40)
        val teach = Task.Teach(teachItem(lesson), lesson)
        val tasks = listOf<Task>(Task.Card(com.jonasschnell.quickscope.data.Item(id = "q1"), "x"), Task.Card(com.jonasschnell.quickscope.data.Item(id = "q2"), "y"))
        assertEquals(1, lessonInsertIndex(0, tasks, "L1"))
        assertNull(lessonInsertIndex(0, tasks + teach, "L1")) // schon in der Runde
        assertEquals(2, lessonInsertIndex(1, tasks + teach, "L2"))
    }
}
