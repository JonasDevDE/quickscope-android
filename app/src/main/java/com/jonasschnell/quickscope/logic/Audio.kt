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

/** Wie lange der Stream hoechstens zum Starten braucht; danach bleibt diese Karte still und die naechste versucht es neu. */
const val STREAM_START_TIMEOUT_MS = 6_000L

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

/** Status-Antwort -> verfuegbar? Nur eine ausdrueckliche Antwort mit available=false heisst nein; fehlende Antwort (404, Fehler, Timeout) = unbekannt = versuchen. */
fun statusAvailable(status: com.jonasschnell.quickscope.data.AudioStatus?): Boolean = status?.available ?: true

/** Darf ueberhaupt eine Audio-Anfrage (Status, Stream, Wortzeiten) gestellt werden? Nur bei eingeschaltetem Vorlesen. */
fun mayRequestAudio(enabled: Boolean): Boolean = enabled

/** Beim naechsten Item still erneut pruefen, wenn Ton gewuenscht ist, der Modus still ist und die letzte Pruefung lange her ist. */
fun shouldRecheckAudio(mode: AudioMode, enabled: Boolean, lastCheckMs: Long, nowMs: Long): Boolean =
    enabled && mode == AudioMode.Silent && nowMs - lastCheckMs >= AUDIO_RECHECK_MS
