package com.jonasschnell.quickscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.logic.LegalPage
import com.jonasschnell.quickscope.logic.MdBlock
import com.jonasschnell.quickscope.logic.MdSpan
import com.jonasschnell.quickscope.logic.parseMarkdown
import com.jonasschnell.quickscope.ui.theme.Black
import com.jonasschnell.quickscope.ui.theme.Muted

/** Rechtliche Seite als ruhige Vollbildseite: Titel, scrollbarer Text; der Text liegt in der App (ohne Netz, ohne WebView). */
@Composable
fun LegalScreen(page: LegalPage, onClose: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember(page) {
        val text = runCatching { context.assets.open("legal/${page.file}.md").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
        parseMarkdown(text)
    }
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize().background(Black).blockTouches().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CloseButton(onClose, label = "Zurück")
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.H1 -> Text(b.text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))
                    is MdBlock.H2 -> Text(b.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
                    is MdBlock.Para -> Text(styled(b.spans), style = MaterialTheme.typography.bodyLarge)
                    is MdBlock.Bullet -> Row {
                        Text("•", Modifier.padding(end = 10.dp), style = MaterialTheme.typography.bodyLarge)
                        Text(styled(b.spans), style = MaterialTheme.typography.bodyLarge)
                    }
                    is MdBlock.Note -> Text(b.text, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun styled(spans: List<MdSpan>) = buildAnnotatedString {
    spans.forEach { s -> if (s.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text) } else append(s.text) }
}
