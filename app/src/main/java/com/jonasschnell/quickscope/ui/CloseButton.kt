package com.jonasschnell.quickscope.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.logic.acceptClick
import com.jonasschnell.quickscope.ui.theme.Muted

/**
 * Zentraler Schliessen-Knopf ("✕" oder "Zurück"): Treffflaeche mindestens 48 dp, gleich in jedem Zustand (auch beim Laden und
 * beim Audio-Gate), mit Mehrfach-Tap-Schutz. Die Zurueck-Geste ruft dasselbe [onClick] auf.
 */
@Composable
fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "✕") {
    var last by remember { mutableLongStateOf(0L) }
    Box(
        modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(24.dp)).hapticClickable {
            val now = SystemClock.elapsedRealtime()
            if (acceptClick(last, now)) {
                last = now
                onClick()
            }
        }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Muted, style = if (label == "✕") MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall)
    }
}

/**
 * Vollbild-Bildschirme duerfen nie Taps an das darunter liegende Heute durchlassen (sonst loest ein Tipp ins Leere dort z. B.
 * "Los" aus und startet Runden doppelt). Kinder behandeln ihre Taps zuerst; alles Uebrige wird hier verbraucht.
 */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
}
