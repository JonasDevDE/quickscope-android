package com.jonasschnell.quickscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import com.jonasschnell.quickscope.logic.SPEEDS
import com.jonasschnell.quickscope.logic.VoicePart
import com.jonasschnell.quickscope.logic.speedLabel
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonasschnell.quickscope.SessionPhase
import com.jonasschnell.quickscope.SessionUi
import com.jonasschnell.quickscope.SessionViewModel
import com.jonasschnell.quickscope.logic.Task
import com.jonasschnell.quickscope.logic.audioFeedback
import com.jonasschnell.quickscope.logic.audioPrompt
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
fun SessionScreen(vm: SessionViewModel, onRetry: () -> Unit, onClose: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        when (s.phase) {
            SessionPhase.Running -> RunningView(s, vm, onClose)
            SessionPhase.Done -> DoneView(s, onClose)
            SessionPhase.Loading, SessionPhase.Closed -> {
                TopRow(0f, false, null, onClose)
                Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonCard(80.dp)
                    SkeletonCard(56.dp)
                    SkeletonCard(56.dp)
                    SkeletonCard(56.dp)
                }
            }
            SessionPhase.Empty -> MessageView("Gerade nichts zu lernen", s.reason.ifBlank { "Komm später wieder." }, "Fertig", onClose, null, null)
            SessionPhase.Error -> MessageView("Das hat nicht geklappt", s.message ?: "Unbekannter Fehler", "Nochmal", onRetry, "Schließen", onClose)
        }
    }
}

@Composable
private fun TopRow(progress: Float, audio: Boolean, onAudio: ((Boolean) -> Unit)?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("✕", style = MaterialTheme.typography.titleLarge, color = Muted, modifier = Modifier.hapticClickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 4.dp))
        LinearProgressIndicator(progress = { progress }, color = Accent, trackColor = CardHigh, modifier = Modifier.weight(1f).height(6.dp))
        if (onAudio != null) Pill("Ton", audio, { onAudio(!audio) })
    }
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
    val voice = if (s.audio) rememberVoice(s.speed) else null
    val haptics = LocalHaptics.current
    val ids = remember(s.tasks) { s.tasks.map { it.item.id } }

    // Audio-Modus: naechste Aufgaben vorab laden
    LaunchedEffect(s.index, s.audio) {
        if (s.audio) voice?.prefetch(s.examId, ids, s.index)
    }
    // Audio-Modus: Frage vorlesen; sobald geantwortet ist, Antwort (mit Kurzerklaerung) vorlesen und danach weiter.
    LaunchedEffect(s.index, s.audio, s.feedback != null) {
        val fb = s.feedback
        if (voice == null) return@LaunchedEffect
        if (fb == null) {
            voice.speak(s.examId, task.item.id, VoicePart.Question, audioPrompt(task))
        } else {
            voice.speak(s.examId, task.item.id, VoicePart.Answer, audioFeedback(task, fb))
            delay(500)
            vm.next()
        }
    }
    // Feedback: Haptik, ohne Audio nach kurzer Zeit automatisch weiter
    LaunchedEffect(s.feedback, s.index) {
        val fb = s.feedback ?: return@LaunchedEffect
        if (fb) haptics.confirm() else haptics.reject()
        if (s.audio) return@LaunchedEffect
        delay(
            when {
                lessonSteps(task.item).isNotEmpty() -> 4500L
                fb && task.item.explanation.isNullOrBlank() && task.item.mnemonic.isNullOrBlank() -> 1000L
                fb -> 2400L
                else -> 3200L
            },
        )
        vm.next()
    }

    TopRow(sessionProgress(s.tasks.map { it.item }, s.index), s.audio, vm::setAudio, onClose)
    if (voice != null) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SPEEDS.forEach { sp -> Pill(speedLabel(sp), voice.speed == sp, { voice.selectSpeed(sp); vm.setSpeed(sp) }) }
            if (voice.loading) SkeletonBox(Modifier.size(10.dp), CircleShape)
            if (voice.usingDevice) Text("Gerätestimme", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (s.inFinalRound) Text("Abschlussrunde", color = Accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        key(s.index) {
            TaskBody(task, s.feedback, voice, s.examId, vm)
            s.feedback?.let { Spacer(Modifier.height(16.dp)); FeedbackPanel(task, it, vm::next) }
        }
    }
}

/** Die Aufgabe je Typ. Karten und Loesungswege gehen direkt weiter, alle anderen zeigen erst das Feedback. */
@Composable
private fun TaskBody(task: Task, feedback: Boolean?, voice: Voice?, examId: String, vm: SessionViewModel) {
    val scope = rememberCoroutineScope()
    val answered = feedback != null
    val onAnswer: (Boolean) -> Unit = { ok -> vm.answer(ok) }
    when (task) {
        is Task.Card -> CardTask(task, { ok -> vm.answer(ok); vm.next() }, { ans -> if (voice != null) scope.launch { voice.speak(examId, task.item.id, VoicePart.Answer, ans) } })
        is Task.Lesson -> LessonTask(task) { vm.answer(true); vm.next() }
        is Task.Choice -> ChoiceTask(task, answered, onAnswer)
        is Task.Cloze -> ClozeTask(task, onAnswer)
        is Task.Spot -> SpotTask(task, onAnswer)
        is Task.Match -> MatchTask(task, onAnswer)
        is Task.Order -> OrderTask(task, onAnswer)
    }
}

/** Ruhiger Abschluss: Heute geschafft, Prognose vorher/nachher, ein Satz zur Auswahl. */
@Composable
private fun DoneView(s: SessionUi, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LearnMark(56.dp)
        Spacer(Modifier.height(16.dp))
        Text("Heute geschafft", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        val before = s.scoreBefore
        val after = s.scoreAfter
        if (after != null) {
            Text(beforeAfterLine(before ?: after, after), style = MaterialTheme.typography.titleMedium, color = Accent)
            Text("Schätzung, keine Garantie", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (s.reason.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(s.reason, color = Muted, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(32.dp))
        PrimaryButton("Fertig", onClose)
    }
}
