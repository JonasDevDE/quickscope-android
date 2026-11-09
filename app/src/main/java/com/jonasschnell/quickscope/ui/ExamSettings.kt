package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.AppViewModel
import com.jonasschnell.quickscope.UiState
import com.jonasschnell.quickscope.data.Exam
import com.jonasschnell.quickscope.data.neutralMessage
import com.jonasschnell.quickscope.data.ExamUpdate
import com.jonasschnell.quickscope.data.Material
import com.jonasschnell.quickscope.logic.DAILY_MINUTE_CHOICES
import com.jonasschnell.quickscope.logic.PRIOR_KEYS
import com.jonasschnell.quickscope.logic.minutesChoiceLabel
import com.jonasschnell.quickscope.logic.priorLabel
import com.jonasschnell.quickscope.logic.formatExamWhen
import com.jonasschnell.quickscope.logic.isInPast
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Danger
import com.jonasschnell.quickscope.ui.theme.Muted
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Einstellungen, die ein eigenes kleines Sheet oeffnen. */
enum class ExamSetting { Material, Description, Name, When, Daily, Prior, Regenerate, Reset, Delete }

/** Pruefungs-Einstellungen: eine Zeile je Einstellung, jede oeffnet ein kleines Sheet; gespeichert wird automatisch. */
@Composable
fun ExamSettingsSheet(state: UiState, vm: AppViewModel, examId: String, startAt: ExamSetting? = null, onDismiss: () -> Unit) {
    var sub by remember { mutableStateOf(startAt) }
    val exam = state.exams.firstOrNull { it.id == examId }
    if (exam == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    when (sub) {
        null -> QuickscopeSheet(onDismiss, exam.name) {
            SettingsRow("Material", null, { sub = ExamSetting.Material }) { Value("${exam.materialCount}") }
            SettingsRow("Beschreibung", null, { sub = ExamSetting.Description }) { Value(if (exam.description.isNullOrBlank()) "–" else "gesetzt") }
            SettingsRow("Name", null, { sub = ExamSetting.Name }) { Value(exam.name) }
            SettingsRow("Datum und Uhrzeit", null, { sub = ExamSetting.When }) { Value(formatExamWhen(exam.date, exam.time)) }
            SettingsRow("Lernzeit pro Tag", null, { sub = ExamSetting.Daily }) { Value("${exam.dailyMinutes} min") }
            SettingsRow("Vorwissen", null, { sub = ExamSetting.Prior }) { Value(priorLabel(exam.priorKnowledge)) }
            SettingsRow("Aufgaben neu erzeugen", null, { sub = ExamSetting.Regenerate }) {}
            SettingsRow("Fortschritt zurücksetzen", null, { sub = ExamSetting.Reset }) {}
            SettingsRow("Prüfung löschen", null, { sub = ExamSetting.Delete }) {}
        }
        ExamSetting.Material -> MaterialSheet(state, vm, exam) { sub = null }
        ExamSetting.Description -> DescriptionSheet(vm, exam) { sub = null }
        ExamSetting.Name -> NameSheet(vm, exam) { sub = null }
        ExamSetting.When -> WhenSheet(vm, exam) { sub = null }
        ExamSetting.Daily -> DailySheet(state, vm, exam) { sub = null }
        ExamSetting.Prior -> PriorSheet(vm, exam) { sub = null }
        ExamSetting.Regenerate -> AlertDialog(
            onDismissRequest = { sub = null },
            containerColor = Card,
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCFCFD4),
            title = { Text("Aufgaben neu erzeugen?") },
            text = { LightSystemIcons(); Text("Dein Lernstand wird zurückgesetzt.") },
            confirmButton = { HapticTextButton(onClick = { vm.regenerateItems(exam.id); onDismiss() }) { Text("Neu erzeugen", color = Accent) } },
            dismissButton = { HapticTextButton(onClick = { sub = null }) { Text("Abbrechen", color = Color.White) } },
        )
        ExamSetting.Reset -> TwoStepConfirm(
            "Fortschritt zurücksetzen?", "Dein Lernstand wird gelöscht. Material und Aufgaben bleiben.",
            "Wirklich zurücksetzen?", "Letzte Bestätigung: Der Lernstand dieser Prüfung wird jetzt zurückgesetzt.",
            "Zurücksetzen", { vm.resetProgress(exam.id); onDismiss() }, { sub = null },
        )
        ExamSetting.Delete -> TwoStepConfirm(
            "Prüfung löschen?", "Material, Aufgaben und Fortschritt dieser Prüfung werden gelöscht.",
            "Wirklich endgültig löschen?", "Letzte Bestätigung: „${exam.name}“ wird unwiderruflich gelöscht.",
            "Endgültig löschen", { vm.deleteExam(exam.id); onDismiss() }, { sub = null },
        )
    }
}

@Composable
private fun Value(text: String) {
    Text(text, color = Muted, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).widthIn(max = 160.dp))
}

/** Zweistufige Bestaetigung wie beim Konto loeschen. */
@Composable
fun TwoStepConfirm(title1: String, text1: String, title2: String, text2: String, confirmLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    TickOnAppear()
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Card,
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCFCFD4),
        title = { Text(if (step == 1) title1 else title2) },
        text = { LightSystemIcons(); Text(if (step == 1) text1 else text2) },
        confirmButton = {
            HapticTextButton(onClick = { if (step == 1) step = 2 else onConfirm() }) { Text(if (step == 1) "Weiter" else confirmLabel, color = Danger) }
        },
        dismissButton = { HapticTextButton(onClick = onCancel) { Text("Abbrechen", color = Color.White) } },
    )
}

@Composable
private fun NameSheet(vm: AppViewModel, exam: Exam, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(exam.name) }
    val saved = AutoSave(name.trim(), name.isNotBlank()) { v, fail -> vm.updateExam(exam.id, ExamUpdate(name = v), fail) }
    QuickscopeSheet(onDismiss, "Name", status = if (saved) "Gespeichert" else null) {
        QuickscopeField(name, { name = it.take(80) }, "Name")
    }
}

@Composable
private fun DescriptionSheet(vm: AppViewModel, exam: Exam, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(exam.description.orEmpty()) }
    val saved = AutoSave(text.trim(), true) { v, fail -> vm.updateExam(exam.id, ExamUpdate(description = v), fail) }
    QuickscopeSheet(onDismiss, "Beschreibung", status = if (saved) "Gespeichert" else null) {
        QuickscopeField(text, { text = it.take(2000) }, "z. B. IHK AP2, Schwerpunkt Kalkulation", singleLine = false)
        Text("${text.length} / 2000", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WhenSheet(vm: AppViewModel, exam: Exam, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(runCatching { LocalDate.parse(exam.date) }.getOrNull()) }
    var time by remember { mutableStateOf(runCatching { LocalTime.parse(exam.time) }.getOrDefault(LocalTime.of(8, 0))) }
    val past = date?.let { isInPast(it, time, Instant.now()) } == true
    val saved = AutoSave(date to time, date != null && !past, instantKey = date to time) { (d, t), fail ->
        vm.updateExam(exam.id, ExamUpdate(date = d.toString(), time = "%02d:%02d".format(t.hour, t.minute)), fail)
    }
    QuickscopeSheet(onDismiss, "Datum und Uhrzeit", status = if (saved) "Gespeichert" else null) {
        WhenPicker(date, time, { date = it }, { time = it })
        if (past) Text("Dieser Zeitpunkt ist schon vorbei.", color = Danger, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Vorwissen: drei Pills, Autosave. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorSheet(vm: AppViewModel, exam: Exam, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(exam.priorKnowledge) }
    val saved = AutoSave(value, true, instantKey = value) { v, _ -> vm.updateExam(exam.id, ExamUpdate(priorKnowledge = v)) }
    QuickscopeSheet(onDismiss, "Vorwissen", status = if (saved) "Gespeichert" else null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PRIOR_KEYS.forEach { k -> Pill(priorLabel(k), value == k, { value = k }) }
        }
    }
}

/** Lernzeit pro Tag: jede Stufe als Chip mit der Projektion daneben, die gewaehlte hervorgehoben; Autosave. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DailySheet(state: UiState, vm: AppViewModel, exam: Exam, onDismiss: () -> Unit) {
    var value by remember { mutableIntStateOf(exam.dailyMinutes) }
    val forecast = state.forecast?.takeIf { state.forecastExamId == exam.id }
    val saved = AutoSave(value, true, instantKey = value) { v, _ -> vm.updateExam(exam.id, ExamUpdate(dailyMinutes = v)) }
    QuickscopeSheet(onDismiss, "Lernzeit pro Tag", status = if (saved) "Gespeichert" else null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DAILY_MINUTE_CHOICES.forEach { c -> Pill(minutesChoiceLabel(forecast, c), value == c, { value = c }) }
        }
    }
}

// ---------------------------------------------------------------- Material

private fun roleLabel(m: Material) = if (m.role == "exam") "Aufgaben" else "Skript"

private fun statusLabel(m: Material) = when (m.status) {
    "queued" -> "Wartet"
    "processing" -> "Wird gelesen"
    "done" -> "Fertig"
    "failed" -> "Fehler"
    else -> m.status
}

@Composable
private fun Chip(text: String, color: Color = Muted) {
    Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clip(RoundedCornerShape(50)).background(CardHigh).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Material einer Pruefung: Liste mit Rolle, Status, Seiten und Warnung; Original ansehen, entfernen, hinzufuegen. */
@Composable
private fun MaterialSheet(state: UiState, vm: AppViewModel, exam: Exam, onDismiss: () -> Unit) {
    var removing by remember { mutableStateOf<Material?>(null) }
    LaunchedEffect(exam.id) { vm.loadMaterials(exam.id) }
    val list = state.materials.takeIf { state.materialsExamId == exam.id }
    QuickscopeSheet(onDismiss, "Material") {
        UploadCard(state, exam.id, vm::cancelUpload, vm::retryUpload)
        if (list == null) {
            SkeletonCard(70.dp)
            SkeletonCard(70.dp)
        } else if (list.isEmpty()) {
            EmptyHint("Noch kein Material")
        } else {
            list.forEach { m ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Card).hapticClickable { vm.openPreview(exam.id, m) }.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("✕", color = Muted, modifier = Modifier.hapticClickable { removing = m }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(roleLabel(m))
                        Chip(statusLabel(m), if (m.status == "failed") Danger else if (m.status == "done") Accent else Muted)
                        m.pages?.let { Chip("$it ${if (it == 1) "Seite" else "Seiten"}") }
                    }
                    m.warning?.let { Text("$it. Am besten neu aufnehmen.", color = com.jonasschnell.quickscope.ui.theme.Warn, style = MaterialTheme.typography.bodySmall) }
                    if (m.status == "failed") m.error?.let { Text(neutralMessage(it), color = Danger, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Text("Material hinzufügen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        MaterialAddButtons(onJobs = { vm.upload(exam.id, it) })
    }
    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Card,
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCFCFD4),
            title = { Text("Material entfernen?") },
            text = { LightSystemIcons(); Text("„${m.name}“ und die daraus erzeugten Aufgaben werden entfernt.") },
            confirmButton = { HapticTextButton(onClick = { removing = null; vm.deleteMaterial(exam.id, m.id) }) { Text("Entfernen", color = Danger) } },
            dismissButton = { HapticTextButton(onClick = { removing = null }) { Text("Abbrechen", color = Color.White) } },
        )
    }
}
