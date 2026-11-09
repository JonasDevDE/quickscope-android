package com.jonasschnell.quickscope.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.ui.graphics.graphicsLayer
import com.jonasschnell.quickscope.logic.MatchResult
import com.jonasschnell.quickscope.logic.soundForMatch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.jonasschnell.quickscope.logic.CardFlow
import com.jonasschnell.quickscope.logic.MatchState
import com.jonasschnell.quickscope.logic.OrderState
import com.jonasschnell.quickscope.logic.Task
import com.jonasschnell.quickscope.logic.correctText
import com.jonasschnell.quickscope.logic.lessonSteps
import com.jonasschnell.quickscope.logic.shortExplanation
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Soft
import com.jonasschnell.quickscope.ui.theme.Muted
import com.jonasschnell.quickscope.ui.theme.OnAccent

@Composable
private fun QuestionText(text: String) {
    val hl = LocalHighlight.current
    // Nur hervorheben, wenn der Text exakt dem gesprochenen Text entspricht; sonst ganz normal anzeigen
    val shown = if (hl.text == text && hl.words.isNotEmpty()) {
        buildAnnotatedString {
            append(text)
            hl.words.forEachIndexed { i, w ->
                when {
                    i == hl.active -> addStyle(SpanStyle(color = Accent, background = Accent.copy(alpha = 0.18f)), w.start, w.end)
                    i < hl.spoken -> addStyle(SpanStyle(color = Color.White.copy(alpha = 0.55f)), w.start, w.end)
                }
            }
        }
    } else {
        AnnotatedString(text)
    }
    Text(shown, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
}

/** Eine grosse Antwortflaeche; nach der Antwort gruen (richtig) bzw. rot (gewaehlt, falsch) eingefaerbt. */
@Composable
private fun AnswerBox(text: String, state: Int, onClick: () -> Unit, enabled: Boolean = true) {
    // state: 0 neutral, 1 richtig, 2 falsch, 3 gedimmt
    val border = when (state) {
        1 -> BorderStroke(2.dp, Accent)
        2 -> BorderStroke(2.dp, Soft)
        else -> null
    }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(24.dp)).background(if (state == 3) Card else CardHigh)
            .let { if (border != null) it.border(border, RoundedCornerShape(24.dp)) else it }
            .hapticClickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = if (state == 3) Muted else Color.White)
    }
}

/** Karte: Tipp zum Aufdecken, danach zwei grosse Knoepfe "Gewusst" / "Nicht gewusst". */
@Composable
fun CardTask(task: Task.Card, onAnswer: (Boolean) -> Unit) {
    val flow = remember { CardFlow() }
    var shown by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        QuestionText(task.item.question)
        if (!shown) {
            AnswerBox("Aufdecken", 0, { flow.reveal(); shown = true })
        } else {
            Text(task.answer, style = MaterialTheme.typography.titleLarge, color = Accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            task.item.mnemonic?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            shortExplanation(task.item.explanation)?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { SecondaryButton("Nicht gewusst", { flow.answer(false)?.let { onAnswer(false) } }, color = Soft) }
                Box(Modifier.weight(1f)) { PrimaryButton("Gewusst", { flow.answer(true)?.let { onAnswer(true) } }) }
            }
        }
    }
}

/** Loesungsweg: EINE ruhige Karte mit hoechstens 3 Schritten und optionaler Merkregel. */
@Composable
fun LessonTask(task: Task.Lesson, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(task.item.question, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        CardColumn(verticalSpacing = 12.dp) {
            task.steps.forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("${i + 1}", color = Accent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 14.dp))
                    Text(step, style = MaterialTheme.typography.bodyLarge)
                }
            }
            task.trick?.let { Text("Merke: $it", color = Accent, style = MaterialTheme.typography.bodyMedium) }
        }
        PrimaryButton("Weiter", onDone)
    }
}

/** mc, choice2, number, truefalse: Optionen als grosse Flaechen; ein Tipp antwortet. Bei zwei Optionen auch Wisch. */
@Composable
fun ChoiceTask(task: Task.Choice, answered: Boolean, revealed: Boolean, onAnswer: (Boolean) -> Unit) {
    var chosen by remember { mutableIntStateOf(-1) }
    fun pick(i: Int) {
        if (answered || chosen >= 0) return
        chosen = i
        onAnswer(i == task.correct)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QuestionText(task.item.question)
        task.options.forEachIndexed { i, text ->
            val state = when {
                chosen < 0 && !revealed -> 0
                i == task.correct -> 1
                i == chosen -> 2
                else -> 3
            }
            AnswerBox(text, state, { pick(i) })
        }
    }
}

/** Lueckentext: der Satz mit Luecke, darunter die Wortbank als Chips. */
@Composable
fun ClozeTask(task: Task.Cloze, revealed: Boolean, onAnswer: (Boolean) -> Unit) {
    var chosen by remember { mutableStateOf<String?>(null) }
    val filled = chosen ?: "_____"
    val text = if (task.text.contains("___")) task.text.replaceFirst(Regex("_{3,}"), filled) else "${task.text} $filled"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        QuestionText(text)
        task.bank.forEach { word ->
            val state = when {
                chosen == null && !revealed -> 0
                word == task.answer -> 1
                word == chosen -> 2
                else -> 3
            }
            AnswerBox(word, state, { if (chosen == null) { chosen = word; onAnswer(word == task.answer) } })
        }
    }
}

/** Fehler finden: Aussagen als Flaechen, Tipp auf die falsche. */
@Composable
fun SpotTask(task: Task.Spot, revealed: Boolean, onAnswer: (Boolean) -> Unit) {
    var chosen by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QuestionText(task.item.question.ifBlank { "Was ist falsch?" })
        task.statements.forEachIndexed { i, text ->
            val state = when {
                chosen < 0 && !revealed -> 0
                i == task.falseIndex -> 1
                i == chosen -> 2
                else -> 3
            }
            AnswerBox(text, state, { if (chosen < 0) { chosen = i; onAnswer(i == task.falseIndex) } })
        }
    }
}

private val PairColors = arrayOf(
    Color(0xFFD4FF3A), Color(0xFF5EC8FF), Color(0xFFFF9F5A), Color(0xFFC59BFF), Color(0xFFFF7EB6), Color(0xFF7CFFB2),
)

/**
 * Zuordnen per Tippen: links waehlen, dann rechts; jedes Paar wird sofort ausgewertet. Richtige Paare bleiben verbunden
 * (je Paar eine eigene Farbe, mit Pling), falsche loesen sich mit kurzem Wackeln wieder (ohne Ton). Nach zwei Fehlversuchen
 * wird das richtige Paar gezeigt. Alles passt ohne Scrollen und ohne Layoutsprung.
 */
@Composable
fun MatchTask(task: Task.Match, revealed: Boolean, onAnswer: (Boolean) -> Unit) {
    val state = remember { MatchState.of(task) }
    var version by remember { mutableIntStateOf(0) }
    val haptics = LocalHaptics.current
    val sfx = rememberSfx()
    val shake = remember { Animatable(0f) }
    var wrongLeft by remember { mutableStateOf<String?>(null) }
    var wrongRight by remember { mutableStateOf<String?>(null) }
    var wrongTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(revealed) { if (revealed) { state.revealAll(); version++ } }
    LaunchedEffect(wrongTick) {
        if (wrongTick == 0) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(0f, keyframes { durationMillis = 300; 0f at 0; -10f at 50; 9f at 110; -6f at 170; 3f at 230; 0f at 300 })
        wrongLeft = null
        wrongRight = null
    }
    @Suppress("UNUSED_EXPRESSION") version
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QuestionText(task.item.question.ifBlank { "Zuordnen" })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.lefts.forEach { left ->
                    val c = state.colorOf(left)
                    MatchCell(
                        left, if (c >= 0) PairColors[c % PairColors.size] else null, state.selectedLeft == left, wrongLeft == left, shake,
                    ) { if (!state.done) { state.tapLeft(left); version++ } }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.rights.forEach { right ->
                    val c = state.colorOfRight(right)
                    MatchCell(right, if (c >= 0) PairColors[c % PairColors.size] else null, false, wrongRight == right, shake) {
                        if (state.done) return@MatchCell
                        val left = state.selectedLeft
                        val result = state.tapRight(right)
                        // das letzte Paar loest ohnehin das Pling der ganzen Aufgabe aus
                        if (!state.done) soundForMatch(result)?.let(sfx::play)
                        when (result) {
                            MatchResult.Right -> haptics.confirm()
                            MatchResult.Revealed -> haptics.tick() // Paar wird gezeigt, ohne Ton
                            MatchResult.Wrong -> { haptics.tick(); wrongLeft = left; wrongRight = right; wrongTick++ }
                            MatchResult.None -> Unit
                        }
                        version++
                        if (state.done) onAnswer(state.known)
                    }
                }
            }
        }
    }
}

/** Eine Zelle: [pairColor] gesetzt = Teil eines verbundenen Paares; [wrong] = kurzes Wackeln mit warmem Hauch. */
@Composable
private fun MatchCell(text: String, pairColor: Color?, selected: Boolean, wrong: Boolean, shake: Animatable<Float, *>, onClick: () -> Unit) {
    val bg = when {
        pairColor != null -> pairColor
        wrong -> Soft.copy(alpha = 0.35f)
        else -> CardHigh
    }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .graphicsLayer { translationX = if (wrong) shake.value else 0f }
            .clip(RoundedCornerShape(20.dp)).background(bg)
            .let { if (selected) it.border(BorderStroke(2.dp, Accent), RoundedCornerShape(20.dp)) else it }
            .hapticClickable(enabled = pairColor == null, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, color = if (pairColor != null) Color.Black else Color.White)
    }
}

/** Reihenfolge per Tippen: die Schritte der Reihe nach antippen. */
@Composable
fun OrderTask(task: Task.Order, revealed: Boolean, onAnswer: (Boolean) -> Unit) {
    val state = remember { OrderState.of(task) }
    var version by remember { mutableIntStateOf(0) }
    val haptics = LocalHaptics.current
    LaunchedEffect(revealed) { if (revealed) { state.revealAll(); version++ } }
    @Suppress("UNUSED_EXPRESSION") version
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QuestionText(task.item.question.ifBlank { "Reihenfolge" })
        state.shuffled.forEach { step ->
            val taken = state.isTaken(step)
            val number = if (taken) state.steps.indexOf(step) + 1 else null
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(24.dp)).background(if (taken) Accent else CardHigh)
                    .hapticClickable(enabled = !taken && !state.done) {
                        if (state.tap(step)) haptics.tick() else haptics.reject()
                        version++
                        if (state.done) onAnswer(state.mistakes == 0)
                    }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (number != null) Text("$number", color = OnAccent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 14.dp))
                Text(step, style = MaterialTheme.typography.titleMedium, color = if (taken) OnAccent else Color.White)
            }
        }
    }
}

/**
 * Einsortieren: das aktuelle Element gross, darunter 2 bis 4 grosse Kategorie-Knoepfe. Tipp auf eine Kategorie wertet sofort aus: richtig
 * (Pling) bleibt dort stehen und das naechste Element kommt, falsch wackelt ohne Ton; nach zwei Fehlversuchen wird die richtige Kategorie gezeigt.
 */
@Composable
fun BucketTask(task: Task.Bucket, revealed: Boolean, onAnswer: (Boolean, kotlinx.serialization.json.JsonElement?) -> Unit) {
    val state = remember { com.jonasschnell.quickscope.logic.BucketState.of(task) }
    var version by remember { mutableIntStateOf(0) }
    val haptics = LocalHaptics.current
    val sfx = rememberSfx()
    val shake = remember { Animatable(0f) }
    var wrongCategory by remember { mutableStateOf<String?>(null) }
    var wrongTick by remember { mutableIntStateOf(0) }
    fun payload() = kotlinx.serialization.json.JsonObject(state.placed.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
    LaunchedEffect(revealed) { if (revealed) { state.revealAll(); version++ } }
    LaunchedEffect(wrongTick) {
        if (wrongTick == 0) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(0f, keyframes { durationMillis = 300; 0f at 0; -10f at 50; 9f at 110; -6f at 170; 3f at 230; 0f at 300 })
        wrongCategory = null
    }
    @Suppress("UNUSED_EXPRESSION") version
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QuestionText(task.item.question.ifBlank { "Einsortieren" })
        Box(
            Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black)
                .border(BorderStroke(1.dp, com.jonasschnell.quickscope.ui.theme.Outline), RoundedCornerShape(24.dp)).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(state.current?.text ?: "Geschafft", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, color = if (state.done) Accent else Color.White)
        }
        Text("${minOf(state.index + 1, state.entries.size)} von ${state.entries.size}", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        task.categories.forEach { (id, title) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MatchCell(title, null, false, wrongCategory == id, shake) {
                    if (state.done) return@MatchCell
                    val result = state.tap(id)
                    if (!state.done) com.jonasschnell.quickscope.logic.soundForBucket(result)?.let(sfx::play)
                    when (result) {
                        com.jonasschnell.quickscope.logic.BucketResult.Right -> haptics.confirm()
                        com.jonasschnell.quickscope.logic.BucketResult.Revealed -> haptics.tick()
                        com.jonasschnell.quickscope.logic.BucketResult.Wrong -> { haptics.tick(); wrongCategory = id; wrongTick++ }
                        null -> Unit
                    }
                    version++
                    if (state.done) onAnswer(state.known, payload())
                }
                val inside = state.placedIn(id)
                if (inside.isNotEmpty()) Text(inside.joinToString(" · ") { it.text }, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}

/**
 * Satz bauen: Antwortzeile oben, gemischte Bausteine unten. Tipp setzt in die Zeile, Tipp in der Zeile nimmt zurueck, "Pruefen" wertet aus:
 * richtig = gruen und fertig, falsch = kurzes Wackeln; nach zwei Versuchen wird die richtige Reihenfolge gezeigt.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BuildTask(task: Task.Build, revealed: Boolean, onAnswer: (Boolean, kotlinx.serialization.json.JsonElement?) -> Unit) {
    val state = remember { com.jonasschnell.quickscope.logic.BuildState.of(task) }
    var version by remember { mutableIntStateOf(0) }
    val haptics = LocalHaptics.current
    val shake = remember { Animatable(0f) }
    var wrongTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(revealed) { if (revealed) { state.revealAll(); version++ } }
    LaunchedEffect(wrongTick) {
        if (wrongTick == 0) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(0f, keyframes { durationMillis = 300; 0f at 0; -10f at 50; 9f at 110; -6f at 170; 3f at 230; 0f at 300 })
    }
    @Suppress("UNUSED_EXPRESSION") version
    val good = state.done && state.known
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        QuestionText(task.item.question.ifBlank { "Baue die Antwort" })
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).graphicsLayer { translationX = shake.value }.clip(RoundedCornerShape(24.dp)).background(Color.Black)
                .border(BorderStroke(if (good) 2.dp else 1.dp, if (good) Accent else com.jonasschnell.quickscope.ui.theme.Outline), RoundedCornerShape(24.dp)).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.row.forEachIndexed { pos, id ->
                BuildChip(state.textOf(id), if (good) Accent else com.jonasschnell.quickscope.ui.theme.Pressed, if (good) OnAccent else Color.White, !state.done) { state.tapRow(pos); haptics.tick(); version++ }
            }
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.shuffled.forEach { (id, text) ->
                val used = state.inRow(id)
                BuildChip(text, if (used) Card else CardHigh, if (used) Muted else Color.White, !used && !state.done) { state.tapChip(id); haptics.tick(); version++ }
            }
        }
        PrimaryButton("Prüfen", {
            val result = state.check() ?: return@PrimaryButton
            when (result) {
                com.jonasschnell.quickscope.logic.BuildResult.Right -> haptics.confirm()
                com.jonasschnell.quickscope.logic.BuildResult.Revealed -> haptics.tick()
                com.jonasschnell.quickscope.logic.BuildResult.Wrong -> { haptics.tick(); wrongTick++ }
            }
            version++
            if (state.done) onAnswer(state.known, kotlinx.serialization.json.JsonArray(state.answerOrder().map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }, enabled = state.complete && !state.done)
    }
}

@Composable
private fun BuildChip(text: String, bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(bg)
            .border(BorderStroke(1.dp, com.jonasschnell.quickscope.ui.theme.Outline), RoundedCornerShape(24.dp))
            .hapticClickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}
