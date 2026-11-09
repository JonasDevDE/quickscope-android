package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.logic.PlayState
import com.jonasschnell.quickscope.logic.PlayButtonKind
import com.jonasschnell.quickscope.logic.playButtonKind
import com.jonasschnell.quickscope.ui.theme.CardHigh

/** Einfacher Play-/Pause-Knopf oben rechts: Pause beim Spielen, Play sonst. Kein Spinner, kein Stumm-Konzept. */
@Composable
fun PlayPauseButton(state: PlayState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val kind = playButtonKind(state)
    Box(modifier.size(40.dp).clip(CircleShape).background(CardHigh).hapticClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        when (kind) {
            PlayButtonKind.Pause -> Canvas(Modifier.size(16.dp)) {
                val w = size.width * 0.3f
                drawRoundRect(Color.White, Offset(size.width * 0.12f, 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
                drawRoundRect(Color.White, Offset(size.width * 0.58f, 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
            }
            PlayButtonKind.Play -> Canvas(Modifier.size(16.dp)) {
                drawPath(Path().apply { moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.15f, size.height); close() }, Color.White)
            }
        }
    }
}
