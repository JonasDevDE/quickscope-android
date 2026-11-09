package com.jonasschnell.quickscope.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Muted
import com.jonasschnell.quickscope.ui.theme.OnAccent
import com.jonasschnell.quickscope.ui.theme.Outline
import com.jonasschnell.quickscope.ui.theme.Pressed
import com.jonasschnell.quickscope.ui.theme.Danger
import com.jonasschnell.quickscope.ui.theme.DangerOutline
import com.jonasschnell.quickscope.ui.theme.Sheet

/** Rundes Nutzerbild (z. B. Google-Profilbild) mit Initiale als Fallback. */
@Composable
fun UserAvatar(imageUrl: String?, name: String, size: Dp, modifier: Modifier = Modifier) =
    AvatarImage(imageUrl, name, size, modifier)

@Composable
private fun AvatarImage(imageUrl: String?, name: String, size: Dp, modifier: Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(CardHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl != null) {
            Box(Modifier.size(size).shimmer())
            AsyncImage(
                model = imageUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                name.take(1).uppercase(),
                color = Accent,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.4f).sp),
            )
        }
    }
}

/**
 * Autosave fuer Bearbeiten-Formulare. Speichert [value], wenn es sich gegenueber dem letzten gespeicherten Stand
 * geaendert hat und [valid] ist: verzoegert (800 ms) bei Texteingaben, sofort wenn sich [instantKey] aendert
 * (Auswahl/Schalter), und beim Verlassen der Komposition (Flush). Liefert true, solange "Gespeichert" angezeigt wird.
 */
@Composable
fun <T> AutoSave(
    value: T,
    valid: Boolean,
    instantKey: Any? = null,
    enabled: Boolean = true,
    onSave: (value: T, onFailure: () -> Unit) -> Unit,
): Boolean {
    val haptics = LocalHaptics.current
    var last by remember { mutableStateOf(value) }
    var lastInstant by remember { mutableStateOf(instantKey) }
    var savedTick by remember { mutableIntStateOf(0) }
    var showSaved by remember { mutableStateOf(false) }
    val latest = rememberUpdatedState(value)
    val latestValid = rememberUpdatedState(valid && enabled)
    val latestSave = rememberUpdatedState(onSave)

    fun commit() {
        val v = latest.value
        if (!latestValid.value || v == last) return
        val previous = last
        last = v
        latestSave.value(v) { last = previous }
        haptics.tick()
        showSaved = true
        savedTick++
    }

    LaunchedEffect(value, instantKey) {
        if (value == last) {
            lastInstant = instantKey
            return@LaunchedEffect
        }
        val instant = instantKey != lastInstant
        lastInstant = instantKey
        if (!instant) delay(800)
        commit()
    }
    LaunchedEffect(savedTick) {
        if (savedTick > 0) {
            delay(2200)
            showSaved = false
        }
    }
    DisposableEffect(Unit) { onDispose { commit() } }
    return showSaved
}

/** Gemeinsame erhoehte Flaeche: SurfaceRaised mit Rand und sichtbarem Pressed-State. Grundlage fuer Zeilen, Karten, Sekundaerknoepfe. */
@Composable
fun RowCard(
    onClick: (() -> Unit)?, modifier: Modifier = Modifier, minHeight: Dp = 64.dp, border: Color = Outline,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp), content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed) Pressed else CardHigh, label = "rowBg")
    val haptics = LocalHaptics.current
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = minHeight).clip(shape).background(bg).border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null) { haptics.tick(); onClick() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Gefahrenbereich: rot abgesetzte Ueberschrift und Zeilen, am Ende einer Seite. */
@Composable
fun DangerZone(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Gefahrenbereich", color = Danger, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
fun DangerRow(title: String, onClick: () -> Unit) {
    RowCard(onClick, border = DangerOutline) {
        Text(title, color = Danger, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val container by animateColorAsState(if (enabled) Accent else CardHigh, label = "buttonColor")
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(container)
            .then(if (enabled) Modifier else Modifier.border(1.dp, Outline, RoundedCornerShape(28.dp)))
            .hapticClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) OnAccent else Muted)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Color.White) {
    RowCard(onClick, modifier, minHeight = 56.dp, shape = RoundedCornerShape(28.dp)) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = color, textAlign = TextAlign.Center)
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val container by animateColorAsState(if (selected) Accent else CardHigh, label = "pillBg")
    val content by animateColorAsState(if (selected) OnAccent else Color.White, label = "pillFg")
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .border(1.dp, if (selected) Accent else Outline, RoundedCornerShape(50))
            .hapticClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
fun QuickscopeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    suffix: String? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        suffix = suffix?.let { { Text(it, color = Muted) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(20.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = CardHigh,
            unfocusedContainerColor = CardHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = Accent,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = Muted,
            focusedPlaceholderColor = Muted,
            unfocusedPlaceholderColor = Muted,
            focusedSuffixColor = Muted,
            unfocusedSuffixColor = Muted,
            focusedLabelColor = Muted,
            unfocusedLabelColor = Muted,
        ),
        modifier = modifier.fillMaxWidth().border(1.dp, Outline, RoundedCornerShape(20.dp)),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, color: Color = Muted) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = color, modifier = modifier)
}

@Composable
fun CardColumn(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    verticalSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Card).padding(padding),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content,
    )
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Muted,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickscopeSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    dismissable: Boolean = true,
    status: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = LocalHaptics.current
    TickOnAppear()
    LightSystemIcons()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { dismissable || it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = {
            haptics.gestureEnd()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = Sheet,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 12.dp, bottom = 4.dp).size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3A)),
            )
        },
    ) {
        LightSystemIcons()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (title != null) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = status != null,
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut(),
                    ) { Text(status.orEmpty(), color = Muted, style = MaterialTheme.typography.labelMedium) }
                }
            }
            content()
            Spacer(Modifier.height(4.dp))
        }
    }
}
