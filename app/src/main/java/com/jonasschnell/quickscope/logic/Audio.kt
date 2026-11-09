package com.jonasschnell.quickscope.logic

/** Vorgelesen wird nur die Frage. */
enum class VoicePart(val query: String) { Question("question") }

/** Feste Wiedergabegeschwindigkeit der Stimme (Cloud-Stimme mit erhaltener Tonhoehe, Geraetestimme als Sprechrate). Nur hier festgelegt. */
const val SPEECH_SPEED = 1.15f

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

/**
 * Soll die Frage jetzt vorgelesen werden? Immer wenn ein Item sichtbar wird und der Ton an ist (auch das erste, auch nach
 * Drehen/App-Wechsel), solange noch nicht geantwortet wurde. Lektionskarten spielen sich selbst ab.
 */
fun shouldSpeakQuestion(audioOn: Boolean, feedback: Boolean?, isTeach: Boolean): Boolean = audioOn && feedback == null && !isTeach
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

/** Wiedergabe der Runde nur im Vordergrund: Hintergrund (Home, App-Wechsel, Bildschirm aus) stoppt sofort. */
fun shouldStopForLifecycle(inForeground: Boolean, state: PlayState): Boolean = !inForeground && state != PlayState.Idle

/** Nach dem Stopp im Hintergrund startet die Runde nicht von selbst neu: Auto-Play erst wieder beim naechsten Item. */
fun mayAutoplay(suppressedAt: Int?, index: Int): Boolean = suppressedAt != index

/**
 * Wie lange auf den Start des Streams gewartet wird (das Backend erzeugt das Audio erst): Fragen 12 s, Lektionen 35 s. Kommt das Audio
 * in dieser Zeit, startet es von selbst, solange die Karte aktuell und die App im Vordergrund ist; danach bleibt nur diese Karte still.
 */
const val STREAM_START_QUESTION_MS = 12_000L
const val STREAM_START_LESSON_MS = 35_000L

/** Timeout fuer die Status-Abfrage (GET audio/status). */
const val AUDIO_STATUS_TIMEOUT_MS = 6_000L

fun streamStartTimeoutMs(lesson: Boolean): Long = if (lesson) STREAM_START_LESSON_MS else STREAM_START_QUESTION_MS

/** Was fuer die naechsten Karten schon angestossen wird (Backend-Cache warm halten). */
data class PrefetchTarget(val id: String, val lesson: Boolean)

/** Naechste 2 Fragen bzw. die naechste Lektion (Loesungswege haben kein Audio); nie die aktuelle Karte, hoechstens [count] Ziele. */
fun prefetchTargets(tasks: List<Task>, index: Int, count: Int = 2): List<PrefetchTarget> =
    tasks.drop(index + 1).take(count).mapNotNull { t ->
        when (t) {
            is Task.Teach -> PrefetchTarget(t.lesson.id, true)
            is Task.Lesson -> null
            else -> PrefetchTarget(t.item.id, false)
        }
    }.distinct()

/** Der Audio-Status wird beim Start geprueft und danach hoechstens alle 60 s erneut. */
const val AUDIO_RECHECK_MS = 60_000L

/** Modus der Runde: mit Ton (Streaming) oder still (ohne Meldung, ohne Dialog, ohne Audio-Symbole). */
enum class AudioMode { On, Silent }

/**
 * Preflight: "Vorlesen aus" -> still (ohne jeden Audio-Aufruf); offline oder ausdruecklich nicht verfuegbar -> still;
 * eingeschaltet und online -> Ton, ausser der Status sagt ausdruecklich "nicht verfuegbar".
 */
fun audioModeFor(enabled: Boolean, online: Boolean, available: Boolean?): AudioMode =
    if (enabled && online && available != false) AudioMode.On else AudioMode.Silent

/** Status-Antwort -> verfuegbar? Nur eine ausdrueckliche Antwort mit available=false UND reason heisst nein; fehlende Antwort (404, Fehler, Timeout) = unbekannt = versuchen. */
fun statusAvailable(status: com.jonasschnell.quickscope.data.AudioStatus?): Boolean =
    !(status != null && !status.available && !status.reason.isNullOrBlank())

/** Darf ueberhaupt eine Audio-Anfrage (Status, Stream, Wortzeiten) gestellt werden? Nur bei eingeschaltetem Vorlesen. */
fun mayRequestAudio(enabled: Boolean): Boolean = enabled

/** Beim naechsten Item still erneut pruefen, wenn Ton gewuenscht ist, der Modus still ist und die letzte Pruefung lange her ist. */
fun shouldRecheckAudio(mode: AudioMode, enabled: Boolean, lastCheckMs: Long, nowMs: Long): Boolean =
    enabled && mode == AudioMode.Silent && nowMs - lastCheckMs >= AUDIO_RECHECK_MS

/** So viele Karten in Folge ohne Erfolg, bis das Audio fuer die ganze Runde als ausgefallen gilt (dann verschwindet der Knopf). */
const val AUDIO_FAIL_LIMIT = 3

/** Nach dem Ausblenden fruehestens nach dieser Zeit wieder einblenden (Hysterese gegen Flackern). */
const val AUDIO_HIDE_HYSTERESIS_MS = 60_000L

/**
 * Sichtbarkeit des Play/Pause-Knopfs fuer die ganze Runde: nicht von kurzlebigen Zustaenden (laedt/gescheitert je Versuch) abhaengig.
 * Sichtbar, solange Audio nicht als ausgefallen gilt ([AUDIO_FAIL_LIMIT] Karten in Folge ohne Erfolg); nach dem Ausblenden kommt er
 * fruehestens nach [AUDIO_HIDE_HYSTERESIS_MS] und nur nach einem Erfolg wieder.
 */
class AudioHealth(private val limit: Int = AUDIO_FAIL_LIMIT, private val hysteresisMs: Long = AUDIO_HIDE_HYSTERESIS_MS) {
    private var failures = 0
    private var hiddenAt: Long? = null

    fun success(nowMs: Long) {
        failures = 0
        hiddenAt?.let { if (nowMs - it >= hysteresisMs) hiddenAt = null }
    }

    fun failure(nowMs: Long) {
        failures++
        if (failures >= limit && hiddenAt == null) hiddenAt = nowMs
    }

    fun reset() {
        failures = 0
        hiddenAt = null
    }

    val visible: Boolean get() = hiddenAt == null
}

/** Fehler-Meldung ans Backend: hoechstens 1 je 10 s und 20 je Tag. */
const val CLIENT_LOG_MIN_GAP_MS = 10_000L
const val CLIENT_LOG_MAX_PER_DAY = 20

fun clientLogAllowed(nowMs: Long, lastMs: Long, countToday: Int): Boolean = nowMs - lastMs >= CLIENT_LOG_MIN_GAP_MS && countToday < CLIENT_LOG_MAX_PER_DAY

/** Detailtext hoechstens 120 Zeichen, ohne Zeilenumbrueche. */
fun clientLogDetail(text: String): String = text.replace('\n', ' ').take(120)

/** Kurzer Fehlercode fuer Log und Meldung: http_503, player_error:1:-1004, timeout, header:<typ>. */
fun audioErrorCode(httpCode: Int? = null, playerWhat: Int? = null, playerExtra: Int? = null, timeout: Boolean = false, contentType: String? = null): String = when {
    httpCode != null && httpCode !in 200..299 -> "http_$httpCode"
    contentType != null && !contentType.startsWith("audio/") && contentType != "application/octet-stream" -> "header:" + contentType.take(40)
    playerWhat != null -> "player_error:$playerWhat:${playerExtra ?: 0}"
    timeout -> "timeout"
    else -> "error"
}
