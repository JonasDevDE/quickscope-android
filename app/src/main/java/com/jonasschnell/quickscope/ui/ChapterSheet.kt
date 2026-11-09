package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.logic.ChapterEntry
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Muted

/** Kapitelliste in "Verstehen": Titel und Status (neu, gehört, sitzt); Tippen springt zur ersten ungehörten Lektion des Kapitels. */
@Composable
fun ChapterSheet(entries: List<ChapterEntry>, currentChapterId: String?, onPick: (ChapterEntry) -> Unit, onDismiss: () -> Unit) {
    QuickscopeSheet(onDismiss, "Kapitel") {
        entries.forEach { e ->
            RowCard({ onDismiss(); onPick(e) }) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${e.order}. ${e.title}", maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (e.chapterId == currentChapterId) FontWeight.Bold else FontWeight.Normal),
                    )
                }
                Text(e.status, color = if (e.status == "neu") Muted else Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}
