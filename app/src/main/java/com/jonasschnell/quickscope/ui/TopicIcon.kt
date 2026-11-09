package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.jonasschnell.quickscope.logic.normalizeIcon

/**
 * Thema-Icon, nackt: kein Kreis, kein Rand, kein Hintergrund; Farbe wie der Text daneben. Vektor-Drawable `ic_topic_<schluessel>`
 * (Material Icons, Apache 2.0); unbekannte oder fehlende Schluessel ergeben graduation.
 */
@Composable
fun TopicIcon(key: String?, size: Dp, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val k = normalizeIcon(key)
    val context = LocalContext.current
    val res = remember(k) { context.resources.getIdentifier("ic_topic_$k", "drawable", context.packageName) }
    if (res != 0) Icon(painterResource(res), contentDescription = null, tint = tint, modifier = modifier.size(size))
    else LearnMark(size)
}
