package com.jonasschnell.quickscope.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jonasschnell.quickscope.AppViewModel
import com.jonasschnell.quickscope.logic.AGE_DEFAULT
import com.jonasschnell.quickscope.logic.EDUCATION
import com.jonasschnell.quickscope.logic.OnboardingPage
import com.jonasschnell.quickscope.logic.RECORD_MAX_SECONDS
import com.jonasschnell.quickscope.logic.VOICE_FILE_EXT
import com.jonasschnell.quickscope.logic.VoiceOutcome
import com.jonasschnell.quickscope.logic.voiceOutcomeMessage
import com.jonasschnell.quickscope.logic.aboutCounter
import com.jonasschnell.quickscope.logic.clampAbout
import com.jonasschnell.quickscope.logic.clampAge
import com.jonasschnell.quickscope.logic.greeting
import com.jonasschnell.quickscope.logic.nextPage
import com.jonasschnell.quickscope.logic.previousPage
import com.jonasschnell.quickscope.logic.recordTime
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Black
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Muted
import kotlinx.coroutines.delay
import java.io.File

private enum class VoiceStep { Idle, Recording, Uploading, Review }

/**
 * Onboarding beim ersten Start (nur bei onboardingDone == false): Begruessung, Alter, Bildung, Sprachnachricht mit
 * Zusammenfassung zum Pruefen. Eine Sache pro Seite, grosse Antworten; "Fertig" speichert alles in einem PUT.
 */
@Composable
fun OnboardingFlow(vm: AppViewModel, name: String?) {
    var page by remember { mutableStateOf(OnboardingPage.Welcome) }
    var age by remember { mutableIntStateOf(AGE_DEFAULT) }
    var education by remember { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(VoiceStep.Idle) }
    var busy by remember { mutableStateOf(false) }

    BackHandler(enabled = previousPage(page) != null && step != VoiceStep.Uploading) {
        if (step == VoiceStep.Idle) previousPage(page)?.let { page = it }
    }
    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            OnboardingPage.entries.forEach { p ->
                Box(Modifier.padding(horizontal = 4.dp).size(8.dp).clip(CircleShape).background(if (p == page) Accent else CardHigh))
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (page) {
                OnboardingPage.Welcome -> {
                    Spacer(Modifier.height(48.dp))
                    LearnMark(72.dp, Modifier.align(Alignment.CenterHorizontally))
                    Text(greeting(name), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text("Damit Erklärungen zu dir passen.", color = Muted, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    PrimaryButton("Los", { nextPage(page)?.let { page = it } })
                }
                OnboardingPage.Age -> {
                    Title("Wie alt bist du?")
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        RoundButton("–") { age = clampAge(age - 1) }
                        Text("$age", style = MaterialTheme.typography.displaySmall.copy(fontSize = 64.sp), color = Accent)
                        RoundButton("+") { age = clampAge(age + 1) }
                    }
                    Spacer(Modifier.height(24.dp))
                    PrimaryButton("Weiter", { nextPage(page)?.let { page = it } })
                }
                OnboardingPage.Education -> {
                    Title("Deine Bildung?")
                    EDUCATION.forEach { (key, label) ->
                        if (key == education) PrimaryButton(label, { education = key; nextPage(page)?.let { page = it } })
                        else SecondaryButton(label, { education = key; nextPage(page)?.let { page = it } })
                    }
                }
                OnboardingPage.Voice -> VoicePage(
                    vm, step, about, busy,
                    onStep = { step = it },
                    onAbout = { about = clampAbout(it) },
                    onDone = {
                        busy = true
                        vm.finishOnboarding(age, education, about, onFailure = { busy = false })
                    },
                )
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun RoundButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clip(CircleShape).background(CardHigh).hapticClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.displaySmall)
    }
}

/** Sprachseite: Aufnahme per Tipp starten/stoppen (max. 60 s), Upload mit Shimmer, dann bearbeitbarer Text. */
@Composable
private fun ColumnScope.VoicePage(vm: AppViewModel, step: VoiceStep, about: String, busy: Boolean, onStep: (VoiceStep) -> Unit, onAbout: (String) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var seconds by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    fun finishRecording() {
        val file = recorder?.stop()
        recorder = null
        if (file == null) {
            onStep(VoiceStep.Idle)
            return
        }
        onStep(VoiceStep.Uploading)
        vm.uploadVoice(file) { outcome ->
            hint = voiceOutcomeMessage(outcome)
            when (outcome) {
                is VoiceOutcome.Text -> { onAbout(outcome.about); onStep(VoiceStep.Review) }
                VoiceOutcome.Unavailable -> onStep(VoiceStep.Review) // Text selbst tippen als Weg
                else -> onStep(VoiceStep.Idle)                          // nochmal aufnehmen
            }
        }
    }
    fun startRecording() {
        val r = VoiceRecorder(context) { finishRecording() } // automatisch nach 60 s
        if (r.start()) {
            recorder = r
            seconds = 0
            onStep(VoiceStep.Recording)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() }
    LaunchedEffect(step) {
        while (step == VoiceStep.Recording) {
            delay(250)
            seconds = recorder?.elapsedSeconds() ?: seconds
        }
    }
    DisposableEffect(Unit) { onDispose { recorder?.cancel() } } // nie eine Aufnahme behalten

    Title("Wer bist du?")
    hint?.let { Text(it, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    when (step) {
        VoiceStep.Idle -> {
            Text("Erzähl kurz, wer du bist: Hobbys, Beruf, was dir wichtig ist.", color = Muted, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Aufnehmen", {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            })
            SecondaryButton("Überspringen", { onStep(VoiceStep.Review) })
        }
        VoiceStep.Recording -> {
            Spacer(Modifier.height(24.dp))
            Text(recordTime(seconds), style = MaterialTheme.typography.displaySmall, color = Accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text("von ${recordTime(RECORD_MAX_SECONDS)}", color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Stopp", { finishRecording() })
        }
        VoiceStep.Uploading -> {
            Text("Einen Moment …", color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            SkeletonCard(120.dp)
        }
        VoiceStep.Review -> {
            Text("Stimmt das? Du kannst es ändern.", color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            QuickscopeField(about, onAbout, "Ich bin Snowboarder, arbeite im Marketing und mag Autos.", modifier = Modifier.heightIn(min = 160.dp), singleLine = false)
            Text(aboutCounter(about), color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.End))
            PrimaryButton(if (busy) "Einen Moment" else "Fertig", { if (!busy) onDone() }, enabled = !busy)
        }
    }
}

/** Nimmt AAC (ADTS, .aac) auf (hoechstens 60 s) in eine Cache-Datei; die Datei wird nach dem Upload geloescht. */
class VoiceRecorder(private val context: Context, private val onMax: () -> Unit) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(): Boolean = try {
        val f = File(context.cacheDir, "voice_${System.currentTimeMillis()}.$VOICE_FILE_EXT")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44100)
        r.setAudioEncodingBitRate(64_000)
        r.setMaxDuration(RECORD_MAX_SECONDS * 1000)
        r.setOutputFile(f.absolutePath)
        r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMax() }
        r.prepare()
        r.start()
        recorder = r
        file = f
        startedAt = android.os.SystemClock.elapsedRealtime()
        true
    } catch (e: Exception) {
        android.util.Log.w("VoiceRecorder", "Aufnahme nicht gestartet", e)
        cancel()
        false
    }

    fun elapsedSeconds(): Int = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt().coerceAtMost(RECORD_MAX_SECONDS)

    /** Beendet die Aufnahme und liefert die Datei (null bei Fehler). */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val f = file
        return try {
            r.stop()
            r.release()
            f
        } catch (e: Exception) {
            runCatching { r.release() }
            f?.delete()
            null
        }
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
        file?.delete()
    }
}
