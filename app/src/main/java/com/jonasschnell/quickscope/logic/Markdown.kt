package com.jonasschnell.quickscope.logic

/** Die rechtlichen Seiten, als Texte in den App-Assets (assets/legal/<datei>.md). */
enum class LegalPage(val file: String, val title: String) {
    Privacy("datenschutz", "Datenschutzerklärung"),
    Terms("agb", "Allgemeine Geschäftsbedingungen"),
    Imprint("impressum", "Impressum"),
}

/** Ein Stueck fett oder normal gesetzten Textes. */
data class MdSpan(val text: String, val bold: Boolean = false)

/** Bausteine des kleinen Markdown-Light: Ueberschriften, Absaetze, Listenpunkte und graue Hinweiszeilen. */
sealed interface MdBlock {
    data class H1(val text: String) : MdBlock
    data class H2(val text: String) : MdBlock
    data class Para(val spans: List<MdSpan>) : MdBlock
    data class Bullet(val spans: List<MdSpan>) : MdBlock
    data class Note(val text: String) : MdBlock
}

/** **fett** in Spans zerlegen; ein nicht geschlossenes ** bleibt als normaler Text stehen. */
fun parseSpans(text: String): List<MdSpan> {
    val out = mutableListOf<MdSpan>()
    var i = 0
    var bold = false
    while (i < text.length) {
        val at = text.indexOf("**", i)
        if (at < 0) {
            out += MdSpan(text.substring(i), bold)
            break
        }
        if (bold || text.indexOf("**", at + 2) >= 0) {
            if (at > i) out += MdSpan(text.substring(i, at), bold)
            bold = !bold
            i = at + 2
        } else {
            out += MdSpan(text.substring(i), bold)
            break
        }
    }
    return out.filter { it.text.isNotEmpty() }
}

/**
 * Zeilenweise: `# ` und `## ` Ueberschriften, `- ` Listenpunkt, `~ ` kleine graue Zeile, Leerzeile beendet den Absatz;
 * aufeinanderfolgende Textzeilen bilden einen Absatz (Zeilenumbrueche bleiben).
 */
fun parseMarkdown(src: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val para = mutableListOf<String>()
    fun flush() {
        if (para.isNotEmpty()) blocks += MdBlock.Para(parseSpans(para.joinToString("\n")))
        para.clear()
    }
    for (raw in src.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("## ") -> { flush(); blocks += MdBlock.H2(line.removePrefix("## ").trim()) }
            line.startsWith("# ") -> { flush(); blocks += MdBlock.H1(line.removePrefix("# ").trim()) }
            line.startsWith("- ") -> { flush(); blocks += MdBlock.Bullet(parseSpans(line.removePrefix("- ").trim())) }
            line.startsWith("~ ") -> { flush(); blocks += MdBlock.Note(line.removePrefix("~ ").trim()) }
            else -> para += line
        }
    }
    flush()
    return blocks
}
