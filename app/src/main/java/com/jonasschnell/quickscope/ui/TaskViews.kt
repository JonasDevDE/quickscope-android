package com.jonasschnell.quickscope.ui

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
import com.jonasschnell.quickscope.ui.theme.Danger
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
        2 -> BorderStroke(2.dp, Danger)
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
fun CardTask(task: Task.Card, onAnswer: (Boolean) -> Unit, onReveal: (String) -> Unit) {
    val flow = remember { CardFlow() }
    var shown by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        QuestionText(task.item.question)
        if (!shown) {
            AnswerBox("Aufdecken", 0, { flow.reveal(); shown = true; onReveal(task.answer) })
        } else {
            Text(task.answer, style = MaterialTheme.typography.titleLarge, color = Accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            task.item.mnemonic?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            shortExplanation(task.item.explanation)?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { SecondaryButton("Nicht gewusst", { flow.answer(false)?.let { onAnswer(false) } }, color = Danger) }
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
fun ChoiceTask(task: Task.Choice, answered: Boolean, onAnswer: (Boolean) -> Unit) {
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
                chosen < 0 -> 0
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
fun ClozeTask(task: Task.Cloze, onAnswer: (Boolean) -> Unit) {
    var chosen by remember { mutableStateOf<String?>(null) }
    val filled = chosen ?: "_____"
    val text = if (task.text.contains("___")) task.text.replaceFirst(Regex("_{3,}"), filled) else "${task.text} $filled"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        QuestionText(text)
        task.bank.forEach { word ->
            val state = when {
                chosen == null -> 0
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
fun SpotTask(task: Task.Spot, onAnswer: (Boolean) -> Unit) {
    var chosen by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QuestionText(task.item.question.ifBlank { "Was ist falsch?" })
        task.statements.forEachIndexed { i, text ->
            val state = when {
                chosen < 0 -> 0
                i == task.falseIndex -> 1
                i == chosen -> 2
                else -> 3
            }
            AnswerBox(text, state, { if (chosen < 0) { chosen = i; onAnswer(i == task.falseIndex) } })
        }
    }
}

/** Zuordnen per Tippen: links waehlen, dann rechts; richtige Paare bleiben markiert. */
@Composable
fun MatchTask(task: Task.Match, onAnswer: (Boolean) -> Unit) {
    val state = remember { MatchState.of(task) }
    var version by remember { mutableIntStateOf(0) }
    val haptics = LocalHaptics.current
    @Suppress("UNUSED_EXPRESSION") version
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QuestionText(task.item.question.ifBlank { "Zuordnen" })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.lefts.forEach { left ->
                    val s = when {
                        left in state.matched -> 1
                        state.selectedLeft == left -> 2
                        else -> 0
                    }
                    MatchCell(left, s) {
                        if (!state.done) { state.tapLeft(left); version++ }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.rights.forEach { right ->
                    MatchCell(right, if (state.isRightMatched(right)) 1 else 0) {
                        if (state.done) return@MatchCell
                        when (state.tapRight(right)) {
                            true -> haptics.confirm()
                            false -> haptics.reject()
                            null -> Unit
                        }
                        version++
                        if (state.done) onAnswer(state.mistakes == 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchCell(text: String, state: Int, onClick: () -> Unit) {
    // state: 0 neutral, 1 gepaart, 2 gewaehlt
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(20.dp))
            .background(if (state == 1) Accent else CardHigh)
            .let { if (state == 2) it.border(BorderStroke(2.dp, Accent), RoundedCornerShape(20.dp)) else it }
            .hapticClickable(enabled = state != 1, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, color = if (state == 1) OnAccent else Color.White)
    }
}

/** Reihenfolge per Tippen: die Schritte der Reihe nach antippen. */
@Composable
fun OrderTask(task: Task.Order, onAnswer: (Boolean) -> Unit) {
    val state = remember { OrderState.of(task) }
    var version by remember { mutableIntStateOf(0) }
    val haptics = LocalHaptics.current
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

/** Sofortiges Feedback: "Richtig" bzw. "Falsch" + richtige Antwort kurz; Erklaerung nur wenn vorhanden, klein; ein Knopf "Weiter". */
@Composable
fun FeedbackPanel(task: Task, correct: Boolean, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Card).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (correct) "Richtig" else "Falsch", color = if (correct) Accent else Danger, style = MaterialTheme.typography.titleMedium)
        if (!correct) Text(correctText(task), style = MaterialTheme.typography.bodyLarge)
        shortExplanation(task.item.explanation)?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        // Loesungsweg (hoechstens 3 kurze Schritte) und Merkregel, wenn die Aufgabe einen mitbringt
        lessonSteps(task.item).forEachIndexed { i, step -> Text("${i + 1}. $step", color = Muted, style = MaterialTheme.typography.bodySmall) }
        task.item.trick?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodySmall) }
        task.item.mnemonic?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodySmall) }
        PrimaryButton("Weiter", onNext, Modifier.padding(top = 6.dp))
    }
}
