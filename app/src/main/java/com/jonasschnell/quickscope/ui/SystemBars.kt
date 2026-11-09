package com.jonasschnell.quickscope.ui

import android.app.Activity
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Sorgt dafuer, dass Statusleisten- und Navigationsleisten-Symbole (Akku, Uhr) im aktuellen Fenster hell sind.
 * Dialog-/Sheet-Fenster haben eigene Window-Flags und fallen sonst auf dunkle Symbole zurueck (schwarz auf schwarz).
 * Muss INNERHALB des Dialog-Inhalts aufgerufen werden (z. B. im text-Slot eines AlertDialogs).
 */
@Composable
fun LightSystemIcons() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: (view.context as? Activity)?.window
            ?: return@SideEffect
        applyLightIcons(window, view)
    }
}

private fun applyLightIcons(window: android.view.Window, view: View) {
    val controller = WindowCompat.getInsetsController(window, view)
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
}
