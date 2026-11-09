package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Black
import com.jonasschnell.quickscope.ui.theme.Muted
import com.jonasschnell.quickscope.ui.theme.Outline

/**
 * "Mehr" auf Heute: "Verstehen" (nur wenn es Lektionen gibt) und "Testen". Layout-stabil: feste Abstaende (oben 8 dp zusaetzlich zum Griff,
 * 12 dp zwischen den Eintraegen, unten 24 dp plus Systemleisten), Hoehe folgt dem Inhalt, bei einem oder zwei Eintraegen gleiche Optik.
 * "Verstehen" oeffnet sofort die Lektionsseite, es gibt keinen Wartezustand.
 */
@Composable
fun MoreSheet(canUnderstand: Boolean, onTest: () -> Unit, onUnderstand: () -> Unit, onDismiss: () -> Unit) {
    QuickscopeSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canUnderstand) BigRow(com.jonasschnell.quickscope.R.drawable.ic_headphones, "Verstehen", "Erst Erklärungen hören") { onDismiss(); onUnderstand() }
            BigRow(com.jonasschnell.quickscope.R.drawable.ic_quiz, "Testen", "Nur Fragen") { onDismiss(); onTest() }
        }
    }
}

@Composable
private fun BigRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    RowCard(onClick, minHeight = 76.dp) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Black).border(1.dp, Outline, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = Accent)
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
