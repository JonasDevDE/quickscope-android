package com.jonasschnell.quickscope.logic

/** Vorgelesen wird nur die Frage. */
enum class VoicePart(val query: String) { Question("question") }

/** Feste Wiedergabegeschwindigkeit der Stimme (Cloud-Stimme mit erhaltener Tonhoehe, Geraetestimme als Sprechrate). Nur hier festgelegt. */
const val SPEECH_SPEED = 1.15f

/**
 * Plant das Vorladen: bei jeder Aufgabe die aktuelle und die naechsten ([window] insgesamt) anfordern,
 * jede Aufgabe nur einmal. [reset] beim Start einer neuen Runde.
 */
class PrefetchPlanner(private val window: Int = 3) {
    private val requested = mutableSetOf<String>()

    /** Ids aus [ids] ab [index], die noch nicht angefordert wurden; sie gelten danach als angefordert. */
    fun next(ids: List<String>, index: Int): List<String> {
        val fresh = ids.drop(index.coerceAtLeast(0)).take(window).filter { requested.add(it) }
        return fresh
    }

    /** Fehlgeschlagene Anforderung zuruecknehmen, damit sie spaeter erneut versucht wird. */
    fun forget(id: String) {
        requested.remove(id)
    }

    fun reset() = requested.clear()
}

/** Zustand der einen Wiedergabe der App. */
enum class PlayState { Idle, Loading, Playing, Paused }

/**
 * Zentrale Zustandsmaschine des Vorlesens (idle, loading, playing, paused) mit Generationsnummer: [begin] startet eine neue
 * Wiedergabe und macht alle frueheren ungueltig, verspaetete Rueckmeldungen (prepared, completed) eines alten Elements
 * werden mit [isCurrent] verworfen. Es gibt dadurch nie zwei gleichzeitige Wiedergaben.
 */
class PlaybackMachine {
    var state = PlayState.Idle
        private set
    var generation = 0L
        private set

    /** Neue Wiedergabe: laedt, alles Vorherige ist ungueltig. */
    fun begin(): Long {
        generation++
        state = PlayState.Loading
        return generation
    }

    /** Gilt die Wiedergabe [gen] noch (nicht ersetzt, nicht gestoppt, nicht beendet)? */
    fun isCurrent(gen: Long): Boolean = gen == generation && state != PlayState.Idle

    /** Audio ist bereit und startet; false = veraltet, verwerfen. */
    fun playing(gen: Long): Boolean {
        if (gen != generation || state != PlayState.Loading) return false
        state = PlayState.Playing
        return true
    }

    fun pause(gen: Long) {
        if (gen == generation && state == PlayState.Playing) state = PlayState.Paused
    }

    fun resume(gen: Long) {
        if (gen == generation && state == PlayState.Paused) state = PlayState.Playing
    }

    /** Ende der Wiedergabe [gen]; false = veraltet (eine neuere laeuft). */
    fun finish(gen: Long): Boolean {
        if (gen != generation) return false
        state = PlayState.Idle
        return true
    }

    /** Alles stoppen (neue Karte, Stumm, Verlassen); spaete Rueckmeldungen werden damit ungueltig. */
    fun stop() {
        generation++
        state = PlayState.Idle
    }
}

/** Dateiname im Audio-Cache. */
fun audioFileName(examId: String, itemId: String, part: VoicePart) = "${examId}_${itemId}_${part.query}.wav".replace(Regex("[^A-Za-z0-9._-]"), "_")

/** Aelteste Dateien zuerst entfernen, bis die Summe unter [maxBytes] liegt. Liefert die Namen der zu loeschenden Dateien. */
fun filesToPrune(files: List<Pair<String, Pair<Long, Long>>>, maxBytes: Long): List<String> {
    // Eintrag: Name -> (Groesse, letzte Aenderung)
    var total = files.sumOf { it.second.first }
    val out = mutableListOf<String>()
    for ((name, meta) in files.sortedBy { it.second.second }) {
        if (total <= maxBytes) break
        out += name
        total -= meta.first
    }
    return out
}

/** So lange wird auf die Cloud-Stimme gewartet (Audio laedt noch), danach liest die Geraetestimme statt zu verwerfen. */
const val CLOUD_WAIT_MS = 15_000L

/**
 * Soll die Frage jetzt vorgelesen werden? Immer wenn ein Item sichtbar wird und der Ton an ist (auch das erste, auch nach
 * Drehen/App-Wechsel), solange noch nicht geantwortet wurde. Lektionskarten spielen sich selbst ab.
 */
fun shouldSpeakQuestion(audioOn: Boolean, feedback: Boolean?, isTeach: Boolean): Boolean = audioOn && feedback == null && !isTeach
/** Wie viele Aufgaben/Lektionen beim Rundenstart sofort vorgeladen werden. */
const val WARM_COUNT = 4

/** So lange wartet der Rundenstart ("Gleich geht's los") hoechstens auf das Audio der ersten Karte. */
const val FIRST_AUDIO_WAIT_MS = 6_000L

/** Ziel fuers Vorladen: eine Aufgabe (Frage-Audio) oder eine Lektion. */
data class WarmTarget(val id: String, val lesson: Boolean)

/** Reihenfolge des Vorladens beim Rundenstart: die ersten [count] Karten, die erste zuerst. */
fun warmPlan(tasks: List<Task>, count: Int = WARM_COUNT): List<WarmTarget> =
    tasks.take(count).map { t -> if (t is Task.Teach) WarmTarget(t.lesson.id, true) else WarmTarget(t.item.id, false) }

/** Wird beim Start schon vorgeladen und gewartet? Nur wenn vorgelesen wird: Ton an oder die erste Karte ist eine Lektion. */
fun audioNeededAtStart(audioOn: Boolean, tasks: List<Task>): Boolean = tasks.isNotEmpty() && (audioOn || tasks.first() is Task.Teach)

/** Darstellung des Play-/Pause-Knopfes: nur Play oder Pause (kein Ladesymbol; das Warten passiert vor dem Anzeigen der Karte). */
enum class PlayButtonKind { Play, Pause }

fun playButtonKind(state: PlayState): PlayButtonKind = if (state == PlayState.Playing) PlayButtonKind.Pause else PlayButtonKind.Play

/** Was ein Tipp auf den Knopf bewirkt. */
enum class PlayTap { Pause, Resume, Restart }

fun playTap(state: PlayState): PlayTap = when (state) {
    PlayState.Playing -> PlayTap.Pause
    PlayState.Paused -> PlayTap.Resume
    else -> PlayTap.Restart // fertig, gestoppt oder noch nicht gestartet: von vorn
}

/** Wartezeit vor dem naechsten Versuch: 1 s, 2 s, 4 s, 8 s ... hoechstens 60 s; ein Retry-After des Servers wird beachtet. */
fun retryDelayMs(attempt: Int, retryAfterMs: Long? = null): Long {
    val backoff = (1_000L shl attempt.coerceIn(0, 6)).coerceAtMost(60_000L)
    return maxOf(backoff, retryAfterMs ?: 0L).coerceAtMost(120_000L)
}

/** Retry-After-Header (Sekunden) in Millisekunden; null bei fehlendem oder unlesbarem Wert. */
fun parseRetryAfter(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)

/** Fehlerpolitik im Hoerbuch: leise erneut versuchen, nach 3 Fehlern in Folge die Lektion ruhig ueberspringen (spaeter wiederholen). */
sealed interface FailureAction {
    data class Retry(val delayMs: Long) : FailureAction
    data object Skip : FailureAction
}

const val AUDIOBOOK_MAX_FAILURES = 3

fun nextAfterFailure(failuresInRow: Int): FailureAction =
    if (failuresInRow >= AUDIOBOOK_MAX_FAILURES) FailureAction.Skip else FailureAction.Retry(retryDelayMs(failuresInRow - 1))

/** Hoechstens so viele Vorlade-Downloads gleichzeitig (die aktuelle Karte hat Vorrang und zaehlt nicht mit). */
const val PREFETCH_PARALLEL = 2

/** So lange wartet das Audio-Gate hoechstens (mit unsichtbaren Wiederholungen), dann erscheint der ruhige Fehlerbildschirm. */
const val GATE_TIMEOUT_MS = 45_000L

enum class GateResult { Ready, Wait, Timeout }

/**
 * Audio-Gate: eine Karte erscheint nur, wenn ihr Audio schon im lokalen Cache liegt. Ohne Audio-Pflicht (Ton aus fuer diese Runde)
 * ist sie sofort bereit; sonst wird gewartet, bis nach [timeoutMs] der Fehlerbildschirm kommt.
 */
fun gateDecision(audioRequired: Boolean, cached: Boolean, waitedMs: Long, timeoutMs: Long = GATE_TIMEOUT_MS): GateResult = when {
    !audioRequired || cached -> GateResult.Ready
    waitedMs >= timeoutMs -> GateResult.Timeout
    else -> GateResult.Wait
}

/** Ziel des Gates fuer eine Karte: Lektion oder Frage. */
fun gateTarget(task: Task): WarmTarget = if (task is Task.Teach) WarmTarget(task.lesson.id, true) else WarmTarget(task.item.id, false)

/** Wiedergabe der Runde nur im Vordergrund: Hintergrund (Home, App-Wechsel, Bildschirm aus) stoppt sofort. */
fun shouldStopForLifecycle(inForeground: Boolean, state: PlayState): Boolean = !inForeground && state != PlayState.Idle

/** Nach dem Stopp im Hintergrund startet die Runde nicht von selbst neu: Auto-Play erst wieder beim naechsten Item. */
fun mayAutoplay(suppressedAt: Int?, index: Int): Boolean = suppressedAt != index
