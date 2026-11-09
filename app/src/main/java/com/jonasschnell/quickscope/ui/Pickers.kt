package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.logic.QUICK_TIMES
import com.jonasschnell.quickscope.logic.todayBerlin
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Muted
import com.jonasschnell.quickscope.ui.theme.OnAccent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMANY)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** Datum und Uhrzeit der Pruefung: ein Datumsknopf, Schnellwahl-Chips fuer die Uhrzeit, "Andere" oeffnet den Zeitwaehler. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhenPicker(date: LocalDate?, time: LocalTime, onDate: (LocalDate) -> Unit, onTime: (LocalTime) -> Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PrimaryButton(date?.let { dateFmt.format(it) } ?: "Datum wählen", { showDate = true })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK_TIMES.forEach { t -> Pill(t, timeFmt.format(time) == t, { onTime(LocalTime.parse(t)) }) }
            Pill(if (timeFmt.format(time) in QUICK_TIMES) "Andere" else timeFmt.format(time), timeFmt.format(time) !in QUICK_TIMES, { showTime = true })
        }
    }
    if (showDate) DateDialog(date, { onDate(it); showDate = false }, { showDate = false })
    if (showTime) TimeDialog(time, { onTime(it); showTime = false }, { showTime = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = todayBerlin(Instant.now())
    val todayMillis = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayMillis
            override fun isSelectableYear(year: Int) = year >= today.year
        },
    )
    val colors = DatePickerDefaults.colors(
        containerColor = com.jonasschnell.quickscope.ui.theme.CardHigh, titleContentColor = Muted, headlineContentColor = Color.White, weekdayContentColor = Muted,
        subheadContentColor = Muted, yearContentColor = Color.White, currentYearContentColor = Accent,
        selectedYearContentColor = OnAccent, selectedYearContainerColor = Accent, dayContentColor = Color.White,
        disabledDayContentColor = Color(0xFF55555A), selectedDayContentColor = OnAccent, selectedDayContainerColor = Accent,
        todayContentColor = Accent, todayDateBorderColor = Accent, navigationContentColor = Color.White,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = colors,
        confirmButton = {
            HapticTextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("OK", color = Accent) }
        },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Abbrechen", color = Color.White) } },
    ) { LightSystemIcons(); DatePicker(state, colors = colors) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.jonasschnell.quickscope.ui.theme.CardHigh,
        title = { Text("Uhrzeit", color = Color.White) },
        text = {
            LightSystemIcons()
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                TimePicker(
                    state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = CardHigh, clockDialSelectedContentColor = OnAccent, clockDialUnselectedContentColor = Color.White,
                        selectorColor = Accent, containerColor = com.jonasschnell.quickscope.ui.theme.CardHigh, timeSelectorSelectedContainerColor = Accent,
                        timeSelectorUnselectedContainerColor = CardHigh, timeSelectorSelectedContentColor = OnAccent,
                        timeSelectorUnselectedContentColor = Color.White,
                    ),
                )
            }
        },
        confirmButton = { HapticTextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK", color = Accent) } },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Abbrechen", color = Color.White) } },
    )
}
