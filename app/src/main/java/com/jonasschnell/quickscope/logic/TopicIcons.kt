package com.jonasschnell.quickscope.logic

/** Feste Sammlung der Thema-Icons (Schluessel wie im Backend). Die KI waehlt, der Nutzer kann ueberschreiben. */
val TOPIC_ICON_KEYS: List<String> = listOf(
    "graduation", "book", "pencil", "calculator", "atom", "flask", "microscope", "dna", "brain", "code",
    "server", "database", "shield", "lock", "globe", "language", "speech", "music", "palette", "camera",
    "film", "theater", "history", "landmark", "scale", "gavel", "briefcase", "chart", "coins", "bank",
    "cart", "truck", "car", "wrench", "hammer", "plug", "bolt", "leaf", "tree", "paw",
    "heart", "stethoscope", "pill", "apple", "utensils", "ball", "trophy", "mountain", "map", "plane",
    "rocket", "star", "puzzle", "lightbulb", "clock", "calendar", "mail", "phone", "home",
)

const val DEFAULT_TOPIC_ICON = "graduation"

/** Unbekannter oder fehlender Schluessel ergibt das Abschlusskappen-Icon. */
fun normalizeIcon(key: String?): String = key?.takeIf { it in TOPIC_ICON_KEYS } ?: DEFAULT_TOPIC_ICON
