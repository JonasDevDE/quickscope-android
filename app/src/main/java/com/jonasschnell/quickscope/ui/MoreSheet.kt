package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Black
import com.jonasschnell.quickscope.ui.theme.Muted
import com.jonasschnell.quickscope.ui.theme.Outline

/**
 * "Mehr" auf Heute: zwei grosse Zeilen, "Verstehen" (erst Erklaerungen hoeren) und "Testen" (nur Fragen). "Verstehen" erscheint nur,
 * wenn Audio nachweislich geht (siehe listenVisible); beim Antippen wird zuerst der Stream geprueft, waehrend die Zeile ruhig
 * "Einen Moment" zeigt. Alles andere liegt woanders (Einstellungen, Themenliste).
 */
@Composable
fun MoreSheet(canUnderstand: Boolean, probing: Boolean, onTest: () -> Unit, onUnderstand: () -> Unit, onDismiss: () -> Unit) {
    QuickscopeSheet(onDismiss) {
        if (canUnderstand) BigRow(Icons.Filled.PlayArrow, "Verstehen", if (probing) "Einen Moment" else "Erst Erklärungen hören") { if (!probing) onUnderstand() }
        BigRow(Icons.Filled.Edit, "Testen", "Nur Fragen") { if (!probing) { onDismiss(); onTest() } }
    }
}

@Composable
private fun BigRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    RowCard(onClick, minHeight = 76.dp) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Black).border(1.dp, Outline, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Accent)
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
