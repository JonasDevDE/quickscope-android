package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.jonasschnell.quickscope.logic.isOpen
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
enum class ExamSetting { Icon, Material, Description, Name, Kind, Daily, Prior, Reset, Delete }

/**
 * Thema-Seite im Stil der Profilseite: Name, Termin, Material, Beschreibung, Lernzeit, Vorwissen und am Ende der Gefahrenbereich.
 * Erreichbar nur ueber das Drei-Punkte-Menue in "Meine Themen". Gespeichert wird automatisch und ohne Hinweis.
 */
@Composable
fun ExamSettingsSheet(state: UiState, vm: AppViewModel, examId: String, startAt: ExamSetting? = null, onDismiss: () -> Unit) {
    var sub by remember { mutableStateOf(startAt) }
    val exam = state.exams.firstOrNull { it.id == examId }
    if (exam == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var name by remember(exam.id) { mutableStateOf(exam.name) }
    AutoSave(name.trim(), name.isNotBlank()) { v, fail -> vm.updateExam(exam.id, ExamUpdate(name = v), fail) }
    // Die Seite bleibt immer sichtbar; Unterthemen kommen als kleine Sheets darueber.
    PageScaffold("Thema", onDismiss) {
        QuickscopeField(name, { name = it.take(80) }, "Name")
        SettingsRow("Icon", null, { sub = ExamSetting.Icon }) { TopicIcon(exam.icon, 28.dp) }
        SettingsRow("Termin", null, { sub = ExamSetting.Kind }) { Value(if (isOpen(exam)) "Ohne Termin" else formatExamWhen(exam.date, exam.time)) }
        SettingsRow("Material", null, { sub = ExamSetting.Material }) { Value(if (exam.materialCount == 1L) "1 Datei" else "${exam.materialCount} Dateien") }
        SettingsRow("Beschreibung", null, { sub = ExamSetting.Description }) { Value(exam.description?.takeIf { it.isNotBlank() } ?: "–") }
        SettingsRow("Lernzeit pro Tag", null, { sub = ExamSetting.Daily }) { Value("${exam.dailyMinutes} min") }
        SettingsRow("Vorwissen", null, { sub = ExamSetting.Prior }) { Value(priorLabel(exam.priorKnowledge)) }
        DangerZone {
            DangerRow("Von vorn beginnen") { sub = ExamSetting.Reset }
            DangerRow("Thema löschen") { sub = ExamSetting.Delete }
        }
    }
    when (sub) {
        null -> Unit
        ExamSetting.Icon -> IconSheet(vm, exam) { sub = null }
        ExamSetting.Material -> MaterialSheet(state, vm, exam) { sub = null }
        ExamSetting.Description -> DescriptionSheet(vm, exam) { sub = null }
        ExamSetting.Name -> Unit
        ExamSetting.Kind -> KindSheet(vm, exam) { sub = null }
        ExamSetting.Daily -> DailySheet(state, vm, exam) { sub = null }
        ExamSetting.Prior -> PriorSheet(vm, exam) { sub = null }
        ExamSetting.Reset -> TwoStepConfirm(
            "Von vorn beginnen?", "Der Lernstand dieses Themas wird zurückgesetzt und alle Aufgaben werden neu aufgebaut.",
            "Wirklich von vorn beginnen?", "Letzte Bestätigung: Der Lernstand wird jetzt zurückgesetzt.",
            "Von vorn beginnen", { vm.regenerateItems(exam.id, resetProgress = true); onDismiss() }, { sub = null },
        )
        ExamSetting.Delete -> TwoStepConfirm(
            "Thema löschen?", "Material, Aufgaben und Fortschritt dieses Themas werden gelöscht.",
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
        containerColor = com.jonasschnell.quickscope.ui.theme.CardHigh,
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
private fun DescriptionSheet(vm: AppViewModel, exam: Exam, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(exam.description.orEmpty()) }
    val saved = AutoSave(text.trim(), true) { v, fail -> vm.updateExam(exam.id, ExamUpdate(description = v), fail) }
    PageScaffold("Beschreibung", onDismiss) {
        QuickscopeField(text, { text = it.take(2000) }, "z. B. IHK AP2, Schwerpunkt Kalkulation", modifier = Modifier.heightIn(min = 180.dp), singleLine = false)
        Text("${text.length} / 2000", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Icon waehlen: Raster der festen Sammlung (5 Spalten, grosse Treffer); setzt sofort. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconSheet(vm: AppViewModel, exam: Exam, onDismiss: () -> Unit) {
    val current = com.jonasschnell.quickscope.logic.normalizeIcon(exam.icon)
    QuickscopeSheet(onDismiss, "Icon") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 5) {
            com.jonasschnell.quickscope.logic.TOPIC_ICON_KEYS.forEach { key ->
                val selected = key == current
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
                        .then(if (selected) Modifier.background(CardHigh).border(1.dp, com.jonasschnell.quickscope.ui.theme.Outline, RoundedCornerShape(16.dp)) else Modifier)
                        .hapticClickable { vm.updateExam(exam.id, ExamUpdate(icon = key)); onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    TopicIcon(key, 28.dp, tint = if (selected) Color.White else Muted)
                }
            }
        }
    }
}

/** Termin: "Mit Termin" (Datum und Uhrzeit des Pruefungstermins) oder "Ohne Termin" (dauerhaft lernen). */
@Composable
private fun KindSheet(vm: AppViewModel, exam: Exam, onDismiss: () -> Unit) {
    val wasExam = !isOpen(exam)
    var wantsExam by remember { mutableStateOf(wasExam) }
    var date by remember { mutableStateOf(runCatching { LocalDate.parse(exam.date) }.getOrNull()) }
    var time by remember { mutableStateOf(runCatching { LocalTime.parse(exam.time) }.getOrDefault(LocalTime.of(8, 0))) }
    val past = date?.let { isInPast(it, time, Instant.now()) } == true
    AutoSave(date to time, wasExam && wantsExam && date != null && !past, instantKey = date to time) { (d, t), fail ->
        vm.updateExam(exam.id, ExamUpdate(date = d.toString(), time = "%02d:%02d".format(t.hour, t.minute)), fail)
    }
    QuickscopeSheet(onDismiss, "Termin") {
        if (wantsExam) PrimaryButton("Mit Termin", {}) else SecondaryButton("Mit Termin", { wantsExam = true })
        if (!wantsExam) PrimaryButton("Ohne Termin", {}) else SecondaryButton("Ohne Termin", {
            vm.updateExam(exam.id, ExamUpdate(mode = "open"))
            onDismiss()
        })
        if (wantsExam) {
            WhenPicker(date, time, { date = it }, { time = it })
            if (past) Text("Dieser Zeitpunkt ist schon vorbei.", color = Danger, style = MaterialTheme.typography.bodyMedium)
            if (!wasExam) PrimaryButton("Speichern", {
                vm.updateExam(exam.id, ExamUpdate(mode = "exam", date = date.toString(), time = "%02d:%02d".format(time.hour, time.minute)))
                onDismiss()
            }, enabled = date != null && !past)
        }
    }
}

/** Vorwissen: drei Pills, Autosave. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorSheet(vm: AppViewModel, exam: Exam, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(exam.priorKnowledge) }
    val saved = AutoSave(value, true, instantKey = value) { v, _ -> vm.updateExam(exam.id, ExamUpdate(priorKnowledge = v)) }
    QuickscopeSheet(onDismiss, "Vorwissen", status = null) {
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
    QuickscopeSheet(onDismiss, "Lernzeit pro Tag", status = null) {
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
    Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clip(RoundedCornerShape(50)).background(com.jonasschnell.quickscope.ui.theme.Black).border(1.dp, com.jonasschnell.quickscope.ui.theme.Outline, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Material einer Pruefung: Liste mit Rolle, Status, Seiten und Warnung; Original ansehen, entfernen, hinzufuegen. */
@Composable
private fun MaterialSheet(state: UiState, vm: AppViewModel, exam: Exam, onDismiss: () -> Unit) {
    var removing by remember { mutableStateOf<Material?>(null) }
    LaunchedEffect(exam.id) { vm.loadMaterials(exam.id) }
    val list = state.materials.takeIf { state.materialsExamId == exam.id }
    PageScaffold("Material", onDismiss) {
        UploadCard(state, exam.id, vm::cancelUpload, vm::retryUpload)
        if (list == null) {
            SkeletonCard(70.dp)
            SkeletonCard(70.dp)
        } else if (list.isEmpty()) {
            EmptyHint("Noch kein Material")
        } else {
            list.forEach { m ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardHigh).border(1.dp, com.jonasschnell.quickscope.ui.theme.Outline, RoundedCornerShape(24.dp)).hapticClickable { vm.openPreview(exam.id, m) }.padding(16.dp),
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
        MaterialAddButtons(onJobs = { vm.upload(exam.id, it) })
    }
    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = com.jonasschnell.quickscope.ui.theme.CardHigh,
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCFCFD4),
            title = { Text("Material entfernen?") },
            text = { LightSystemIcons(); Text("„${m.name}“ und die daraus erzeugten Aufgaben werden entfernt.") },
            confirmButton = { HapticTextButton(onClick = { removing = null; vm.deleteMaterial(exam.id, m.id) }) { Text("Entfernen", color = Danger) } },
            dismissButton = { HapticTextButton(onClick = { removing = null }) { Text("Abbrechen", color = Color.White) } },
        )
    }
}
