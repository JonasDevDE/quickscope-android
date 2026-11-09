package com.jonasschnell.quickscope.logic

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Alle Intensitaets-Konstanten fuer Feedback und Feiern an einer Stelle. */
object FeedbackTuning {
    const val XP_BASE = 10
    /** Zusatz-XP je Schwierigkeitsstufe ueber 3. */
    const val XP_PER_EXTRA_DIFFICULTY = 3
    /** Alle so viele richtige in Folge gibt es Serienbonus-XP (und eine Mini-Feier). */
    const val MEDIUM_EVERY = 5
    const val BIG_EVERY = 10
    const val XP_STREAK_BONUS = 5
    const val XP_STREAK_CAP = 15
    /** Serienzaehler erst ab so vielen in Folge zeigen. */
    const val STREAK_VISIBLE_FROM = 2

    /** "Gleich nochmal": falsch beantwortete Aufgaben kommen nach 3 bis 5 Aufgaben wieder. */
    const val RETRY_MIN_GAP = 3
    const val RETRY_MAX_GAP = 5

    /** Ab dieser Kenntnis (Themen-Mastery) "sitzt" ein Thema. */
    const val SITS_THRESHOLD = 0.8

    /** Partikel je Feier. */
    const val BURST_SMALL = 14
    const val BURST_MEDIUM = 26
    const val BURST_BIG = 40
    const val CONFETTI = 60

    /** Toene: kurz und leise (Anteil der Vollaussteuerung). */
    const val SFX_VOLUME = 0.22
    const val SAMPLE_RATE = 22050
}

enum class Celebration { Small, Medium, Big }

/** Welche Feier bei welcher Serie: alle 10 gross, alle 5 mittel, sonst klein. */
fun celebrationFor(streak: Int): Celebration = when {
    streak > 0 && streak % FeedbackTuning.BIG_EVERY == 0 -> Celebration.Big
    streak > 0 && streak % FeedbackTuning.MEDIUM_EVERY == 0 -> Celebration.Medium
    else -> Celebration.Small
}

/** XP einer richtigen Antwort: Grundwert, mehr bei schwierigen Aufgaben (Stufe 4/5) und bei Serie (je 5 in Folge). */
fun xpFor(difficulty: Int, streakAfter: Int): Int {
    val diff = max(0, difficulty - 3) * FeedbackTuning.XP_PER_EXTRA_DIFFICULTY
    val streak = min(streakAfter / FeedbackTuning.MEDIUM_EVERY * FeedbackTuning.XP_STREAK_BONUS, FeedbackTuning.XP_STREAK_CAP)
    return FeedbackTuning.XP_BASE + diff + streak
}

/** Ergebnis einer Antwort fuer Anzeige, Ton und Haptik. */
data class Outcome(
    val correct: Boolean,
    val retry: Boolean,
    val streak: Int,
    val bestStreak: Int,
    val xp: Int,
    val celebration: Celebration,
    /** Erste richtige Antwort nach einem Fehler. */
    val comeback: Boolean,
)

/** Zaehlt Serie, Bestserie, XP und Treffer einer Runde. Wiederholungen ("Gleich nochmal") geben keine XP. */
class FeedbackTracker {
    var streak = 0
        private set
    var best = 0
        private set
    var xp = 0
        private set
    var correct = 0
        private set
    var answers = 0
        private set
    private var lastWasWrong = false

    fun record(isCorrect: Boolean, difficulty: Int, retry: Boolean = false): Outcome {
        if (!retry) answers++
        if (!isCorrect) {
            streak = 0 // leise zurueck, ohne Aufhebens
            lastWasWrong = true
            return Outcome(false, retry, 0, best, 0, Celebration.Small, false)
        }
        if (!retry) correct++
        streak++
        best = max(best, streak)
        val gained = if (retry) 0 else xpFor(difficulty, streak)
        xp += gained
        val comeback = lastWasWrong
        lastWasWrong = false
        return Outcome(true, retry, streak, best, gained, celebrationFor(streak), comeback)
    }
}

/** Einfuegeposition fuer eine falsch beantwortete Aufgabe: nach 3 bis 5 weiteren Aufgaben, hoechstens ans Ende. */
fun retryInsertIndex(current: Int, size: Int, key: String): Int {
    val span = FeedbackTuning.RETRY_MAX_GAP - FeedbackTuning.RETRY_MIN_GAP + 1
    val gap = FeedbackTuning.RETRY_MIN_GAP + abs(key.hashCode() % span)
    return min(current + 1 + gap, size)
}

/** Themen, die zwischen zwei Staenden ueber die Schwelle gekommen sind (vorher darunter, jetzt darueber). */
fun newlySitting(prev: Map<String, Double>, now: Map<String, Double>, threshold: Double = FeedbackTuning.SITS_THRESHOLD): List<String> =
    now.filter { (id, m) -> m >= threshold && (prev[id] ?: 0.0) < threshold }.keys.toList()

/** Neue Tagesserie, wenn heute eine Runde abgeschlossen wurde. */
fun nextDayStreak(last: LocalDate?, streak: Int, today: LocalDate): Int = when {
    last == null -> 1
    last == today -> max(1, streak)
    ChronoUnit.DAYS.between(last, today) == 1L -> streak + 1
    else -> 1
}

/** Anzuzeigende Tagesserie: nur wenn sie noch laeuft (zuletzt heute oder gestern) und positiv ist; sonst null (ausblenden). */
fun visibleDayStreak(last: LocalDate?, streak: Int, today: LocalDate): Int? =
    if (last != null && streak > 0 && ChronoUnit.DAYS.between(last, today) in 0..1) streak else null

// ---------------------------------------------------------------- Toene (synthetisch, ohne Dateien)

/** Ein Ton: Frequenz in Hz, Dauer in ms, optional gleitend zu [glideTo]. */
data class Note(val hz: Double, val ms: Int, val glideTo: Double? = null)

object Tones {
    private const val C5 = 523.25
    private const val E5 = 659.25
    private const val G5 = 783.99
    private const val C6 = 1046.5
    private const val E6 = 1318.5
    private const val G6 = 1568.0

    /** Richtig: heller aufsteigender Dreiklang. */
    val correct = listOf(Note(C5, 70), Note(E5, 70), Note(G5, 150))
    /** 5 in Folge: hoeher und laenger. */
    val medium = listOf(Note(E5, 70), Note(G5, 70), Note(C6, 70), Note(E6, 190))
    /** 10 in Folge: noch hoeher, mit Schluss-Glanz. */
    val big = listOf(Note(G5, 70), Note(C6, 70), Note(E6, 70), Note(G6, 110), Note(E6, 60), Note(G6, 230))
    /** Falsch: weicher tiefer Ton, leicht abfallend. */
    val wrong = listOf(Note(220.0, 200, glideTo = 175.0))
    /** Abschlussklang: aufsteigende Tonleiter-Fanfare. */
    val done = listOf(Note(C5, 90), Note(E5, 90), Note(G5, 90), Note(C6, 110), Note(G5, 70), Note(C6, 120), Note(E6, 320))
}

/** Erzeugt 16-Bit-PCM (mono): Sinus mit kurzem Anschlag und weichem Abklingen, [volume] 0..1 der Vollaussteuerung. */
fun synth(notes: List<Note>, volume: Double = FeedbackTuning.SFX_VOLUME, sampleRate: Int = FeedbackTuning.SAMPLE_RATE): ShortArray {
    val total = notes.sumOf { it.ms * sampleRate / 1000 }
    val out = ShortArray(total)
    var pos = 0
    var phase = 0.0
    for (n in notes) {
        val len = n.ms * sampleRate / 1000
        val attack = max(1, sampleRate * 5 / 1000)
        for (i in 0 until len) {
            val t = i.toDouble() / len
            val hz = if (n.glideTo != null) n.hz + (n.glideTo - n.hz) * t else n.hz
            phase += 2 * PI * hz / sampleRate
            val env = min(1.0, i.toDouble() / attack) * exp(-3.0 * t) * (1 - t * t * t * t) // weich, ohne Klick am Ende
            out[pos++] = (sin(phase) * env * volume * Short.MAX_VALUE).toInt().toShort()
        }
    }
    return out
}
