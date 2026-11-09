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
fun TeachCard(task: Task.Teach, examId: String, voice: Voice, onHeard: (String, Double) -> Unit, onNext: () -> Unit) {
    val lesson = task.lesson
    val durationMs = lesson.estSeconds * 1000L
    var muted by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(lesson.id, muted, replay) {
        if (muted) {
            voice.stop()
            return@LaunchedEffect
        }
        finished = false
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
                voice.speakLesson(examId, lesson.id, lesson.ttsText)
                finished = true
            } finally {
                job.cancel()
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(lesson.title, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        val active = if (finished) lesson.keyPoints.size - 1 else activeKeyPoint(lesson.keyPoints.size, elapsed, durationMs)
        lesson.keyPoints.forEachIndexed { i, point ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("•", color = if (i == active && !muted) Accent else Muted, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
                Text(
                    point, style = MaterialTheme.typography.titleMedium,
                    color = if (i == active && !muted) Accent else Color.White.copy(alpha = 0.75f),
                )
            }
        }
        // Eselsbruecke erst gegen Ende; der Platz ist immer reserviert, damit nichts springt
        Text(
            if (finished || showMnemonic(elapsed, durationMs)) lesson.mnemonic.orEmpty() else "", color = Accent, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().height(24.dp),
            maxLines = 1,
        )
        if (voice.usingDevice && !muted) Text("Gerätestimme", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (muted || finished) Pill("Anhören", false, { muted = false; replay++ }) else Pill("Stumm", false, { muted = true })
        }
        PrimaryButton("Weiter zu den Fragen", {
            voice.stop()
            // Ueberspringen zaehlt nicht als gehoert: nur melden, wenn wirklich etwas gehoert wurde
            val fraction = heardFraction(elapsed, durationMs, finished)
            if (fraction > 0.05) onHeard(lesson.id, fraction)
            onNext()
        })
    }
}
