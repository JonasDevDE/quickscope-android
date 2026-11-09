package com.jonasschnell.quickscope.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.ui.theme.Accent

/** Abschlusskappe der App in Akzentfarbe (flache Raute, Sockel-Bogen, Quaste); mit [spinning] pulsiert sie als Ladeanzeige. */
@Composable
fun LearnMark(size: Dp = 64.dp, modifier: Modifier = Modifier, spinning: Boolean = false) {
    val pulse = if (spinning) {
        rememberInfiniteTransition(label = "learn").animateFloat(
            initialValue = 0.9f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "learnPulse",
        ).value
    } else {
        1f
    }
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = Stroke(width = 1.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(pulse, pivot = Offset(12 * u, 12 * u)) {
            // Kappe: flache Raute
            drawPath(
                Path().apply {
                    moveTo(12 * u, 4.5f * u); lineTo(22 * u, 9 * u); lineTo(12 * u, 13.5f * u); lineTo(2 * u, 9 * u); close()
                },
                Accent, style = stroke,
            )
            // Sockel: Bogen unter der Raute
            drawPath(
                Path().apply {
                    moveTo(6 * u, 11.5f * u); lineTo(6 * u, 15 * u)
                    cubicTo(8.5f * u, 17.5f * u, 15.5f * u, 17.5f * u, 18 * u, 15 * u)
                    lineTo(18 * u, 11.5f * u)
                },
                Accent, style = stroke,
            )
            // Quaste: Linie mit kleinem Kreis
            drawLine(Accent, Offset(22 * u, 9 * u), Offset(22 * u, 15 * u), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawCircle(Accent, radius = 1.3f * u, center = Offset(22 * u, 16.5f * u))
        }
    }
}
