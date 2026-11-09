package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.Exam
import com.jonasschnell.quickscope.data.Forecast
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DE = Locale.GERMANY
private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

fun examStart(e: Exam): Instant? = runCatching { Instant.parse(e.startsAt) }.getOrNull()

/** Dauerhaftes Lernen ohne Termin. */
fun isOpen(e: Exam): Boolean = e.kind == "open"

fun isOver(e: Exam, now: Instant): Boolean = e.status == "over" || examStart(e)?.let { !it.isAfter(now) } == true

/**
 * Aktive Pruefung: die zuletzt gewaehlte (falls es sie noch gibt), sonst die naechste anstehende,
 * sonst die zuletzt vergangene. Ohne Pruefungen null.
 */
fun pickActiveExam(exams: List<Exam>, chosenId: String?, now: Instant): Exam? {
    exams.firstOrNull { it.id == chosenId }?.let { return it }
    val upcoming = exams.filter { !isOver(it, now) }.minByOrNull { examStart(it) ?: Instant.MAX }
    if (upcoming != null) return upcoming
    return exams.maxByOrNull { examStart(it) ?: Instant.MIN }
}

/** Pruefungen fuer das Popup: anstehende zuerst (naechste oben), dann vergangene (juengste oben). */
fun sortExams(exams: List<Exam>, now: Instant): List<Exam> {
    val (past, upcoming) = exams.partition { isOver(it, now) }
    return upcoming.sortedBy { examStart(it) ?: Instant.MAX } + past.sortedByDescending { examStart(it) ?: Instant.MIN }
}

private val whenFmt = DateTimeFormatter.ofPattern("EEE, d. MMM, HH:mm", DE)

/** "Fr., 13. Nov., 09:00" aus Datum und Uhrzeit; bei unlesbaren Werten die Rohwerte. */
fun formatExamWhen(date: String, time: String): String = runCatching {
    whenFmt.format(LocalDate.parse(date).atTime(LocalTime.parse(time)))
}.getOrElse { "$date $time" }

/** Liegt der Zeitpunkt (Europe/Berlin) nicht mehr in der Zukunft? */
fun isInPast(date: LocalDate, time: LocalTime, now: Instant): Boolean =
    !date.atTime(time).atZone(BERLIN).toInstant().isAfter(now)

/** Heutiges Datum in Berlin als Untergrenze fuer den Datumswaehler. */
fun todayBerlin(now: Instant): LocalDate = now.atZone(BERLIN).toLocalDate()

val QUICK_TIMES = listOf("08:00", "09:00", "10:00", "13:00")
val DAILY_MINUTE_CHOICES = listOf(10, 20, 30, 45, 60)

/** Thementitel je Themen-Id fuer die Zeile ueber der Frage; bei Untergruppen "Gruppe › Titel". */
fun topicLabels(topics: List<com.jonasschnell.quickscope.data.Topic>): Map<String, String> {
    val byId = topics.associateBy { it.id }
    return topics.associate { t ->
        val parent = t.parentId?.let { byId[it] }?.title?.takeIf { it.isNotBlank() }
        t.id to if (parent != null) "$parent › ${t.title}" else t.title
    }
}

/** Vorwissen: Auswahlwerte fuer die Prüfung (Schluessel des Backends). */
val PRIOR_KEYS = listOf("none", "some", "much")

fun priorLabel(key: String): String = when (key) {
    "none" -> "Nichts"
    "much" -> "Viel"
    else -> "Etwas"
}

/** Kurzzeile zum Lernplan: "Heute: Rechnen und Vertragsrecht" bzw. "3 von 8 Kapiteln gehört"; null ohne Plan. */
fun planLine(plan: com.jonasschnell.quickscope.data.Plan?): String? {
    if (plan == null || plan.chaptersTotal <= 0) return null
    val today = plan.todayChapters
    return when {
        today.size == 1 -> "Heute: ${today[0]}"
        today.size == 2 -> "Heute: ${today[0]} und ${today[1]}"
        today.size > 2 -> "Heute: ${today[0]}, ${today[1]} und ${today.size - 2} weitere"
        else -> "${plan.chaptersHeard} von ${plan.chaptersTotal} Kapiteln gehört"
    }
}

/**
 * Soll eine Prognose-Zahl gezeigt werden? Nur bei fertiger Pruefung und wenn schon etwas beantwortet wurde
 * (coveragePercent > 0). Davor besteht der Wert nur aus Vorannahmen und Ratewahrscheinlichkeit und springt, sobald sich
 * Aufgaben oder Lektionen aendern; dann lieber ein ruhiger Platzhalter statt einer Zahl.
 */
fun forecastReliable(exam: com.jonasschnell.quickscope.data.Exam?, f: com.jonasschnell.quickscope.data.Forecast?): Boolean =
    exam != null && f != null && exam.status == "ready" &&
        if (exam.kind == "open") f.knowledgePercent != null else f.coveragePercent > 0 && f.confidence != "none"

/** Stand jetzt, oder null ohne belastbare Prognose (confidence "none": die Zahlen fehlen im JSON). */
fun Forecast.scoreOrNull(): Int? =
    if (mode == "open") knowledgePercent else if (confidence == "none" || coveragePercent <= 0) null else scoreNowPercent

/** Zahl im Ring: bei offenem Lernen der Wissensstand, sonst der Stand fuer heute. */
fun ringPercent(exam: Exam, f: com.jonasschnell.quickscope.data.Forecast): Int =
    if (isOpen(exam)) f.knowledgePercent ?: 0 else f.scoreNowPercent

/** Zeile in der Liste: Termin oder "Kein Prüfungsdatum" (bei offenem Lernen), dahinter "vorbei". */
fun examSubtitle(e: Exam, now: Instant): String =
    if (isOpen(e)) "Kein Prüfungsdatum" else formatExamWhen(e.date, e.time) + if (isOver(e, now)) " · vorbei" else ""

/** Seiten der Neu-Pruefung-Folge: bei "Dauerhaft lernen" entfaellt die Datum-/Uhrzeitseite. */
enum class NewExamPage { Kind, Name, When, Material, Description, Prior, Daily }

fun newExamPages(open: Boolean): List<NewExamPage> =
    NewExamPage.entries.filter { open.not() || it != NewExamPage.When }

/** Erster Buchstabe gross (Satz- oder Zeilenanfang), sonst unveraendert. */
fun capFirst(text: String): String = text.replaceFirstChar { it.uppercase() }

/**
 * Relative Angabe bis zum Prüfungstermin, aus KALENDERTAGEN in [zone] (nicht aus Stunden/24): heute, morgen, übermorgen, "in N Tagen";
 * am Termintag zusätzlich "in N Std" bzw. "in N Min". Nach dem Termin "vorbei". Am Satz- oder Zeilenanfang ([startOfSentence]) gross
 * ("Morgen"), mitten im Satz klein ("Prüfung morgen").
 */
fun relativeDayLabel(startsAt: Instant?, now: Instant, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), startOfSentence: Boolean = false): String {
    val text = when {
        startsAt == null || !startsAt.isAfter(now) -> "vorbei"
        else -> {
            val days = java.time.temporal.ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), startsAt.atZone(zone).toLocalDate())
            val total = Duration.between(now, startsAt)
            when {
                days <= 0L -> when {
                    total.toHours() >= 1 -> "heute, in ${total.toHours()} Std"
                    else -> "in ${maxOf(1, total.toMinutes())} Min"
                }
                days == 1L -> "morgen"
                days == 2L -> "übermorgen"
                else -> "in $days Tagen"
            }
        }
    }
    return if (startOfSentence) capFirst(text) else text
}

/** Eintraege hinter "Mehr" auf Heute, in der Reihenfolge ihrer Gruppen (Ueben, Heute, Pruefung). */
enum class MoreEntry { QuestionsOnly, ListenOnly, Plan, Used, Streak, Material, Settings }

fun moreEntries(hasLessons: Boolean, hasPlan: Boolean, streak: Int?): List<MoreEntry> = buildList {
    if (hasLessons) { add(MoreEntry.QuestionsOnly); add(MoreEntry.ListenOnly) }
    if (hasPlan) add(MoreEntry.Plan)
    add(MoreEntry.Used)
    if (streak != null && streak > 0) add(MoreEntry.Streak)
    add(MoreEntry.Material)
    add(MoreEntry.Settings)
}
