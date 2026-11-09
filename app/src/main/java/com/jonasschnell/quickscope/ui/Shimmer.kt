package com.jonasschnell.quickscope.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.ui.theme.Card

private val ShimmerBase = Color(0xFF161616)
private val ShimmerHighlight = Color(0xFF262626)

/** Soft gradient sweep used as a loading placeholder fill. */
@Composable
fun Modifier.shimmer(): Modifier {
    val progress = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerProgress",
    )
    return drawWithCache {
        val width = size.width
        onDrawBehind {
            val start = -width + progress.value * width * 2.2f
            drawRect(
                Brush.linearGradient(
                    colors = listOf(ShimmerBase, ShimmerHighlight, ShimmerBase),
                    start = Offset(start, 0f),
                    end = Offset(start + width, size.height * 0.4f),
                ),
            )
        }
    }
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    Box(modifier.clip(shape).shimmer())
}

@Composable
fun SkeletonLine(width: Dp, height: Dp = 14.dp, modifier: Modifier = Modifier) {
    SkeletonBox(modifier.width(width).height(height), RoundedCornerShape(8.dp))
}

@Composable
fun SkeletonCard(height: Dp, modifier: Modifier = Modifier) {
    SkeletonBox(modifier.fillMaxWidth().height(height), RoundedCornerShape(28.dp))
}

@Composable
fun SkeletonTile(modifier: Modifier = Modifier) {
    SkeletonBox(modifier.aspectRatio(1f), RoundedCornerShape(18.dp))
}
