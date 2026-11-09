package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.logic.CardFlow
import com.jonasschnell.quickscope.logic.shouldSpeakQuestion
import com.jonasschnell.quickscope.logic.SPEECH_SPEED
import com.jonasschnell.quickscope.logic.FailureAction as F
import com.jonasschnell.quickscope.logic.AudioMode
import com.jonasschnell.quickscope.logic.AUDIO_RECHECK_MS
import com.jonasschnell.quickscope.logic.STREAM_START_TIMEOUT_MS
import com.jonasschnell.quickscope.logic.audioModeFor
import com.jonasschnell.quickscope.logic.mayRequestAudio
import com.jonasschnell.quickscope.logic.shouldRecheckAudio
import com.jonasschnell.quickscope.logic.PlayButtonKind as K
import com.jonasschnell.quickscope.logic.PlayState as P
import com.jonasschnell.quickscope.logic.PlayTap as T
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTest {
    @Test fun speechSpeedIsFixed() {
        assertEquals(1.15f, SPEECH_SPEED, 0.0001f)
    }

    @Test fun cardFlowNeedsRevealBeforeAnswer() {
        val f = CardFlow()
        assertNull(f.answer(true))
        assertFalse(f.revealed)
        f.reveal()
        assertEquals("known", f.answer(true))
        assertEquals("unknown", f.answer(false))
    }

    @Test fun speechStartsWheneverAnItemAppearsWithSoundOn() {
        assertTrue(shouldSpeakQuestion(audioOn = true, feedback = null, isTeach = false))   // auch das erste Item
        assertFalse(shouldSpeakQuestion(audioOn = false, feedback = null, isTeach = false))
        assertFalse(shouldSpeakQuestion(audioOn = true, feedback = true, isTeach = false))   // schon beantwortet
        assertFalse(shouldSpeakQuestion(audioOn = true, feedback = null, isTeach = true))    // Lektion spielt sich selbst
    }

    @Test fun neverTwoPlaybacksAtOnceAndOldCallbacksAreDropped() {
        val m = com.jonasschnell.quickscope.logic.PlaybackMachine()
        val first = m.begin()
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Loading, m.state)
        assertTrue(m.playing(first))
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Playing, m.state)
        // neues Element startet: das alte ist sofort ungueltig, es gibt nur eine aktuelle Wiedergabe
        val second = m.begin()
        assertFalse(m.isCurrent(first))
        assertTrue(m.isCurrent(second))
        // verspaetetes "prepared" und "complete" des alten Elements werden verworfen
        assertFalse(m.playing(first))
        assertFalse(m.finish(first))
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Loading, m.state)
        assertTrue(m.playing(second))
        // zweimal "prepared" fuer dasselbe Element startet nicht doppelt
        assertFalse(m.playing(second))
        m.pause(second)
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Paused, m.state)
        m.resume(second)
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Playing, m.state)
        assertTrue(m.finish(second))
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Idle, m.state)
        assertFalse(m.isCurrent(second))
    }

    @Test fun stopInvalidatesEverythingInFlight() {
        val m = com.jonasschnell.quickscope.logic.PlaybackMachine()
        val g = m.begin()
        m.stop()                        // z. B. Item gewechselt oder Stumm waehrend das Audio noch laedt
        assertFalse(m.isCurrent(g))
        assertFalse(m.playing(g))       // das spaet fertige Audio startet nicht mehr
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Idle, m.state)
        // "Anhoeren" mehrfach schnell hintereinander: nur die letzte Wiedergabe zaehlt
        val a = m.begin(); val b = m.begin(); val c = m.begin()
        assertFalse(m.isCurrent(a)); assertFalse(m.isCurrent(b)); assertTrue(m.isCurrent(c))
        assertEquals(c, m.generation)
    }

    @Test fun backgroundStopsRoundPlaybackAndAutoplayRestsUntilNextItem() {
        // Vordergrund: nichts zu tun; Hintergrund: jede laufende, ladende oder pausierte Wiedergabe sofort stoppen
        assertFalse(com.jonasschnell.quickscope.logic.shouldStopForLifecycle(true, P.Playing))
        assertTrue(com.jonasschnell.quickscope.logic.shouldStopForLifecycle(false, P.Playing))
        assertTrue(com.jonasschnell.quickscope.logic.shouldStopForLifecycle(false, P.Loading))
        assertTrue(com.jonasschnell.quickscope.logic.shouldStopForLifecycle(false, P.Paused))
        assertFalse(com.jonasschnell.quickscope.logic.shouldStopForLifecycle(false, P.Idle))
        // nach dem Stopp: kein Auto-Play fuer dieses Item, aber wieder beim naechsten; Play-Knopf startet von vorn
        assertTrue(com.jonasschnell.quickscope.logic.mayAutoplay(null, 3))
        assertFalse(com.jonasschnell.quickscope.logic.mayAutoplay(3, 3))
        assertTrue(com.jonasschnell.quickscope.logic.mayAutoplay(3, 4))
        assertEquals(com.jonasschnell.quickscope.logic.PlayTap.Restart, com.jonasschnell.quickscope.logic.playTap(P.Idle))
        // die Maschine bleibt danach sauber: gestoppt = ungueltig, nichts laeuft weiter
        val m = com.jonasschnell.quickscope.logic.PlaybackMachine()
        val g = m.begin(); m.playing(g)
        m.stop()
        assertFalse(m.isCurrent(g))
        assertEquals(P.Idle, m.state)
    }

    @Test fun playButtonShowsOnlyPlayOrPause() {
        assertEquals(K.Pause, com.jonasschnell.quickscope.logic.playButtonKind(P.Playing))
        assertEquals(K.Play, com.jonasschnell.quickscope.logic.playButtonKind(P.Idle))
        assertEquals(K.Play, com.jonasschnell.quickscope.logic.playButtonKind(P.Paused))
        assertEquals(K.Play, com.jonasschnell.quickscope.logic.playButtonKind(P.Loading))
        assertEquals(2, K.entries.size) // kein Spinner
    }

    @Test fun tapPausesResumesOrRestarts() {
        assertEquals(T.Pause, com.jonasschnell.quickscope.logic.playTap(P.Playing))
        assertEquals(T.Resume, com.jonasschnell.quickscope.logic.playTap(P.Paused))
        assertEquals(T.Restart, com.jonasschnell.quickscope.logic.playTap(P.Idle))   // am Ende neu von vorn
        assertEquals(T.Restart, com.jonasschnell.quickscope.logic.playTap(P.Loading))
    }

    @Test fun backoffDoublesUpToSixtySecondsAndHonoursRetryAfter() {
        val seq = (0..9).map { com.jonasschnell.quickscope.logic.retryDelayMs(it) }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L, 60000L, 60000L), seq)
        // Retry-After des Servers wird beachtet (aber nie kuerzer als der Backoff)
        assertEquals(30_000L, com.jonasschnell.quickscope.logic.retryDelayMs(0, 30_000L))
        assertEquals(8_000L, com.jonasschnell.quickscope.logic.retryDelayMs(3, 2_000L))
        assertEquals(120_000L, com.jonasschnell.quickscope.logic.retryDelayMs(0, 600_000L)) // vernuenftige Obergrenze
        assertEquals(1000L, com.jonasschnell.quickscope.logic.retryDelayMs(-3))
        assertEquals(30_000L, com.jonasschnell.quickscope.logic.parseRetryAfter(" 30 "))
        assertNull(com.jonasschnell.quickscope.logic.parseRetryAfter(null))
        assertNull(com.jonasschnell.quickscope.logic.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(com.jonasschnell.quickscope.logic.parseRetryAfter("-5"))
    }

    @Test fun audiobookRetriesQuietlyThenSkipsAfterThreeFailures() {
        assertEquals(F.Retry(1000L), com.jonasschnell.quickscope.logic.nextAfterFailure(1))
        assertEquals(F.Retry(2000L), com.jonasschnell.quickscope.logic.nextAfterFailure(2))
        assertEquals(F.Skip, com.jonasschnell.quickscope.logic.nextAfterFailure(3))
        assertEquals(F.Skip, com.jonasschnell.quickscope.logic.nextAfterFailure(7))
    }

    @Test fun retryStopsWhenItemChangesOrAppGoesToBackground() {
        // Itemwechsel oder Hintergrund machen die laufende Wiedergabe ungueltig: die Schleife prueft isCurrent und endet
        val m = com.jonasschnell.quickscope.logic.PlaybackMachine()
        val g = m.begin()
        assertTrue(m.isCurrent(g))
        val next = m.begin()                       // naechste Karte
        assertFalse(m.isCurrent(g))
        assertTrue(m.isCurrent(next))
        m.stop()                                   // App im Hintergrund
        assertFalse(m.isCurrent(next))
        assertTrue(com.jonasschnell.quickscope.logic.shouldStopForLifecycle(false, P.Loading))
    }

    @Test fun preflightDecidesBetweenSoundAndSilent() {
        assertEquals(AudioMode.On, audioModeFor(enabled = true, online = true, available = true))
        assertEquals(AudioMode.Silent, audioModeFor(true, true, false))
        assertEquals(AudioMode.On, audioModeFor(true, true, null))         // Status unbekannt (z. B. 404): Stream wird versucht
        assertEquals(AudioMode.On, audioModeFor(true, true, com.jonasschnell.quickscope.logic.statusAvailable(null)))  // 404/Fehler -> Audiomodus
        assertEquals(AudioMode.Silent, audioModeFor(true, true, com.jonasschnell.quickscope.logic.statusAvailable(com.jonasschnell.quickscope.data.AudioStatus(available = false, reason = "all-models-blocked", retryAfterSeconds = 30))))
        assertEquals(AudioMode.On, audioModeFor(true, true, com.jonasschnell.quickscope.logic.statusAvailable(com.jonasschnell.quickscope.data.AudioStatus(available = false))))   // false ohne Grund schaltet nicht stumm
        assertEquals(AudioMode.Silent, audioModeFor(true, false, true))    // offline
        assertEquals(AudioMode.Silent, audioModeFor(false, true, true))    // Vorlesen aus
    }

    @Test fun noAudioRequestWhenReadAloudIsOff() {
        assertFalse(mayRequestAudio(false))
        assertTrue(mayRequestAudio(true))
        assertFalse(shouldRecheckAudio(AudioMode.Silent, false, 0, 10 * AUDIO_RECHECK_MS))
    }

    @Test fun silentRoundRetriesOnNextItemAfterInterval() {
        assertFalse(shouldRecheckAudio(AudioMode.Silent, true, 1_000, 1_000 + AUDIO_RECHECK_MS - 1))
        assertTrue(shouldRecheckAudio(AudioMode.Silent, true, 1_000, 1_000 + AUDIO_RECHECK_MS))
        assertFalse(shouldRecheckAudio(AudioMode.On, true, 0, 10 * AUDIO_RECHECK_MS))
        assertTrue(STREAM_START_TIMEOUT_MS in 1_000L..15_000L)
    }

    @Test fun understandIsVisibleAsSoonAsLessonsExist() {
        assertTrue(com.jonasschnell.quickscope.logic.understandVisible(true))
        assertFalse(com.jonasschnell.quickscope.logic.understandVisible(false))
        // Audio-Zustand spielt fuer die Sichtbarkeit keine Rolle: die Funktion kennt ihn gar nicht
    }

    @Test fun audioStartsLateOnlyWhenEnabledOnlineAndAvailable() {
        val f = { a: Boolean, b: Boolean, c: Boolean -> com.jonasschnell.quickscope.logic.audioAfterCheck(a, b, c) }
        assertTrue(f(true, true, true))
        assertFalse(f(true, true, false))
        assertFalse(f(false, true, true))   // Vorlesen aus
        assertFalse(f(true, false, true))   // offline: Lesemodus
    }

    @Test fun readingModeNeverAutoAdvancesWithoutFinishedAudio() {
        val adv = { a: Boolean, b: Boolean -> com.jonasschnell.quickscope.logic.autoAdvance(a, b) }
        assertTrue(adv(true, true))      // Stream zu Ende: naechste Lektion
        assertFalse(adv(true, false))    // ohne Ton: nur der Weiter-Knopf
        assertFalse(adv(false, true))    // normale Runde blaettert nie von selbst
    }

    @Test fun understandEntriesAndFallbackAndOfflineRound() {
        val l1 = com.jonasschnell.quickscope.data.SessionLesson("L1", "A", listOf("x"), "t", null, 30)
        val l2 = com.jonasschnell.quickscope.data.SessionLesson("L2", "B", listOf("y"), "t", null, 30)
        val card = com.jonasschnell.quickscope.data.SessionEntry(item = com.jonasschnell.quickscope.data.Item(id = "q1", type = "card"))
        val items = listOf(card, com.jonasschnell.quickscope.data.SessionEntry(lesson = l1), card, com.jonasschnell.quickscope.data.SessionEntry(lesson = l2))
        assertEquals(listOf("L1", "L2"), com.jonasschnell.quickscope.logic.understandEntries(items).map { it.lesson!!.id })
        val all = listOf(
            com.jonasschnell.quickscope.data.Lesson(id = "H", title = "gehoert", heard = true),
            com.jonasschnell.quickscope.data.Lesson(id = "N", title = "neu", heard = false),
        )
        assertEquals(listOf("N", "H"), com.jonasschnell.quickscope.logic.lessonEntriesFrom(all).map { it.lesson!!.id })
        val pack = com.jonasschnell.quickscope.logic.OfflinePack(items, emptyList())
        assertEquals(listOf("L1", "L2"), com.jonasschnell.quickscope.logic.offlineLessonRound(pack, emptySet()).map { it.lesson!!.id })
        assertEquals(listOf("L2"), com.jonasschnell.quickscope.logic.offlineLessonRound(pack, setOf("l:L1")).map { it.lesson!!.id })
        assertEquals(listOf("L1", "L2"), com.jonasschnell.quickscope.logic.offlineLessonRound(pack, setOf("l:L1", "l:L2")).map { it.lesson!!.id })   // alles gelesen: nochmal
    }
}
