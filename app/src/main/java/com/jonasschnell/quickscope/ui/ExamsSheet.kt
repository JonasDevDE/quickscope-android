package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.AppViewModel
import com.jonasschnell.quickscope.UiState
import com.jonasschnell.quickscope.data.CheatSheet
import com.jonasschnell.quickscope.logic.formatExamWhen
import com.jonasschnell.quickscope.logic.isOver
import com.jonasschnell.quickscope.logic.sortExams
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Muted
import java.time.Instant

/** Pille im Kopf (ohne Pfeil): Name der aktiven Pruefung, Tippen oeffnet die Liste. */
@Composable
fun ExamPill(name: String, loading: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(CardHigh).hapticClickable(enabled = !loading, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.widthIn(max = 220.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Liste der Pruefungen mit Datum/Uhrzeit; Zeile waehlt, Drei-Punkte oeffnen die Einstellungen; unten "Neue Prüfung". */
@Composable
fun ExamsSheet(state: UiState, vm: AppViewModel, onNew: () -> Unit, onSettings: (String) -> Unit, onDismiss: () -> Unit) {
    val now = Instant.now()
    val active = state.activeExam?.id
    QuickscopeSheet(onDismiss, "Prüfungen") {
        sortExams(state.exams, now).forEach { e ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Card).hapticClickable { vm.selectExam(e.id); onDismiss() }.padding(start = 20.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (e.id == active) Accent else Card))
                Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatExamWhen(e.date, e.time) + if (isOver(e, now)) " · vorbei" else "",
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "⋮", style = MaterialTheme.typography.titleLarge, color = Muted,
                    modifier = Modifier.hapticClickable { onSettings(e.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        PrimaryButton("Neue Prüfung", onNew)
    }
}

/** Merkzettel (Letzte Runde): die wichtigsten Fakten als kurze Stichpunkte, nach Themen gruppiert. */
@Composable
fun CheatsheetSheet(vm: AppViewModel, examId: String, onDismiss: () -> Unit) {
    var sheet by remember { mutableStateOf<CheatSheet?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(examId) {
        sheet = vm.cheatsheetNow(examId)
        failed = sheet == null
    }
    QuickscopeSheet(onDismiss, "Merkzettel") {
        val s = sheet
        when {
            s != null -> {
                if (s.bullets.isEmpty()) EmptyHint("Noch nichts zum Lesen")
                var lastTopic: String? = null
                s.bullets.forEach { b ->
                    if (b.topic != lastTopic) {
                        lastTopic = b.topic
                        SectionTitle(b.topic, Modifier.padding(top = 8.dp), Accent)
                    }
                    Text("• ${b.text}", style = MaterialTheme.typography.bodyLarge)
                }
                if (s.note.isNotBlank()) Text(s.note, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            failed -> EmptyHint("Gerade nicht erreichbar")
            else -> {
                SkeletonCard(80.dp)
                SkeletonCard(80.dp)
                SkeletonCard(80.dp)
            }
        }
    }
}
