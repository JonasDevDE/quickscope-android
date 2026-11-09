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
import androidx.compose.foundation.border
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.Color
import com.jonasschnell.quickscope.ui.theme.Outline
import com.jonasschnell.quickscope.ui.theme.Danger
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.AppViewModel
import com.jonasschnell.quickscope.UiState
import com.jonasschnell.quickscope.data.CheatSheet
import com.jonasschnell.quickscope.logic.examSubtitle
import com.jonasschnell.quickscope.logic.sortExams
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Muted
import java.time.Instant

/** Pille im Kopf (ohne Pfeil): Name der aktiven Pruefung, Tippen oeffnet die Liste. */
@Composable
fun ExamPill(name: String, loading: Boolean, onClick: () -> Unit, icon: String? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(CardHigh).hapticClickable(enabled = !loading, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!loading) TopicIcon(icon, 20.dp)
        Text(name, Modifier.widthIn(max = 220.dp).padding(start = if (loading) 0.dp else 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Meine Themen": Zeile waehlt, Drei-Punkte oeffnen das Menue (Bearbeiten, Material, Löschen); unten "Neues Thema". */
@Composable
fun ExamsSheet(
    state: UiState, vm: AppViewModel, onNew: () -> Unit, onEdit: (String) -> Unit, onMaterial: (String) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit,
) {
    val now = Instant.now()
    val active = state.activeExam?.id
    QuickscopeSheet(onDismiss, "Meine Themen") {
        sortExams(state.exams, now).forEach { e ->
            androidx.compose.runtime.key(e.id) {
                var menu by remember { mutableStateOf(false) }
                RowCard({ vm.selectExam(e.id); onDismiss() }) {
                    TopicIcon(e.icon, 24.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(e.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (e.id == active) FontWeight.Bold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(examSubtitle(e, now), color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        Text(
                            "⋮", style = MaterialTheme.typography.titleLarge, color = Color.White,
                            modifier = Modifier.hapticClickable { menu = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        DropdownMenu(menu, { menu = false }, modifier = Modifier.background(CardHigh).border(1.dp, Outline, RoundedCornerShape(12.dp))) {
                            DropdownMenuItem(text = { Text("Bearbeiten") }, onClick = { menu = false; onEdit(e.id) })
                            DropdownMenuItem(text = { Text("Material") }, onClick = { menu = false; onMaterial(e.id) })
                            DropdownMenuItem(text = { Text("Löschen", color = Danger) }, onClick = { menu = false; onDelete(e.id) })
                        }
                    }
                }
            }
        }
        PrimaryButton("Neues Thema", onNew)
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
