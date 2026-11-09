package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.CardHigh

/**
 * "Mehr" auf Heute: nur zwei grosse ruhige Zeilen, "Nur Fragen" und "Nur zuhören". "Nur zuhören" entfaellt ohne Lektionen,
 * offline und bei "Vorlesen aus"; dann bleibt ein Eintrag. Alles andere (Einstellungen, Material, Wechsel) liegt woanders.
 */
@Composable
fun MoreSheet(canListen: Boolean, onQuestionsOnly: () -> Unit, onListen: () -> Unit, onDismiss: () -> Unit) {
    QuickscopeSheet(onDismiss) {
        BigRow("?", "Nur Fragen") { onDismiss(); onQuestionsOnly() }
        if (canListen) BigRow("♪", "Nur zuhören") { onDismiss(); onListen() }
    }
}

@Composable
private fun BigRow(symbol: String, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp).clip(RoundedCornerShape(24.dp)).background(Card).hapticClickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(CardHigh), contentAlignment = Alignment.Center) {
            Text(symbol, color = Accent, style = MaterialTheme.typography.titleLarge)
        }
        Text(title, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
    }
}
