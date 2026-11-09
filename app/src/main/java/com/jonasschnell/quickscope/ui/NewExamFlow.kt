package com.jonasschnell.quickscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.AppViewModel
import com.jonasschnell.quickscope.data.ExamCreate
import com.jonasschnell.quickscope.data.UploadJob
import com.jonasschnell.quickscope.logic.isInPast
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Black
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Muted
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val PAGES = 4
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** Kurzbeschreibung eines vorgemerkten Uploads, z. B. "Foto-Scan, 3 Seiten, Skript". */
private fun jobLabel(job: UploadJob): String {
    val what = if (job.scan) "Scan, ${job.files.size} ${if (job.files.size == 1) "Seite" else "Seiten"}" else job.files.first().name
    return "$what · ${if (job.role == "exam") "Aufgaben" else "Skript"}"
}

/**
 * Neue Pruefung als Seiten mit Fortschrittspunkten: Name, Datum und Uhrzeit, Material, Beschreibung.
 * Material und Beschreibung sind optional. [onClose] ist null, solange es noch keine Pruefung gibt (kein Zurueck).
 */
@Composable
fun NewExamFlow(vm: AppViewModel, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    var page by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf(LocalTime.of(8, 0)) }
    var jobs by remember { mutableStateOf(listOf<UploadJob>()) }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val back = { if (page > 0) page-- else onClose?.invoke() }
    BackHandler(enabled = page > 0 || onClose != null) { back() }

    fun finish() {
        busy = true
        vm.createExam(
            ExamCreate(name.trim(), date.toString(), timeFmt.format(time), description.trim().ifEmpty { null }),
            onDone = { exam ->
                vm.upload(exam.id, jobs)
                onClose?.invoke()
            },
            onFailure = { busy = false },
        )
    }

    Column(modifier.fillMaxSize().background(Black).navigationBarsPadding().imePadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.CenterStart) {
                if (page > 0 || onClose != null) {
                    Text(if (page > 0) "Zurück" else "✕", color = Muted, style = MaterialTheme.typography.titleSmall, modifier = Modifier.hapticClickable { back() }.padding(vertical = 8.dp))
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                repeat(PAGES) { i ->
                    Box(Modifier.padding(horizontal = 4.dp).size(8.dp).clip(CircleShape).background(if (i == page) Accent else CardHigh))
                }
            }
            Spacer(Modifier.size(48.dp))
        }
        AnimatedContent(
            page, modifier = Modifier.weight(1f).fillMaxWidth(), label = "examPage",
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 3 } + fadeOut())
            },
        ) { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (p) {
                    0 -> {
                        PageTitle("Wie heißt die Prüfung?")
                        QuickscopeField(name, { name = it.take(80) }, "Name")
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton("Weiter", { page = 1 }, enabled = name.isNotBlank())
                    }
                    1 -> {
                        PageTitle("Wann ist die Prüfung?")
                        WhenPicker(date, time, { date = it }, { time = it })
                        val past = date?.let { isInPast(it, time, Instant.now()) } == true
                        if (past) Text("Dieser Zeitpunkt ist schon vorbei.", color = com.jonasschnell.quickscope.ui.theme.Danger, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton("Weiter", { page = 2 }, enabled = date != null && !past)
                    }
                    2 -> {
                        PageTitle("Material hinzufügen")
                        Text("Optional. Foto, Galerie oder Dateien.", color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        MaterialAddButtons(onJobs = { jobs = jobs + it })
                        jobs.forEachIndexed { i, job ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(jobLabel(job), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text("✕", color = Muted, modifier = Modifier.hapticClickable { jobs = jobs.filterIndexed { j, _ -> j != i } }.padding(8.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton(if (jobs.isEmpty()) "Überspringen" else "Weiter", { page = 3 })
                    }
                    else -> {
                        PageTitle("Kurz beschreiben?")
                        Text(
                            "Optional. Ohne Material ergänzt die KI aus eigenem Wissen.",
                            color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        )
                        QuickscopeField(description, { description = it.take(2000) }, "z. B. IHK AP2, Schwerpunkt Kalkulation", singleLine = false)
                        Text("${description.length} / 2000", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.End))
                        if (jobs.isEmpty() && description.isBlank()) {
                            Text("Ohne Material und Beschreibung hat die KI nichts zum Vorbereiten. Du kannst beides später ergänzen.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton(if (busy) "Einen Moment" else "Los", { finish() }, enabled = !busy)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageTitle(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}
