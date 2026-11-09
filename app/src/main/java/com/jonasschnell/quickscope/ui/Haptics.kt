package com.jonasschnell.quickscope.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView

/** Semantik der Haptik; siehe [Haptics.perform] fuer das Mapping auf Plattform-Konstanten. */
enum class HapticKind {
    /** Leichter Tick: Taps auf Kacheln, Buttons, Tabs, Zeilen; Oeffnen von Sheets/Dialogen. */
    Tick,
    /** Erfolg (gespeichert, hochgeladen, beigetreten ...). */
    Confirm,
    /** Fehler / Abbruch. */
    Reject,
    /** Long-Press erkannt. */
    LongPress,
    /** Ende einer Geste (Wegwischen, Rueckgaengig, Loeschen). */
    GestureEnd,
    /** Rasten beim Scrubben / Werte-Auswahl. */
    Segment,
}

/** Zentrale Haptik ueber View.performHapticFeedback (keine VIBRATE-Permission noetig). */
@Stable
class Haptics(private val view: View?) {
    /** Globaler Schalter aus den Einstellungen. */
    var enabled: Boolean = true
    private var lastReject = 0L

    fun perform(kind: HapticKind, force: Boolean = false) {
        val v = view ?: return
        if (!enabled && !force) return
        if (kind == HapticKind.Reject) {
            // Mehrere parallele Fehlschlaege (z. B. 4 Ladefehler) nur einmal spueren.
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastReject < 600) return
            lastReject = now
        }
        v.performHapticFeedback(constant(kind))
    }

    fun tick() = perform(HapticKind.Tick)
    fun confirm() = perform(HapticKind.Confirm)
    fun reject() = perform(HapticKind.Reject)
    fun longPress() = perform(HapticKind.LongPress)
    fun gestureEnd() = perform(HapticKind.GestureEnd)
    fun segment() = perform(HapticKind.Segment)

    private fun constant(kind: HapticKind): Int {
        val sdk = Build.VERSION.SDK_INT
        return when (kind) {
            HapticKind.Tick -> HapticFeedbackConstants.CLOCK_TICK
            HapticKind.LongPress -> HapticFeedbackConstants.LONG_PRESS
            HapticKind.Confirm ->
                if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            HapticKind.Reject ->
                if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            HapticKind.GestureEnd ->
                if (sdk >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK
            HapticKind.Segment ->
                if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        }
    }
}

val LocalHaptics = compositionLocalOf { Haptics(null) }

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Wie clickable, loest aber vor dem Callback die Haptik [kind] aus. */
fun Modifier.hapticClickable(
    enabled: Boolean = true,
    kind: HapticKind = HapticKind.Tick,
    onClick: () -> Unit,
): Modifier = composed {
    val haptics = LocalHaptics.current
    clickable(enabled = enabled) {
        haptics.perform(kind)
        onClick()
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.hapticCombinedClickable(
    onLongClick: () -> Unit,
    onClick: () -> Unit,
): Modifier = composed {
    val haptics = LocalHaptics.current
    combinedClickable(
        onClick = {
            haptics.tick()
            onClick()
        },
        onLongClick = {
            haptics.longPress()
            onLongClick()
        },
    )
}

/** Tick beim Erscheinen (Dialog, Sheet). */
@Composable
fun TickOnAppear() {
    val haptics = LocalHaptics.current
    LaunchedEffect(Unit) { haptics.tick() }
}

/** Segment-Tick bei jedem Wertwechsel von [key], nicht beim ersten Anzeigen. */
@Composable
fun SegmentOnChange(key: Any?) {
    val haptics = LocalHaptics.current
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) first[0] = false else haptics.segment()
    }
}

@Composable
fun HapticTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val haptics = LocalHaptics.current
    TextButton(onClick = { haptics.tick(); onClick() }, modifier = modifier, content = content)
}
