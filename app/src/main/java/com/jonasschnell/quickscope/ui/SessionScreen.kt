package com.jonasschnell.quickscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import com.jonasschnell.quickscope.logic.Celebration
import com.jonasschnell.quickscope.logic.Tones
import com.jonasschnell.quickscope.logic.PlayTap
import com.jonasschnell.quickscope.logic.mayAutoplay
import com.jonasschnell.quickscope.logic.playTap
import com.jonasschnell.quickscope.logic.shouldSpeakQuestion
import com.jonasschnell.quickscope.logic.soundFor
import com.jonasschnell.quickscope.logic.floor5
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonasschnell.quickscope.SessionPhase
import com.jonasschnell.quickscope.SessionUi
import com.jonasschnell.quickscope.SessionViewModel
import com.jonasschnell.quickscope.logic.Task
import com.jonasschnell.quickscope.logic.beforeAfterLine
import com.jonasschnell.quickscope.logic.lessonSteps
import com.jonasschnell.quickscope.logic.sessionProgress
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Black
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Danger
import com.jonasschnell.quickscope.ui.theme.Muted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lernrunde im Vollbild: eine Aufgabe pro Bildschirm, Zeitbalken oben, Abbrechen jederzeit. */
@Composable
fun SessionScreen(vm: SessionViewModel, onRetry: () -> Unit, onMore: () -> Unit, onClose: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Black)) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        when (s.phase) {
            SessionPhase.Running -> if (s.blocked) BlockedView(onRetry = vm::retryGate, onSilent = vm::continueSilent, onClose = onClose) else RunningView(s, vm, onClose)
            SessionPhase.Done -> DoneView(s, onClose)
            SessionPhase.Loading, SessionPhase.Closed -> if (s.blocked) BlockedView(onRetry = vm::retryGate, onSilent = vm::continueSilent, onClose = onClose) else {
                TopRow(0f, 0, null, onClose)
                Text("Gleich geht's los", color = Muted, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonCard(80.dp)
                    SkeletonCard(56.dp)
                    SkeletonCard(56.dp)
                    SkeletonCard(56.dp)
                }
            }
            SessionPhase.Empty ->
                if (s.reason.contains("Alles gelernt")) MessageView("Alles gelernt", s.reason, "Fertig", onClose, null, null) // offenes Lernen: ruhig, dann schliessen
                else MessageView(if (s.reason.contains("Tagesziel")) "Tagesziel geschafft" else "Gerade nichts zu lernen", s.reason.ifBlank { "Komm später wieder." }, "Noch eine Runde", onMore, "Fertig", onClose)
            SessionPhase.Error -> MessageView("Das hat nicht geklappt", s.message ?: "Unbekannter Fehler", "Nochmal", onRetry, "Schließen", onClose)
        }
    }
    // Vollflaechiger Tint ueber allem (auch Status- und Navigationsleiste), klickdurchlaessig, ohne Layout-Einfluss
    if (s.phase == SessionPhase.Running && s.feedback != null) s.fx?.let { FullTint(it) }
    }
}

@Composable
private fun TopRow(progress: Float, streak: Int, play: (@Composable () -> Unit)?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("✕", style = MaterialTheme.typography.titleLarge, color = Muted, modifier = Modifier.hapticClickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 4.dp))
        GlowProgress(progress, Modifier.weight(1f))
        StreakChip(streak)
        play?.invoke()
    }
}

/** Ruhiger Zwischenbildschirm, wenn das Audio nicht kommt: keine halbe Uebung, die Runde bleibt erhalten. */
@Composable
private fun BlockedView(onRetry: () -> Unit, onSilent: () -> Unit, onClose: () -> Unit) {
    TopRow(0f, 0, null, onClose)
    MessageView("Gerade nicht möglich", "Bitte gleich nochmal.", "Nochmal", onRetry, "Ohne Ton lernen", onSilent)
}

@Composable
private fun MessageView(title: String, text: String, primary: String, onPrimary: () -> Unit, secondary: String?, onSecondary: (() -> Unit)?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LearnMark(56.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        PrimaryButton(primary, onPrimary)
        if (secondary != null && onSecondary != null) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(secondary, onSecondary)
        }
    }
}

@Composable
private fun ColumnScope.RunningView(s: SessionUi, vm: SessionViewModel, onClose: () -> Unit) {
    val task = s.current ?: return
    val voice = rememberVoice(onBackground = vm::suppressAutoplay)
    val haptics = LocalHaptics.current
    val sfx = rememberSfx()
    val shake = remember { Animatable(0f) }
    val ids = remember(s.tasks) { s.tasks.map { it.item.id } }

    // Audio-Modus: naechste Aufgaben vorab laden
    LaunchedEffect(s.index) {
        voice.prefetch(s.examId, ids, s.index)
    }
    // Audio-Modus: nur die Frage vorlesen (nie Antwort oder Rueckmeldung)
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.index, s.feedback != null, voice, retry) {
        if (task is Task.Teach) return@LaunchedEffect // Lektionskarte spielt sich selbst ab
        if (shouldSpeakQuestion(!s.silent && !s.waiting, s.feedback, task is Task.Teach) && mayAutoplay(s.autoplaySuppressedAt, s.index)) voice.speak(s.examId, task.item.id, task.item.question)
        else voice.stop()
    }
    // Rueckmeldung: Ton und Haptik; richtig deutlich groesser als falsch
    LaunchedEffect(s.fx?.id) {
        val o = s.fx?.outcome ?: return@LaunchedEffect
        if (o.correct) {
            soundFor(o)?.let(sfx::play)
            when (o.celebration) {
                Celebration.Small -> haptics.celebrate(0)
                Celebration.Medium -> haptics.celebrate(1)
                Celebration.Big -> haptics.celebrate(2)
            }
        } else {
            // falsch: kein Ton, nur ein leichtes Tick und das sanfte Wackeln
            haptics.tick()
            shake.animateTo(0f, keyframes {
                durationMillis = 340
                0f at 0; -14f at 60; 12f at 120; -8f at 190; 5f at 250; 0f at 340
            })
        }
    }
    LaunchedEffect(s.sits?.id) {
        if (s.sits != null) { sfx.play(Tones.medium); haptics.celebrate(1) }
    }
    // automatisch weiter nach kurzer Zeit (auch per "Weiter"); Karten brauchen keine Lesezeit
    LaunchedEffect(s.feedback, s.index) {
        val fb = s.feedback ?: return@LaunchedEffect
        delay(
            when {
                task is Task.Card -> if (fb) 1000L else 1500L
                s.neutral -> 2200L
                lessonSteps(task.item).isNotEmpty() -> 4500L
                fb -> 1400L
                else -> 2200L
            },
        )
        vm.next()
    }

    val shownItems = s.tasks.filterIndexed { i, _ -> !s.isRetry.getOrElse(i) { false } }.map { it.item }
    val done = (0 until s.index).count { !s.isRetry.getOrElse(it) { false } }
    TopRow(sessionProgress(shownItems, done), s.streak, if (s.silent) null else ({
        PlayPauseButton(voice.playState, {
            // Pause gilt nur fuer die aktuelle Karte; die naechste Karte startet von selbst
            when (playTap(voice.playState)) {
                PlayTap.Pause -> voice.pause()
                PlayTap.Resume -> voice.resume()
                PlayTap.Restart -> { vm.allowAutoplay(); retry++ }
            }
        })
    }), onClose)
    run {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        }
    }
    if (s.inFinalRound) Text("Abschlussrunde", color = Accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { translationX = shake.value }.verticalScroll(rememberScrollState()).padding(top = 16.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            key(s.index) {
                s.topics[task.item.topicId]?.let {
                    Text(it, color = Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                CompositionLocalProvider(LocalHighlight provides (voice?.highlight ?: Highlight())) {
                    TaskBody(task, s.feedback, s.neutral, s.examId, voice, retry, mayAutoplay(s.autoplaySuppressedAt, s.index), s.silent, vm)
                }
            }
        }
        s.fx?.takeIf { s.feedback != null }?.let { fx -> if (fx.outcome.correct) CorrectFx(fx) }
        s.sits?.let { SitsBanner(it, Modifier.align(Alignment.TopCenter)) }
    }
    // Feste Position unten: vor der Antwort "Weiss nicht" (klein, unauffaellig), danach "Weiter"; die Hoehe bleibt gleich
    if (task is Task.Choice || task is Task.Cloze || task is Task.Spot || task is Task.Match || task is Task.Order) {
        Box(Modifier.fillMaxWidth().height(76.dp).padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
            if (s.feedback == null) {
                HapticTextButton(onClick = vm::dontKnow) { Text("Weiß nicht", color = Muted, style = MaterialTheme.typography.labelLarge) }
            } else {
                PrimaryButton("Weiter", vm::next)
            }
        }
    }
}

/** Die Aufgabe je Typ. Lektionskarten und Loesungswege haben ihren eigenen "Weiter"-Knopf. */
@Composable
private fun TaskBody(task: Task, feedback: Boolean?, neutral: Boolean, examId: String, voice: Voice, replayKey: Int, autoplay: Boolean, silent: Boolean, vm: SessionViewModel) {
    val answered = feedback != null
    val revealed = neutral
    val onAnswer: (Boolean) -> Unit = { ok -> vm.answer(ok) }
    when (task) {
        is Task.Card -> CardTask(task, onAnswer)
        is Task.Lesson -> LessonTask(task) { vm.answer(true); vm.next() }
        is Task.Teach -> TeachCard(task, examId, voice, replayKey, autoplay, silent, onHeard = vm::heard, onNext = vm::next)
        is Task.Choice -> ChoiceTask(task, answered, revealed, onAnswer)
        is Task.Cloze -> ClozeTask(task, revealed, onAnswer)
        is Task.Spot -> SpotTask(task, revealed, onAnswer)
        is Task.Match -> MatchTask(task, revealed, onAnswer)
        is Task.Order -> OrderTask(task, revealed, onAnswer)
    }
}

/** Zaehlt einen Wert beim Erscheinen von 0 hoch. */
@Composable
private fun countUp(target: Int): Int {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val v by animateIntAsState(if (started) target else 0, tween(1100), label = "countUp")
    return v
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(CardHigh).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = Accent)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
    }
}

/** Feier-Bildschirm: Werte zaehlen hoch, Konfetti, Abschlussklang; Prognose nur, wenn sie sich geaendert hat. */
@Composable
private fun DoneView(s: SessionUi, onClose: () -> Unit) {
    val sfx = rememberSfx()
    val haptics = LocalHaptics.current
    LaunchedEffect(Unit) {
        sfx.play(Tones.done)
        haptics.celebrate(3)
    }
    val correct = countUp(s.correctCount)
    val xp = countUp(s.xp)
    val best = countUp(s.bestStreak)
    val seconds = countUp(s.elapsedSeconds)
    Box(Modifier.fillMaxSize()) {
        Confetti(Unit)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            LearnMark(56.dp)
            Spacer(Modifier.height(16.dp))
            Text("Heute geschafft", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCell("$correct / ${s.answerCount}", "richtig", Modifier.weight(1f))
                StatCell("%d:%02d".format(seconds / 60, seconds % 60), "Zeit", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCell("$best", "Serie", Modifier.weight(1f))
                StatCell("+$xp", "Punkte", Modifier.weight(1f))
            }
            val before = s.scoreBefore
            val after = s.scoreAfter
            if (after != null && (before == null || floor5(before) != floor5(after))) {
                Spacer(Modifier.height(16.dp))
                Text(beforeAfterLine(before ?: after, after), style = MaterialTheme.typography.titleMedium, color = Accent)
            }
            if (s.dayStreak >= 2) {
                Spacer(Modifier.height(8.dp))
                Text("${s.dayStreak} Tage in Folge", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(32.dp))
            PrimaryButton("Fertig", onClose)
        }
    }
}
