package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.Exam
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

/** "noch 12 Tage 4 Std", "noch 5 Std 20 Min", "noch 20 Min", sonst "Prüfung vorbei". */
fun formatRemaining(startsAt: Instant?, now: Instant): String {
    if (startsAt == null || !startsAt.isAfter(now)) return "Prüfung vorbei"
    val total = Duration.between(now, startsAt)
    val days = total.toDays()
    val hours = total.toHours() % 24
    val minutes = total.toMinutes() % 60
    val text = when {
        days >= 1 -> "$days ${if (days == 1L) "Tag" else "Tage"}" + if (hours > 0) " $hours Std" else ""
        total.toHours() >= 1 -> "${total.toHours()} Std" + if (minutes > 0) " $minutes Min" else ""
        else -> "${maxOf(1, minutes)} Min"
    }
    return "noch $text"
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
