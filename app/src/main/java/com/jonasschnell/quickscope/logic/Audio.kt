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
