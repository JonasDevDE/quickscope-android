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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.ui.theme.Accent

/** Fadenkreuz (Scope) der App in Akzentfarbe: Ring, vier Striche und Mittelpunkt; mit [spinning] dreht es sich pendelnd als Ladeanzeige. */
@Composable
fun ScopeMark(size: Dp = 64.dp, modifier: Modifier = Modifier, spinning: Boolean = false) {
    val angle = if (spinning) {
        rememberInfiniteTransition(label = "scope").animateFloat(
            initialValue = -35f,
            targetValue = 35f,
            animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "scopeAngle",
        ).value
    } else {
        0f
    }
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val c = Offset(12 * u, 12 * u)
        val stroke = Stroke(width = 1.4f * u, cap = StrokeCap.Round)
        rotate(angle, pivot = c) {
            drawCircle(Accent, radius = 8f * u, center = c, style = stroke)
            // Vier Striche: vom Rand ueber den Ring ein Stueck nach innen
            drawLine(Accent, Offset(12 * u, 1.5f * u), Offset(12 * u, 8 * u), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(Accent, Offset(12 * u, 16 * u), Offset(12 * u, 22.5f * u), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(Accent, Offset(1.5f * u, 12 * u), Offset(8 * u, 12 * u), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(Accent, Offset(16 * u, 12 * u), Offset(22.5f * u, 12 * u), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawCircle(Accent, radius = 1.3f * u, center = c)
        }
    }
}
