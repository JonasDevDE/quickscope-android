package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.Profile

/** Hoechstlaenge des Profiltextes "Ueber mich" (wie im Backend). */
const val ABOUT_MAX = 600

/** Alter: erlaubt 10 bis 99, Standard 18. */
const val AGE_MIN = 10
const val AGE_MAX = 99
const val AGE_DEFAULT = 18

/** Bildungsstand: Schluessel des Backends mit Anzeigetext. */
val EDUCATION = listOf(
    "hauptschule" to "Hauptschule",
    "realschule" to "Realschule",
    "abitur" to "Abitur",
    "ausbildung" to "Ausbildung",
    "studium" to "Studium",
    "other" to "Anderes",
)

fun educationLabel(key: String?): String = EDUCATION.firstOrNull { it.first == key }?.second ?: "–"

fun ageValid(age: Int?): Boolean = age == null || age in AGE_MIN..AGE_MAX

fun clampAge(age: Int): Int = age.coerceIn(AGE_MIN, AGE_MAX)

/** Eingabe auf die Hoechstlaenge begrenzen (Einfuegen langer Texte schneidet ab). */
fun clampAbout(text: String): String = if (text.length > ABOUT_MAX) text.take(ABOUT_MAX) else text

/** Zaehler "12/600". */
fun aboutCounter(text: String): String = "${text.length}/$ABOUT_MAX"

/** Gueltig, wenn nicht laenger als [ABOUT_MAX]; leer ist erlaubt (kein Pflichtfeld). */
fun aboutValid(text: String): Boolean = text.length <= ABOUT_MAX

/** Zu sendender Text: Raender weg, Zeilenumbrueche bleiben. */
fun normalizeAbout(text: String): String = text.trim()

/** Muss gespeichert werden? Nur bei Aenderung gegenueber dem gespeicherten Stand und wenn gueltig. */
fun aboutNeedsSave(saved: String, current: String): Boolean = aboutValid(current) && normalizeAbout(current) != normalizeAbout(saved)

/** Bearbeitungsstand des Profils in den Einstellungen. */
data class ProfileDraft(val age: Int?, val education: String?, val about: String)

fun draftOf(p: Profile) = ProfileDraft(p.age, p.educationLevel, p.about)

fun draftValid(d: ProfileDraft) = ageValid(d.age) && aboutValid(d.about)

fun draftNeedsSave(saved: ProfileDraft, cur: ProfileDraft): Boolean =
    draftValid(cur) && (cur.age != saved.age || cur.education != saved.education || normalizeAbout(cur.about) != normalizeAbout(saved.about))

// ---------------- Onboarding

/** Ob das Onboarding laeuft: wird nach dem Login aus dem Profil bestimmt. */
enum class OnboardingGate { Unknown, Needed, Done, Failed }

/** Nur wenn das Profil geladen ist und onboardingDone false ist, kommt das Onboarding; bei Netzfehler Failed (Retry), nie endlos blockierend. */
fun gateFor(profile: Profile?): OnboardingGate = when {
    profile == null -> OnboardingGate.Failed
    profile.onboardingDone -> OnboardingGate.Done
    else -> OnboardingGate.Needed
}

/** Seiten des Onboardings in fester Reihenfolge. */
enum class OnboardingPage { Welcome, Age, Education, Voice }

fun nextPage(p: OnboardingPage): OnboardingPage? = OnboardingPage.entries.getOrNull(p.ordinal + 1)

fun previousPage(p: OnboardingPage): OnboardingPage? = OnboardingPage.entries.getOrNull(p.ordinal - 1)

/** Begruessung mit dem Vornamen aus dem Google-Profil. */
fun greeting(name: String?): String {
    val first = name?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
    return if (first != null) "Hi $first" else "Hi"
}

/** Aufnahme: hoechstens 60 Sekunden; Anzeige "0:07". */
const val RECORD_MAX_SECONDS = 60

fun recordTime(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
