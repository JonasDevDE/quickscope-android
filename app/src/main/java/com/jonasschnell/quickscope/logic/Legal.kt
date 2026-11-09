package com.jonasschnell.quickscope.logic

/** Rechtliche Seiten: eigene Adresse aus dem Build, sonst <BASE_URL>legal/<seite>; nie doppelter Schraegstrich. */
fun legalUrl(base: String, page: String, override: String = ""): String {
    if (override.isNotBlank()) return override.trim()
    return base.trimEnd('/') + "/legal/" + page.trimStart('/')
}

const val LEGAL_PRIVACY = "datenschutz"
const val LEGAL_TERMS = "agb"
