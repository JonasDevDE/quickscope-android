package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.Item
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Random

/** Aufbereitete Aufgabe fuer die Anzeige; unlesbare Payloads fallen auf eine einfache Karte zurueck. */
sealed interface Task {
    val item: Item

    /** Karte: Frage, aufdecken, dann "wusste ich"/"nicht gewusst". */
    data class Card(override val item: Item, val answer: String) : Task
    /** mc, choice2, number, truefalse: eine richtige Option. */
    data class Choice(override val item: Item, val options: List<String>, val correct: Int) : Task
    data class Cloze(override val item: Item, val text: String, val bank: List<String>, val answer: String) : Task
    data class Spot(override val item: Item, val statements: List<String>, val falseIndex: Int) : Task
    data class Match(override val item: Item, val pairs: List<Pair<String, String>>) : Task
    data class Order(override val item: Item, val steps: List<String>) : Task
    /** Loesungsweg: ein ruhiger Kartenblock mit hoechstens 3 Schritten und optionaler Merkregel. */
    data class Lesson(override val item: Item, val steps: List<String>, val trick: String?) : Task
    /** Lektion (Hoeren vor Abfragen): Titel und Stichworte, Lektion wird abgespielt. */
    data class Teach(override val item: Item, val lesson: com.jonasschnell.quickscope.data.SessionLesson) : Task
}

private fun JsonObject.strings(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** Hoechstens 3 Schritte fuer Loesungswege. */
fun lessonSteps(item: Item): List<String> = (item.solutionSteps ?: emptyList()).filter { it.isNotBlank() }.take(3)

fun parseTask(item: Item): Task {
    val p = item.payload
    val fallback = Task.Card(item, item.answer)
    if (item.lesson) {
        val steps = lessonSteps(item)
        if (steps.isNotEmpty()) return Task.Lesson(item, steps, item.trick)
    }
    return when (item.type) {
        "card" -> fallback
        "mc", "choice2", "number" -> {
            val options = item.options ?: p?.strings("options")
            val correct = item.correctIndex ?: p?.int("correctIndex")
            if (options != null && options.size >= 2 && correct != null && correct in options.indices) Task.Choice(item, options, correct) else fallback
        }
        "truefalse" -> Task.Choice(item, listOf("Wahr", "Falsch"), if (item.answer.trim().lowercase() == "wahr") 0 else 1)
        "cloze" -> {
            val text = p?.string("text") ?: item.question
            val bank = p?.strings("bank")
            val answer = p?.string("answer") ?: item.answer
            if (bank != null && bank.size >= 2 && answer in bank) Task.Cloze(item, text, bank, answer) else fallback
        }
        "spot" -> {
            val statements = p?.strings("statements")
            val idx = p?.int("falseIndex")
            if (statements != null && statements.size >= 2 && idx != null && idx in statements.indices) Task.Spot(item, statements, idx) else fallback
        }
        "match" -> {
            val pairs = (p?.get("pairs") as? JsonArray)?.mapNotNull {
                val o = runCatching { it.jsonObject }.getOrNull() ?: return@mapNotNull null
                val l = o["left"]?.jsonPrimitive?.contentOrNull
                val r = o["right"]?.jsonPrimitive?.contentOrNull
                if (l != null && r != null) l to r else null
            }
            if (pairs != null && pairs.size >= 2) Task.Match(item, pairs) else fallback
        }
        "order" -> {
            val steps = p?.strings("steps")
            if (steps != null && steps.size >= 2) Task.Order(item, steps) else fallback
        }
        else -> fallback
    }
}

/** Antwort-Ergebnis fuer das Backend: Karten und Loesungswege known/unknown, alles andere right/wrong. */
fun outcomeFor(task: Task, correct: Boolean): String = when (task) {
    is Task.Card, is Task.Lesson, is Task.Teach -> if (correct) "known" else "unknown"
    else -> if (correct) "right" else "wrong"
}

/** Stabile Mischung je Aufgabe, damit die Reihenfolge beim Neuzeichnen gleich bleibt (und nie schon geloest ist). */
fun <T> shuffledFor(item: Item, list: List<T>): List<T> {
    if (list.size < 2) return list
    val rnd = Random(item.id.hashCode().toLong())
    var out = list.shuffled(rnd)
    var tries = 0
    while (out == list && tries++ < 5) out = list.shuffled(rnd)
    return out
}

/** Ergebnis eines Tipps auf die rechte Seite einer Zuordnung. */
enum class MatchResult { None, Right, Wrong, Revealed }

/**
 * Zuordnung per Tippen: links waehlen, rechts waehlen. Jedes Paar wird sofort ausgewertet: richtig bleibt verbunden (jedes Paar
 * mit eigener Farbe, [colorOf]), falsch loest sich wieder und zaehlt als Fehler. Nach [MAX_TRIES] Fehlversuchen bei demselben
 * Begriff wird das richtige Paar angezeigt ([revealed]); dann gilt die Aufgabe als nicht gewusst.
 */
class MatchState(val lefts: List<String>, val rights: List<String>, private val solution: Map<String, String>) {
    val matched = mutableSetOf<String>()
    val revealed = mutableSetOf<String>()
    private val tries = HashMap<String, Int>()
    private val colors = HashMap<String, Int>()
    var selectedLeft: String? = null
        private set
    var mistakes = 0
        private set
    val done get() = matched.size == lefts.size

    /** Gewuenscht: ohne Fehler und ohne aufgedecktes Paar. */
    val known get() = mistakes == 0 && revealed.isEmpty()

    fun tapLeft(left: String) {
        if (left !in matched) selectedLeft = if (selectedLeft == left) null else left
    }

    fun tapRight(right: String): MatchResult {
        val left = selectedLeft ?: return MatchResult.None
        selectedLeft = null
        if (solution[left] == right) {
            connect(left)
            return MatchResult.Right
        }
        mistakes++
        val n = (tries[left] ?: 0) + 1
        tries[left] = n
        if (n >= MAX_TRIES) {
            revealed.add(left)
            connect(left)
            return MatchResult.Revealed
        }
        return MatchResult.Wrong
    }

    private fun connect(left: String) {
        matched.add(left)
        colors[left] = colors.size
    }

    /** Farbnummer des Paares (in der Reihenfolge des Verbindens), -1 wenn noch nicht verbunden. */
    fun colorOf(left: String): Int = colors[left] ?: -1

    fun colorOfRight(right: String): Int = matched.firstOrNull { solution[it] == right }?.let { colorOf(it) } ?: -1

    /** "Weiss nicht": alle Paare als richtig zeigen. */
    fun revealAll() {
        for (l in lefts) if (l !in matched) connect(l)
        selectedLeft = null
    }

    fun isRightMatched(right: String) = matched.any { solution[it] == right }

    companion object {
        const val MAX_TRIES = 2

        fun of(task: Task.Match) = MatchState(
            shuffledFor(task.item, task.pairs.map { it.first }),
            shuffledFor(task.item, task.pairs.map { it.second }),
            task.pairs.toMap(),
        )
    }
}

/** Reihenfolge per Tippen: der Reihe nach den naechsten Schritt waehlen; jeder falsche Tipp zaehlt als Fehler. */
class OrderState(val steps: List<String>, val shuffled: List<String>) {
    var next = 0
        private set
    var mistakes = 0
        private set
    val done get() = next >= steps.size

    /** true = richtiger Schritt, false = falsch. */
    fun tap(step: String): Boolean {
        if (done) return false
        return if (steps[next] == step) {
            next++
            true
        } else {
            mistakes++
            false
        }
    }

    /** "Weiss nicht": die richtige Reihenfolge zeigen. */
    fun revealAll() {
        next = steps.size
    }

    fun isTaken(step: String) = steps.take(next).contains(step)

    companion object {
        fun of(task: Task.Order) = OrderState(task.steps, shuffledFor(task.item, task.steps))
    }
}

/** Hoechstens zwei kurze Saetze der Erklaerung. */
fun shortExplanation(text: String?): String? {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return null
    val sentences = Regex("(?<=[.!?])\\s+").split(t)
    return sentences.take(2).joinToString(" ")
}

/** Die richtige Antwort als kurzer Text fuer das Feedback. */
fun correctText(task: Task): String = when (task) {
    is Task.Card -> task.answer
    is Task.Choice -> task.options[task.correct]
    is Task.Cloze -> task.answer
    is Task.Spot -> task.statements[task.falseIndex]
    is Task.Match -> task.pairs.joinToString("; ") { "${it.first} = ${it.second}" }
    is Task.Order -> task.steps.joinToString(" → ")
    is Task.Lesson, is Task.Teach -> ""
}

/** Anteil der Runde, der geschafft ist (nach geschaetzter Zeit); fuer den Zeitbalken oben. */
fun sessionProgress(items: List<Item>, doneCount: Int): Float {
    val total = items.sumOf { it.estSeconds.coerceAtLeast(1) }
    if (total == 0) return 0f
    return items.take(doneCount).sumOf { it.estSeconds.coerceAtLeast(1) }.toFloat() / total
}

/** Vorlesetext der Geraetestimme: nur die Frage, nie die Antwortmoeglichkeiten. Eine Luecke wird nie als Wort gesprochen, sondern ist eine kurze Pause (Komma). */
fun audioPrompt(task: Task): String =
    if (task is Task.Cloze) task.item.question.replace(Regex("_{3,}"), ",") else task.item.question

/** Ablauf einer Karte ohne Wisch: erst aufdecken, dann "gewusst"/"nicht gewusst"; vorher gibt es kein Ergebnis. */
class CardFlow {
    var revealed = false
        private set

    fun reveal() {
        revealed = true
    }

    /** Ergebnis fuer das Backend ("known"/"unknown") oder null, solange die Karte nicht aufgedeckt ist. */
    fun answer(known: Boolean): String? = if (!revealed) null else if (known) "known" else "unknown"
}

/** Synthetisches Item fuer ein Lektions-Element der Session (Zeitbalken nutzt estSeconds). */
fun teachItem(lesson: com.jonasschnell.quickscope.data.SessionLesson): Item =
    Item(id = "lesson-${lesson.id}", type = "lesson", question = lesson.title, estSeconds = lesson.estSeconds)

/** Element der Session als Anzeige-Aufgabe: Lektion nur bei `type == "lesson"` (Lektionskarte), sonst eine Aufgabe. */
fun taskOf(entry: com.jonasschnell.quickscope.data.SessionEntry): Task? = when {
    entry.lesson != null -> Task.Teach(teachItem(entry.lesson), entry.lesson)
    entry.item != null -> parseTask(entry.item)
    else -> null
}

/** Ablauf "Weiss nicht": neutral, zeigt nur die richtige Antwort, zaehlt nicht fuer die Serie-Anzeige. */
fun dontKnowOutcome(): String = "dontknow"

/** Wo eine nachgereichte Lektion eingefuegt wird: direkt nach der aktuellen Aufgabe, nie doppelt. */
fun lessonInsertIndex(current: Int, tasks: List<Task>, lessonId: String): Int? {
    val already = tasks.drop(current + 1).any { it is Task.Teach && it.lesson.id == lessonId }
    return if (already) null else current + 1
}
