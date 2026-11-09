package com.jonasschnell.quickscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.AppViewModel
import com.jonasschnell.quickscope.UiState
import com.jonasschnell.quickscope.data.Profile
import com.jonasschnell.quickscope.logic.AGE_MAX
import com.jonasschnell.quickscope.logic.AGE_MIN
import com.jonasschnell.quickscope.logic.EDUCATION
import com.jonasschnell.quickscope.logic.ProfileDraft
import com.jonasschnell.quickscope.logic.aboutCounter
import com.jonasschnell.quickscope.logic.clampAbout
import com.jonasschnell.quickscope.logic.draftNeedsSave
import com.jonasschnell.quickscope.logic.draftOf
import com.jonasschnell.quickscope.logic.draftValid
import com.jonasschnell.quickscope.logic.educationLabel
import com.jonasschnell.quickscope.ui.theme.Black
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Muted

/**
 * Profil in den Einstellungen: Alter, Bildung und "Ueber mich". Dient nur dazu, Beispiele und Erklaerungen passender zu machen.
 * Kein Pflichtfeld; gespeichert wird automatisch, "Gespeichert" ist sichtbar.
 */
@Composable
fun AboutScreen(state: UiState, vm: AppViewModel, onClose: () -> Unit) {
    var initial by remember { mutableStateOf<Profile?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val loaded = vm.loadProfile()
        if (loaded == null) failed = true else initial = loaded
    }
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Zurück", color = Muted, style = MaterialTheme.typography.titleSmall, modifier = Modifier.hapticClickable(onClick = onClose).padding(vertical = 8.dp))
        }
        Text("Profil", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp))
        val p = initial
        when {
            p != null -> ProfileEditor(state, vm, p, onClose)
            failed -> {
                Text("Gerade nicht erreichbar.", color = Muted)
                Spacer(Modifier.height(16.dp))
                SecondaryButton("Schließen", onClose)
            }
            else -> SkeletonCard(140.dp)
        }
    }
}

@Composable
private fun ProfileEditor(state: UiState, vm: AppViewModel, initial: Profile, onClose: () -> Unit) {
    var draft by remember { mutableStateOf(draftOf(initial)) }
    var saved by remember { mutableStateOf(draftOf(initial)) }
    var changed by remember { mutableStateOf(false) }
    val showSaved = AutoSave(draft, draftValid(draft)) { value, onFailure ->
        if (draftNeedsSave(saved, value)) vm.saveProfile(value, onDone = { saved = value; changed = true }, onFailure = onFailure)
    }
    val exam = state.activeExam
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                DropdownField("Alter", draft.age?.toString() ?: "–", (AGE_MIN..AGE_MAX).map { it.toString() to it.toString() }) { draft = draft.copy(age = it.toInt()) }
            }
            Box(Modifier.weight(1.6f)) {
                DropdownField("Bildung", educationLabel(draft.education), EDUCATION) { draft = draft.copy(education = it) }
            }
        }
        Text("Über mich", style = MaterialTheme.typography.titleMedium)
        QuickscopeField(
            draft.about, { draft = draft.copy(about = clampAbout(it)) }, "Ich bin Snowboarder, arbeite im Marketing und mag Autos.",
            modifier = Modifier.heightIn(min = 180.dp), singleLine = false,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (showSaved) "Gespeichert" else "", color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(aboutCounter(draft.about), color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        // Nach einer Aenderung: optional die Aufgaben der aktuellen Pruefung neu erzeugen; neue Pruefungen nutzen es automatisch
        if (changed && exam != null) {
            Text("Für „${exam.name}“ gilt es nach dem Neuerzeugen.", color = Muted, style = MaterialTheme.typography.bodySmall)
            SecondaryButton("Aufgaben jetzt neu erzeugen", { vm.regenerateItems(exam.id); changed = false })
        }
        PrimaryButton("Fertig", onClose)
    }
}

/** Auswahlfeld mit Dropdown (Zahlen oder Begriffe), im dunklen Stil. */
@Composable
fun <T> DropdownField(label: String, value: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardHigh).hapticClickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.background(Card).heightIn(max = 320.dp)) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(key); open = false }) }
        }
    }
}
