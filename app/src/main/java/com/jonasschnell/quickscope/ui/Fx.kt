package com.jonasschnell.quickscope.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jonasschnell.quickscope.Fx
import com.jonasschnell.quickscope.Sits
import com.jonasschnell.quickscope.logic.Celebration
import com.jonasschnell.quickscope.logic.FeedbackTuning
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Muted
import com.jonasschnell.quickscope.ui.theme.Soft
import com.jonasschnell.quickscope.ui.theme.Warn
import kotlinx.coroutines.delay
import java.util.Random
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private val Palette = arrayOf(Accent, Color.White, Warn, Accent)

/** Vorberechnete Partikel (Winkel, Tempo, Groesse, Farbe): pro Zeichnen keine Allokationen. */
private class Particles(count: Int, seed: Long, upward: Boolean) {
    val angle = FloatArray(count)
    val speed = FloatArray(count)
    val size = FloatArray(count)
    val color = IntArray(count)
    val phase = FloatArray(count)

    init {
        val r = Random(seed)
        for (i in 0 until count) {
            // nach oben verlaufend: Faecher von -160 bis -20 Grad; sonst rundum
            angle[i] = if (upward) (-160f + r.nextFloat() * 140f) * (Math.PI.toFloat() / 180f) else r.nextFloat() * 6.2832f
            speed[i] = 0.45f + r.nextFloat() * 0.55f
            size[i] = 2.5f + r.nextFloat() * 3.5f
            color[i] = r.nextInt(Palette.size)
            phase[i] = r.nextFloat()
        }
    }
}

/** Partikel-Burst aus der Mitte nach oben; [p] (0..1) wird erst beim Zeichnen gelesen, damit nichts neu komponiert wird. */
@Composable
private fun Burst(p: Animatable<Float, *>, count: Int, seed: Long, modifier: Modifier = Modifier) {
    val particles = remember(count, seed) { Particles(count, seed, upward = true) }
    Canvas(modifier) {
        val t = p.value
        val reach = size.minDimension * 0.55f
        val cx = size.width / 2
        val cy = size.height * 0.42f
        val ease = 1f - (1f - t) * (1f - t)
        val alpha = (1f - t).coerceIn(0f, 1f).pow(0.7f)
        for (i in particles.angle.indices) {
            val d = particles.speed[i] * reach * ease
            val x = cx + cos(particles.angle[i]) * d
            val y = cy + sin(particles.angle[i]) * d + t * t * reach * 0.25f // leichte Schwerkraft
            drawCircle(Palette[particles.color[i]].copy(alpha = alpha), particles.size[i].dp.toPx() * (1f - t * 0.4f), Offset(x, y))
        }
    }
}

/** Konfetti: fallende Plaettchen ueber die ganze Breite, kurz. */
@Composable
fun Confetti(trigger: Any?, modifier: Modifier = Modifier) {
    val p = remember { Animatable(0f) }
    val particles = remember { Particles(FeedbackTuning.CONFETTI, 7L, upward = false) }
    LaunchedEffect(trigger) {
        p.snapTo(0f)
        p.animateTo(1f, tween(2400))
    }
    Canvas(modifier.fillMaxSize()) {
        val t = p.value
        if (t >= 1f) return@Canvas
        for (i in particles.angle.indices) {
            val x = particles.phase[i] * size.width + sin(t * 9f + particles.phase[i] * 20f) * 18.dp.toPx()
            val y = -20.dp.toPx() + (t * (0.6f + particles.speed[i]) * (size.height + 40.dp.toPx()))
            val a = (1f - t * t).coerceIn(0f, 1f)
            val w = particles.size[i].dp.toPx()
            drawRoundRect(Palette[particles.color[i]].copy(alpha = a), Offset(x, y), Size(w * 1.6f, w), CornerRadius(w / 3))
        }
    }
}

/**
 * Feier bei einer richtigen Antwort: gruener Puls, Haken mit Pop, Partikel-Burst, schwebende Punkte, bei Meilensteinen
 * zusaetzlich "N in Folge", beim Comeback ein leiser Zusatz. Beruehrungen gehen durch.
 */
@Composable
fun CorrectFx(fx: Fx, modifier: Modifier = Modifier) {
    val outcome = fx.outcome
    val p = remember { Animatable(0f) }
    val pop = remember { Animatable(0f) }
    val milestone = outcome.celebration != Celebration.Small
    LaunchedEffect(fx.id) {
        p.snapTo(0f)
        pop.snapTo(0f)
        pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium))
    }
    LaunchedEffect(fx.id) {
        p.animateTo(1f, tween(if (outcome.celebration == Celebration.Big) 1800 else if (milestone) 1400 else 900))
    }
    val count = when (outcome.celebration) {
        Celebration.Small -> FeedbackTuning.BURST_SMALL
        Celebration.Medium -> FeedbackTuning.BURST_MEDIUM
        Celebration.Big -> FeedbackTuning.BURST_BIG
    }
    Box(modifier.fillMaxSize()) {
        // gruener Puls
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.2f * (1f - p.value).pow(2f) }.background(Accent))
        Burst(p, count, fx.id.toLong(), Modifier.fillMaxSize())
        // Haken mit Pop
        Canvas(Modifier.align(Alignment.Center).size(72.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = (1f - p.value * p.value).coerceIn(0f, 1f) }) {
            val u = size.width / 24f
            drawCircle(Accent, radius = 11f * u)
            val check = Path().apply { moveTo(7f * u, 12.5f * u); lineTo(10.5f * u, 16f * u); lineTo(17f * u, 8.5f * u) }
            drawPath(check, Color.Black, style = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // schwebende Punkte
        if (outcome.xp > 0) {
            Text(
                "+${outcome.xp}", color = Accent, style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { translationY = -(48.dp.toPx() + 110.dp.toPx() * p.value); alpha = (1f - p.value * p.value * p.value).coerceIn(0f, 1f) },
            )
        }
        if (milestone) {
            Text(
                "${outcome.streak} in Folge", color = Color.White, style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.align(Alignment.Center).graphicsLayer {
                    translationY = 90.dp.toPx() - 30.dp.toPx() * p.value
                    val s = 0.7f + 0.3f * pop.value
                    scaleX = s; scaleY = s
                    alpha = (1f - p.value * p.value).coerceIn(0f, 1f)
                },
            )
        } else if (outcome.comeback) {
            Text(
                "Comeback", color = Accent, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { translationY = 64.dp.toPx(); alpha = (1f - p.value).coerceIn(0f, 1f) },
            )
        }
    }
}

/** Sanfter, kurzer warmer Schimmer bei einer falschen Antwort (nie hart). */
@Composable
fun SoftFx(fx: Fx, modifier: Modifier = Modifier) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(fx.id) {
        p.snapTo(0f)
        p.animateTo(1f, tween(600))
    }
    Box(modifier.fillMaxSize().graphicsLayer { alpha = 0.10f * (1f - p.value) }.background(Soft))
}

/** Banner "Thema sitzt: <Titel>" mit kleinem Burst; verschwindet nach 2,5 s von selbst. */
@Composable
fun SitsBanner(sits: Sits, modifier: Modifier = Modifier) {
    val p = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(sits.id) {
        visible = true
        p.snapTo(0f)
        p.animateTo(1f, tween(1500))
    }
    LaunchedEffect(sits.id) {
        delay(2500)
        visible = false
    }
    val slide by animateFloatAsState(if (visible) 1f else 0f, tween(280), label = "sitsSlide")
    if (slide <= 0.01f) return
    Box(modifier.fillMaxWidth().height(110.dp)) {
        Burst(p, FeedbackTuning.BURST_SMALL, sits.id * 31L, Modifier.fillMaxSize())
        Text(
            "Thema sitzt: ${sits.title}", style = MaterialTheme.typography.titleSmall, maxLines = 1,
            modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { translationY = -40.dp.toPx() * (1f - slide); alpha = slide }
                .clip(RoundedCornerShape(50)).background(Card).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/** Serienzaehler "5 in Folge" mit Blitz, animiert hochzaehlend; erst ab 2 in Folge sichtbar. */
@Composable
fun StreakChip(streak: Int, modifier: Modifier = Modifier) {
    if (streak < FeedbackTuning.STREAK_VISIBLE_FROM) return
    val shown by animateIntAsState(streak, tween(300), label = "streakCount")
    val bump = remember { Animatable(1f) }
    LaunchedEffect(streak) {
        bump.snapTo(1.3f)
        bump.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
    }
    Row(modifier.graphicsLayer { scaleX = bump.value; scaleY = bump.value }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Bolt()
        Text("$shown in Folge", color = Accent, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp))
    }
}

/** Kleiner Blitz, gezeichnet (kein Emoji). */
@Composable
private fun Bolt() {
    Canvas(Modifier.size(14.dp)) {
        val u = size.width / 14f
        val path = Path().apply {
            moveTo(8.5f * u, 0f); lineTo(2f * u, 8f * u); lineTo(6.5f * u, 8f * u); lineTo(5.5f * u, 14f * u); lineTo(12f * u, 5.5f * u); lineTo(7.5f * u, 5.5f * u); close()
        }
        drawPath(path, Accent)
    }
}

/** Fortschrittsbalken mit weichem Leuchten an der Fuellung. */
@Composable
fun GlowProgress(progress: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(350), label = "progress")
    Canvas(modifier.height(14.dp)) {
        val h = 6.dp.toPx()
        val top = (size.height - h) / 2
        val r = CornerRadius(h / 2)
        drawRoundRect(CardHigh, Offset(0f, top), Size(size.width, h), r)
        val w = size.width * shown
        if (w > 0f) {
            drawRoundRect(Accent.copy(alpha = 0.22f), Offset(0f, top - 4.dp.toPx()), Size(w, h + 8.dp.toPx()), CornerRadius((h + 8.dp.toPx()) / 2))
            drawRoundRect(Accent, Offset(0f, top), Size(w, h), r)
        }
    }
}
