package com.jonasschnell.quickscope.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jonasschnell.quickscope.UiState
import com.jonasschnell.quickscope.data.Exam
import com.jonasschnell.quickscope.data.Forecast
import com.jonasschnell.quickscope.logic.examStart
import com.jonasschnell.quickscope.logic.formatRemaining
import com.jonasschnell.quickscope.logic.isOver
import com.jonasschnell.quickscope.logic.masteryPercent
import com.jonasschnell.quickscope.logic.percentText
import com.jonasschnell.quickscope.logic.projectionLine
import com.jonasschnell.quickscope.logic.remainingMinutes
import com.jonasschnell.quickscope.logic.startLabel
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Danger
import com.jonasschnell.quickscope.ui.theme.Muted
import java.time.Instant

/** Die Startseite der aktiven Pruefung: EIN Bildschirm, ein grosser Knopf. */
@Composable
fun HomePage(
    state: UiState,
    loading: Boolean,
    onStart: (Exam, Int) -> Unit,
    onMaterial: (Exam) -> Unit,
    onCheatsheet: (Exam) -> Unit,
    onRetry: () -> Unit,
    onCancelUpload: () -> Unit,
    onRetryUpload: () -> Unit,
) {
    val exam = state.activeExam
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading || exam == null) {
            HomeSkeleton()
            return@Column
        }
        UploadCard(state, exam.id, onCancelUpload, onRetryUpload)
        when {
            isOver(exam, Instant.now()) -> ReviewView(exam, state.activeForecast)
            exam.status == "processing" -> ProcessingView()
            exam.status == "failed" -> FailedExamView(exam, onMaterial, onRetry)
            exam.status == "empty" -> EmptyExamView(exam, onMaterial)
            else -> ReadyView(exam, state.activeForecast, onStart, onCheatsheet)
        }
    }
}

@Composable
private fun HomeSkeleton() {
    SkeletonBox(Modifier.size(220.dp), androidx.compose.foundation.shape.CircleShape)
    SkeletonLine(180.dp)
    SkeletonCard(56.dp)
}

/** Fortschritt/Fehler eines laufenden Uploads, mit Abbrechen und Nochmal. */
@Composable
fun UploadCard(state: UiState, examId: String, onCancel: () -> Unit, onRetry: () -> Unit) {
    val up = state.upload?.takeIf { it.examId == examId && !it.done } ?: return
    CardColumn(verticalSpacing = 10.dp) {
        if (up.error != null) {
            Text(up.error, color = Danger, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Nochmal", true, onRetry)
                Pill("Verwerfen", false, onCancel)
            }
        } else {
            Text("Wird hochgeladen", style = MaterialTheme.typography.titleSmall)
            if (up.progress != null && up.progress > 0f) {
                LinearProgressIndicator(progress = { up.progress }, color = Accent, trackColor = CardHigh, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(color = Accent, trackColor = CardHigh, modifier = Modifier.fillMaxWidth())
            }
            Pill("Abbrechen", false, onCancel)
        }
    }
}

@Composable
private fun ProcessingView() {
    Text("Liest Material …", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
    SkeletonCard(140.dp)
    SkeletonCard(70.dp)
    SkeletonCard(70.dp)
}

/** Kurze, ehrliche Meldung zum Fehlschlag, mit zwei Aktionen. */
@Composable
private fun FailedExamView(exam: Exam, onMaterial: (Exam) -> Unit, onRetry: () -> Unit) {
    val text = when {
        exam.error?.contains("Limit", ignoreCase = true) == true -> "Gerade ausgelastet. Später nochmal."
        exam.error?.contains("nicht eingerichtet", ignoreCase = true) == true -> "Noch nicht eingerichtet."
        else -> exam.error ?: "Material nicht lesbar."
    }
    Spacer(Modifier.height(24.dp))
    LearnMark(56.dp)
    Text("Das hat nicht geklappt", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(text, color = Muted, textAlign = TextAlign.Center)
    PrimaryButton("Material ansehen", { onMaterial(exam) })
    SecondaryButton("Später nochmal", onRetry)
}

@Composable
private fun EmptyExamView(exam: Exam, onMaterial: (Exam) -> Unit) {
    Spacer(Modifier.height(24.dp))
    LearnMark(56.dp)
    Text("Noch kein Material", style = MaterialTheme.typography.headlineSmall)
    PrimaryButton("Material hinzufügen", { onMaterial(exam) })
}

/** Rueckblick nach der Pruefung: ruhig, ohne Lernknopf. */
@Composable
private fun ReviewView(exam: Exam, forecast: Forecast?) {
    Spacer(Modifier.height(8.dp))
    if (forecast == null) {
        SkeletonBox(Modifier.size(220.dp), androidx.compose.foundation.shape.CircleShape)
    } else {
        ForecastRing(forecast.scoreNowPercent)
        Text("So warst du zuletzt vorbereitet (Schätzung)", color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
    Text("Prüfung vorbei", style = MaterialTheme.typography.titleMedium)
    Text("Dein Fortschritt bleibt hier lesbar.", color = Muted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ReadyView(
    exam: Exam,
    forecast: Forecast?,
    onStart: (Exam, Int) -> Unit,
    onCheatsheet: (Exam) -> Unit,
) {
    var picked by remember { mutableStateOf<String?>(null) }
    val minutes = remainingMinutes(exam.dailyMinutes, forecast?.doneSecondsToday ?: 0)
    if (forecast == null) {
        SkeletonBox(Modifier.size(220.dp), androidx.compose.foundation.shape.CircleShape)
        SkeletonLine(200.dp)
    } else {
        ForecastRing(forecast.scoreNowPercent)
        Text(projectionLine(forecast, exam.dailyMinutes), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    }
    Text(formatRemaining(examStart(exam), Instant.now()), color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    PrimaryButton(startLabel(minutes), { onStart(exam, minutes) })
    if (forecast != null && forecast.weakTopics.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            forecast.weakTopics.take(3).forEach { t ->
                Pill(t.title.take(14), picked == t.topicId, { picked = if (picked == t.topicId) null else t.topicId })
            }
        }
        forecast.weakTopics.firstOrNull { it.topicId == picked }?.let {
            Text("${it.title}: ${masteryPercent(it.mastery)} %", color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
    if (forecast?.showCheatsheetSuggestion == true) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Card).hapticClickable { onCheatsheet(exam) }.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Letzte Runde: 5 Minuten lesen", style = MaterialTheme.typography.titleMedium)
            Text("Der Merkzettel mit dem Wichtigsten", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Grosser Prognose-Ring: Fuellung = Stand heute (stufenlos), die Zahl daneben in 5er-Schritten. */
@Composable
fun ForecastRing(percent: Int) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(percent) { sweep.animateTo(percent.coerceIn(0, 100) / 100f, tween(700)) }
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 16.dp.toPx()
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(CardHigh, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
            if (sweep.value > 0f) drawArc(Accent, -90f, 360f * sweep.value, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(percentText(percent), style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp, lineHeight = 56.sp))
    }
}
