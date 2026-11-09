package com.jonasschnell.quickscope.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
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
import com.jonasschnell.quickscope.data.neutralMessage
import com.jonasschnell.quickscope.data.Forecast
import com.jonasschnell.quickscope.logic.examStart
import com.jonasschnell.quickscope.logic.dayGoalReached
import com.jonasschnell.quickscope.logic.relativeDayLabel
import com.jonasschnell.quickscope.logic.forecastReliable
import com.jonasschnell.quickscope.logic.isOpen
import com.jonasschnell.quickscope.logic.isOver
import com.jonasschnell.quickscope.logic.ringPercent
import com.jonasschnell.quickscope.logic.masteryPercent
import com.jonasschnell.quickscope.logic.percentText
import com.jonasschnell.quickscope.logic.projectionLine
import com.jonasschnell.quickscope.logic.remainingMinutes
import com.jonasschnell.quickscope.logic.START_LABEL
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
    onOpenMore: (Exam) -> Unit,
    onMore: (Exam) -> Unit,
    onMaterial: (Exam) -> Unit,
    onCheatsheet: (Exam) -> Unit,
    onRetry: () -> Unit,
    onCancelUpload: () -> Unit,
    onRetryUpload: () -> Unit,
) {
    val exam = state.activeExam
    // Insets beachten (Gestenleiste, Navigationsleiste, Aussparungen) plus Mindestabstand unten; der Inhalt bleibt eine kompakte,
    // mittig sitzende Spalte (nie ueber die ganze Hoehe aufgespreizt) und scrollt nur, wenn er bei grosser Schrift nicht passt.
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 480.dp).align(Alignment.Center).heightIn(min = maxHeight).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
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
                else -> ReadyView(exam, state.activeForecast, onStart, onOpenMore, onMore, onCheatsheet)
            }
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
        else -> exam.error?.let { neutralMessage(it) } ?: "Material nicht lesbar."
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
        if (forecast.coveragePercent > 0) ForecastRing(forecast.scoreNowPercent) else ForecastPlaceholder()
        Text("So warst du zuletzt vorbereitet (Schätzung)", color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
    Text("Prüfung vorbei", style = MaterialTheme.typography.titleMedium)
}

/**
 * Heute, radikal reduziert, als EINE kompakte Spalte mit festen Abstaenden: Countdown, Ring mit EINER Prognosezeile, Merkzettel
 * (nur kurz vor der Pruefung), der grosse Knopf "Starten" und direkt darunter "Mehr" (mindestens 48 dp, gut mit dem
 * Daumen erreichbar). Nichts haengt am Bildschirmrand.
 */
@Composable
private fun ReadyView(
    exam: Exam,
    forecast: Forecast?,
    onStart: (Exam, Int) -> Unit,
    onOpenMore: (Exam) -> Unit,
    onMore: (Exam) -> Unit,
    onCheatsheet: (Exam) -> Unit,
) {
    val open = isOpen(exam)
    val minutes = remainingMinutes(exam.dailyMinutes, forecast?.doneSecondsToday ?: 0)
    if (!open) {
        Text(relativeDayLabel(examStart(exam), Instant.now(), startOfSentence = true), color = Muted, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    }
    if (forecast == null) {
        SkeletonBox(Modifier.size(220.dp), androidx.compose.foundation.shape.CircleShape)
    } else if (forecastReliable(exam, forecast)) {
        ForecastRing(ringPercent(exam, forecast))
        Text(
            if (open) "Wissensstand" else projectionLine(forecast, exam.dailyMinutes),
            color = Muted, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
        )
    } else {
        ForecastPlaceholder()
    }
    Spacer(Modifier.height(8.dp))
    if (forecast?.showCheatsheetSuggestion == true && !open) {
        Text(
            "Letzte Runde: Merkzettel lesen", color = Accent, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).hapticClickable { onCheatsheet(exam) }.padding(vertical = 12.dp),
        )
    }
    if (forecast != null && dayGoalReached(exam.dailyMinutes, forecast.doneSecondsToday)) {
        Text("✓ Tagesziel geschafft", color = Accent, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(top = 14.dp))
        HapticTextButton(onClick = { onMore(exam) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Noch eine Runde", color = Muted, style = MaterialTheme.typography.labelLarge) }
    } else {
        PrimaryButton(START_LABEL, { onStart(exam, minutes) })
        HapticTextButton(onClick = { onOpenMore(exam) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Mehr", color = Muted, style = MaterialTheme.typography.labelLarge) }
    }
}

/** Leerer Ring ohne Zahl, solange es keine belastbare Prognose gibt. */
@Composable
private fun ForecastPlaceholder() {
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 16.dp.toPx()
            drawArc(CardHigh, -90f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        Text("–", style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp, lineHeight = 56.sp), color = Muted)
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
