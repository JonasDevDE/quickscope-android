package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.UiState
import com.jonasschnell.quickscope.data.Exam
import com.jonasschnell.quickscope.logic.MoreEntry
import com.jonasschnell.quickscope.logic.budgetLine
import com.jonasschnell.quickscope.logic.moreEntries
import com.jonasschnell.quickscope.logic.planLine
import com.jonasschnell.quickscope.ui.theme.Muted

/** Alles Weitere zu Heute, ruhig gruppiert hinter "Mehr": Ueben, Heute, Pruefung. */
@Composable
fun MoreSheet(
    state: UiState, exam: Exam, dayStreak: Int?,
    onQuestionsOnly: () -> Unit, onListen: () -> Unit, onMaterial: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit,
) {
    val forecast = state.activeForecast
    val entries = moreEntries((forecast?.plan?.chaptersTotal ?: 0) > 0, planLine(forecast?.plan) != null, dayStreak)
    QuickscopeSheet(onDismiss) {
        if (MoreEntry.QuestionsOnly in entries) {
            SectionTitle("Üben")
            SettingsRow("Nur Fragen", null, { onDismiss(); onQuestionsOnly() }) {}
            SettingsRow("Nur zuhören", null, { onDismiss(); onListen() }) {}
            Spacer(Modifier.height(4.dp))
        }
        SectionTitle("Heute")
        if (MoreEntry.Plan in entries) InfoRow(planLine(forecast?.plan).orEmpty())
        if (MoreEntry.Used in entries) InfoRow(budgetLine(exam.dailyMinutes, forecast?.doneSecondsToday ?: 0))
        if (MoreEntry.Streak in entries) InfoRow("Serie: $dayStreak ${if (dayStreak == 1) "Tag" else "Tage"}")
        Spacer(Modifier.height(4.dp))
        SectionTitle("Prüfung")
        SettingsRow("Material", null, { onDismiss(); onMaterial() }) {}
        SettingsRow("Einstellungen", null, { onDismiss(); onSettings() }) {}
    }
}

@Composable
private fun InfoRow(text: String) {
    Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
}
