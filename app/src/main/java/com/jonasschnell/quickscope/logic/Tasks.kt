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
    /**
     * [leftOrder]/[rightOrder]: Spalten (Texte) in der Reihenfolge des Backends (payload.left/right, rechts gemischt mit Derangement) - bevorzugt;
     * fehlen sie (Altformat `pairs`), mischt der Client. [leftIds]/[rightIds]: Text -> id fuer die Antwort `{pairs:{leftId:rightId}}`.
     */
    data class Match(
        override val item: Item, val pairs: List<Pair<String, String>>, val leftOrder: List<String>? = null, val rightOrder: List<String>? = null,
        val leftIds: Map<String, String>? = null, val rightIds: Map<String, String>? = null,
    ) : Task
    /** Einsortieren: 2 bis 4 Kategorien (id, Titel), 4 bis 6 Elemente, die nacheinander einer Kategorie zugeordnet werden. */
    data class Bucket(override val item: Item, val categories: List<Pair<String, String>>, val entries: List<BucketEntry>) : Task
    /** Satz/Formel bauen: Bausteine (id, Text) und die richtige Reihenfolge der ids. */
    data class Build(override val item: Item, val tokens: List<Pair<String, String>>, val solutions: List<List<String>>) : Task {
        /** Die Hauptloesung (payload.solution); [solutions] enthaelt alle gueltigen Reihenfolgen. */
        val solution: List<String> get() = solutions.first()
    }
    /** [steps] in richtiger Reihenfolge; [shown] = Reihenfolge der Anzeige vom Backend (payload.items, gemischt); [ids] = Schritt-ids parallel zu [steps] fuer die Antwort `{order:[...]}`. */
    data class Order(override val item: Item, val steps: List<String>, val shown: List<String>? = null, val ids: List<String>? = null) : Task
    /** Loesungsweg: ein ruhiger Kartenblock mit hoechstens 3 Schritten und optionaler Merkregel. */
    data class Lesson(override val item: Item, val steps: List<String>, val trick: String?) : Task
    /** Lektion (Hoeren vor Abfragen): Titel und Stichworte, Lektion wird abgespielt. */
    data class Teach(override val item: Item, val lesson: com.jonasschnell.quickscope.data.SessionLesson) : Task
}

private fun JsonObject.strings(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

data class BucketEntry(val id: String, val text: String, val categoryId: String)

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
            if (statements != null && statements.size >= 2 && idx != null && idx in statements.indices) {
                // die falsche Aussage liegt im Backend oft immer an derselben Stelle: stabil mischen (seed = itemId) und falseIndex mitfuehren
                val order = shuffledFor(item, statements.indices.toList())
                Task.Spot(item, order.map { statements[it] }, order.indexOf(idx))
            } else fallback
        }
        "match" -> parseMatch(item, p) ?: fallback
        "bucket" -> parseBucket(item, p) ?: fallback
        "build" -> parseBuild(item, p) ?: fallback
        "order" -> parseOrder(item, p) ?: fallback
        else -> fallback
    }
}

private fun JsonObject.objects(key: String): List<JsonObject>? = (this[key] as? JsonArray)?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.idText(): Pair<String, String>? = (string("id") ?: return null) to (string("text") ?: return null)

/**
 * match: neu `payload.left:[{id,text}]`, `payload.right:[{id,text}]` (rechts gemischt, Derangement) und `payload.solution:{leftId:rightId}`;
 * Altformat `payload.pairs:[{left,right}]` (in Loesungsreihenfolge, der Client mischt).
 */
private fun parseMatch(item: Item, p: JsonObject?): Task.Match? {
    p ?: return null
    val lefts = p.objects("left")?.mapNotNull { it.idText() }
    val rights = p.objects("right")?.mapNotNull { it.idText() }
    val solution = p["solution"] as? JsonObject
    if (lefts != null && rights != null && solution != null && lefts.size >= 2) {
        val pairs = lefts.mapNotNull { (lid, ltext) ->
            val rid = solution[lid]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            rights.firstOrNull { it.first == rid }?.let { ltext to it.second }
        }
        if (pairs.size == lefts.size) {
            return Task.Match(item, pairs, lefts.map { it.second }, rights.map { it.second }, lefts.associate { it.second to it.first }, rights.associate { it.second to it.first })
        }
    }
    val pairs = (p["pairs"] as? JsonArray)?.mapNotNull {
        val o = runCatching { it.jsonObject }.getOrNull() ?: return@mapNotNull null
        val l = o["left"]?.jsonPrimitive?.contentOrNull
        val r = o["right"]?.jsonPrimitive?.contentOrNull
        if (l != null && r != null) l to r else null
    }
    return if (pairs != null && pairs.size >= 2) Task.Match(item, pairs) else null
}

/** order: neu `payload.items:[{id,text}]` (gemischt) und `payload.solution:[ids]`; Altformat `payload.steps` (Loesungsreihenfolge, der Client mischt). */
private fun parseOrder(item: Item, p: JsonObject?): Task.Order? {
    p ?: return null
    val items = p.objects("items")?.mapNotNull { it.idText() }
    val solution = p.strings("solution")
    if (items != null && solution != null && solution.size >= 2 && solution.all { id -> items.any { it.first == id } }) {
        return Task.Order(item, solution.map { id -> items.first { it.first == id }.second }, items.map { it.second }, solution)
    }
    val steps = p.strings("steps")
    return if (steps != null && steps.size >= 2) Task.Order(item, steps) else null
}

/** bucket: `payload.categories:[{id,label}]` und `payload.items:[{id,text,categoryId}]` (schon gemischt); unlesbar = null (Karte). */
private fun parseBucket(item: Item, p: JsonObject?): Task.Bucket? {
    p ?: return null
    val categories = (p.objects("categories") ?: return null).mapNotNull { o ->
        (o.string("id") ?: return@mapNotNull null) to (o.string("label") ?: return@mapNotNull null)
    }
    val entries = (p.objects("items") ?: return null).mapNotNull { o ->
        BucketEntry(o.string("id") ?: return@mapNotNull null, o.string("text") ?: return@mapNotNull null, o.string("categoryId") ?: return@mapNotNull null)
    }
    if (categories.size !in 2..4 || entries.size < 2 || entries.any { e -> categories.none { it.first == e.categoryId } }) return null
    return Task.Bucket(item, categories, entries)
}

/** build: `payload.tokens:[{id,text}]` (gemischt), `payload.solution:[ids]` und `payload.solutions:[[ids]]` (alle gueltigen Reihenfolgen). */
private fun parseBuild(item: Item, p: JsonObject?): Task.Build? {
    p ?: return null
    val tokens = p.objects("tokens")?.mapNotNull { it.idText() } ?: return null
    val solution = p.strings("solution") ?: return null
    val others = (p["solutions"] as? JsonArray)?.mapNotNull { el -> (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } } .orEmpty()
    val all = (listOf(solution) + others).filter { it.size >= 2 }.distinct()
    if (tokens.size < 2 || all.isEmpty() || all.any { sol -> sol.any { id -> tokens.none { it.first == id } } }) return null
    return Task.Build(item, tokens, all)
}

/**
 * Mischt [base] so, dass kein Element an seiner Ausgangsstelle bleibt (Derangement), stabil je Aufgabe (seed = itemId). Bei weniger
 * als 2 Elementen unveraendert; sonst immer eine echte Verschiebung (zur Not zyklisch).
 */
fun <T> derangedFor(item: Item, base: List<T>): List<T> {
    val n = base.size
    if (n < 2) return base
    val rnd = Random(item.id.hashCode().toLong() xor 0x5DEECE66DL)
    val idx = (0 until n).toList()
    var perm = idx.shuffled(rnd)
    var tries = 0
    while (perm.indices.any { perm[it] == it } && tries++ < 50) perm = idx.shuffled(rnd)
    if (perm.indices.any { perm[it] == it }) perm = idx.map { (it + 1) % n }
    return perm.map { base[it] }
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
    if (out == list) out = list.drop(1) + list.first()   // nie in Loesungsreihenfolge
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

    /** "Weiss nicht": alle Paare als richtig zeigen (dann wird keine Antwort mitgeschickt). */
    fun revealAll() {
        for (l in lefts) if (l !in matched) connect(l)
        selectedLeft = null
        dontKnow = true
    }

    var dontKnow = false
        private set

    /** Verbundene Paare (links -> rechts, jeweils Text). */
    fun connected(): Map<String, String> = matched.associateWith { solution.getValue(it) }

    fun isRightMatched(right: String) = matched.any { solution[it] == right }

    companion object {
        const val MAX_TRIES = 2

        /**
         * Spalten: vom Backend geliefert (bevorzugt), sonst links gemischt und rechts als Derangement dazu, damit kein rechtes Element
         * neben seinem linken Partner steht (frueher bekamen beide Spalten dieselbe Mischung und die Loesung lag nebeneinander).
         */
        fun of(task: Task.Match): MatchState {
            val solution = task.pairs.toMap()
            if (task.leftOrder != null && task.rightOrder != null) return MatchState(task.leftOrder, task.rightOrder, solution)
            val pairs = shuffledFor(task.item, task.pairs)
            return MatchState(pairs.map { it.first }, derangedFor(task.item, pairs.map { it.second }), solution)
        }
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

    /** "Weiss nicht": die richtige Reihenfolge zeigen (dann wird keine Antwort mitgeschickt). */
    fun revealAll() {
        next = steps.size
        revealedAll = true
    }

    var revealedAll = false
        private set

    fun isTaken(step: String) = steps.take(next).contains(step)

    companion object {
        fun of(task: Task.Order) = OrderState(task.steps, task.shown ?: shuffledFor(task.item, task.steps))
    }
}

/** Ergebnis eines Tipps auf eine Kategorie beim Einsortieren. */
enum class BucketResult { Right, Wrong, Revealed }

/**
 * Einsortieren: die Elemente kommen nacheinander, Tipp auf eine Kategorie wertet sofort aus. Richtig = bleibt in der Kategorie (Pling),
 * falsch = Wackeln ohne Ton; nach [MAX_TRIES] Fehlversuchen bei einem Element wird die richtige Zuordnung gezeigt.
 */
class BucketState(val categories: List<Pair<String, String>>, val entries: List<BucketEntry>) {
    val placed = LinkedHashMap<String, String>()
    var index = 0
        private set
    var mistakes = 0
        private set
    private var tries = 0
    var anyRevealed = false
        private set
    val current: BucketEntry? get() = entries.getOrNull(index)
    val done get() = index >= entries.size
    val known get() = mistakes == 0

    fun tap(categoryId: String): BucketResult? {
        val e = current ?: return null
        if (e.categoryId == categoryId) {
            placed[e.id] = categoryId
            index++
            tries = 0
            return BucketResult.Right
        }
        mistakes++
        tries++
        if (tries >= MAX_TRIES) {
            placed[e.id] = e.categoryId
            index++
            tries = 0
            anyRevealed = true
            return BucketResult.Revealed
        }
        return BucketResult.Wrong
    }

    /** "Weiss nicht": alles Uebrige richtig zeigen. */
    fun revealAll() {
        entries.drop(index).forEach { placed[it.id] = it.categoryId }
        index = entries.size
        anyRevealed = true
    }

    fun placedIn(categoryId: String): List<BucketEntry> = entries.filter { placed[it.id] == categoryId }

    companion object {
        const val MAX_TRIES = 2
        /** Die Elemente kommen vom Backend schon gemischt. */
        fun of(task: Task.Bucket) = BucketState(task.categories, task.entries)
    }
}

enum class BuildResult { Right, Wrong, Revealed }

/**
 * Satz bauen: Bausteine unten, Antwortzeile oben. Tipp setzt einen Baustein in die Zeile, Tipp in der Zeile nimmt ihn zurueck. "Pruefen"
 * wertet aus (gegen ALLE gueltigen Reihenfolgen): richtig = fertig, falsch = Wackeln; nach [MAX_TRIES] Versuchen wird die Hauptloesung gezeigt.
 */
class BuildState(val tokens: List<Pair<String, String>>, val solutions: List<List<String>>, val shuffled: List<Pair<String, String>>) {
    val row = mutableListOf<String>()
    var mistakes = 0
        private set
    var done = false
        private set
    var revealed = false
        private set
    private var tries = 0
    private val maxLength = solutions.maxOf { it.size }
    private val lengths = solutions.map { it.size }.toSet()

    val complete get() = row.size in lengths
    val known get() = mistakes == 0 && !revealed

    fun textOf(id: String): String = tokens.firstOrNull { it.first == id }?.second.orEmpty()
    fun inRow(id: String) = id in row

    fun tapChip(id: String) {
        if (!done && id !in row && row.size < maxLength) row.add(id)
    }

    fun tapRow(position: Int) {
        if (!done && position in row.indices) row.removeAt(position)
    }

    fun check(): BuildResult? {
        if (!complete || done) return null
        if (solutions.any { it == row }) {
            done = true
            return BuildResult.Right
        }
        mistakes++
        tries++
        if (tries >= MAX_TRIES) {
            showSolution()
            return BuildResult.Revealed
        }
        return BuildResult.Wrong
    }

    /** "Weiss nicht": die richtige Reihenfolge zeigen (dann wird keine Antwort mitgeschickt). */
    fun revealAll() {
        if (!done) showSolution()
    }

    private fun showSolution() {
        row.clear()
        row.addAll(solutions.first())
        done = true
        revealed = true
    }

    companion object {
        const val MAX_TRIES = 2

        /** Bausteine kommen vom Backend gemischt; stehen sie zufaellig in Loesungsreihenfolge, mischt der Client. */
        fun of(task: Task.Build): BuildState {
            val inOrder = task.tokens.map { it.first }.let { ids -> task.solutions.any { it == ids } }
            return BuildState(task.tokens, task.solutions, if (inOrder) shuffledFor(task.item, task.tokens) else task.tokens)
        }
    }
}

/**
 * Antwort fuer `POST /attempts` (Feld `answer`) bei den Anordnungs-Aufgaben; der Server prueft nach. Nie bei "Weiss nicht"/Aufdecken
 * und nie, wenn die Aufgabe keine ids hat (Altformat): dann null.
 */
fun matchAnswer(task: Task.Match, state: MatchState): kotlinx.serialization.json.JsonElement? {
    val l = task.leftIds ?: return null
    val r = task.rightIds ?: return null
    if (!state.done || state.dontKnow || state.revealed.isNotEmpty()) return null
    val pairs = state.connected().entries.associate { (lt, rt) -> (l[lt] ?: return null) to JsonPrimitive(r[rt] ?: return null) }
    return JsonObject(mapOf("pairs" to JsonObject(pairs)))
}

fun orderAnswer(task: Task.Order, state: OrderState): kotlinx.serialization.json.JsonElement? {
    val ids = task.ids ?: return null
    if (!state.done || state.revealedAll) return null
    return JsonObject(mapOf("order" to JsonArray(ids.map { JsonPrimitive(it) })))
}

fun bucketAnswer(state: BucketState): kotlinx.serialization.json.JsonElement? {
    if (!state.done || state.anyRevealed) return null
    return JsonObject(mapOf("assignments" to JsonObject(state.placed.mapValues { JsonPrimitive(it.value) })))
}

fun buildAnswer(state: BuildState): kotlinx.serialization.json.JsonElement? {
    if (!state.done || state.revealed) return null
    return JsonObject(mapOf("order" to JsonArray(state.row.map { JsonPrimitive(it) })))
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
    is Task.Bucket -> task.entries.joinToString("; ") { e -> "${e.text} = ${task.categories.first { it.first == e.categoryId }.second}" }
    is Task.Build -> task.solution.joinToString(" ") { id -> task.tokens.first { it.first == id }.second }
    is Task.Order -> task.steps.joinToString(" → ")
    is Task.Lesson, is Task.Teach -> ""
}

/** Anteil der Runde, der geschafft ist (nach geschaetzter Zeit); fuer den Zeitbalken oben. */
fun sessionProgress(items: List<Item>, doneCount: Int): Float {
    val total = items.sumOf { it.estSeconds.coerceAtLeast(1) }
    if (total == 0) return 0f
    return items.take(doneCount).sumOf { it.estSeconds.coerceAtLeast(1) }.toFloat() / total
}

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
