package com.jonasschnell.quickscope.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.jonasschnell.quickscope.logic.LessonHeader
import com.jonasschnell.quickscope.ui.theme.CardHigh
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.logic.Task
import com.jonasschnell.quickscope.logic.activeKeyPoint
import com.jonasschnell.quickscope.logic.heardFraction
import com.jonasschnell.quickscope.logic.showMnemonic
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Muted
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * Lektionskarte der Lernrunde: Titel und Stichworte, die Lektion wird sofort abgespielt (Stumm-Knopf), ein Knopf "Weiter"
 * springt direkt zu den Fragen. Gemeldet wird, wie viel gehoert wurde.
 */
@Composable
fun TeachCard(task: Task.Teach, examId: String, voice: Voice, replayKey: Int, autoplay: Boolean, silent: Boolean, onHeard: (String, Double) -> Unit, onNext: () -> Unit, lessonsOnly: Boolean = false, header: LessonHeader? = null) {
    val lesson = task.lesson
    val durationMs = lesson.estSeconds * 1000L
    var replay by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(lesson.id, replay, replayKey, silent) {
        if (silent) return@LaunchedEffect // ohne Ton: nur lesen
        if (!autoplay && replayKey == 0 && replay == 0) return@LaunchedEffect // im Hintergrund gestoppt: erst Play startet wieder
        finished = false
        var played = false
        val t0 = SystemClock.elapsedRealtime()
        elapsed = 0
        coroutineScope {
            val job = launch {
                while (isActive) {
                    elapsed = SystemClock.elapsedRealtime() - t0
                    delay(250)
                }
            }
            try {
                finished = voice.speakLesson(examId, lesson.id) // nur wenn wirklich bis zum Ende gespielt wurde
                played = finished
            } finally {
                job.cancel()
            }
        }
        // "Verstehen": nach dem Ende des Streams von selbst zur naechsten Lektion
        if (com.jonasschnell.quickscope.logic.autoAdvance(lessonsOnly, played)) {
            onHeard(lesson.id, 1.0)
            onNext()
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Kopfzeile: Kapitel klein (bei Kapitelwechsel kurz hervorgehoben), Lektionstitel gross, darunter dezenter Fortschritt
        var highlightChapter by remember(lesson.id) { mutableStateOf(header?.newChapter == true) }
        LaunchedEffect(lesson.id) { if (highlightChapter) { delay(2_500); highlightChapter = false } }
        header?.chapterLine?.let {
            Text(
                it, color = if (highlightChapter) Accent else Muted, textAlign = TextAlign.Center, maxLines = 2,
                style = if (lessonsOnly) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            lesson.title,
            style = (if (lessonsOnly) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall).copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        header?.progress?.let { (cur, total) ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (total in 2..12) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(total) { i -> Box(Modifier.size(if (i + 1 == cur) 8.dp else 6.dp).clip(CircleShape).background(if (i + 1 == cur) Accent else CardHigh)) }
                }
                Text(header.progressText.orEmpty(), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        val active = if (finished) lesson.keyPoints.size - 1 else activeKeyPoint(lesson.keyPoints.size, elapsed, durationMs)
        lesson.keyPoints.forEachIndexed { i, point ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("•", color = if (i == active) Accent else Muted, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
                Text(
                    point, style = MaterialTheme.typography.titleMedium,
                    color = if (i == active) Accent else Color.White.copy(alpha = 0.75f),
                )
            }
        }
        // Eselsbruecke erst gegen Ende; der Platz ist immer reserviert, damit nichts springt
        Text(
            if (finished || showMnemonic(elapsed, durationMs)) lesson.mnemonic.orEmpty() else "", color = Accent, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().height(24.dp),
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton(if (lessonsOnly) "Weiter" else "Weiter zum Test", {
            voice.stop()
            // Ueberspringen zaehlt nicht als gehoert: nur melden, wenn wirklich etwas gehoert wurde
            val fraction = heardFraction(elapsed, durationMs, finished)
            if (fraction > 0.05) onHeard(lesson.id, fraction)
            onNext()
        })
    }
}
