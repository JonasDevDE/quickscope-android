package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.logic.normalizeIcon
import com.jonasschnell.quickscope.ui.theme.Black
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Outline

/** Schluessel, fuer die die eingebaute Kern-Icon-Sammlung schon einen Vektor hat. Alle anderen kommen als `ic_topic_<schluessel>` (res/drawable). */
private val CORE_ICONS: Map<String, ImageVector> = mapOf(
    "pencil" to Icons.Filled.Create, "wrench" to Icons.Filled.Build, "hammer" to Icons.Filled.Build, "cart" to Icons.Filled.ShoppingCart,
    "calendar" to Icons.Filled.DateRange, "mail" to Icons.Filled.Email, "phone" to Icons.Filled.Phone, "home" to Icons.Filled.Home,
    "star" to Icons.Filled.Star, "heart" to Icons.Filled.Favorite, "lock" to Icons.Filled.Lock, "map" to Icons.Filled.Place,
)

/**
 * Thema-Icon: runder dunkler Kreis mit hellem Symbol. Reihenfolge: eigenes Vektor-Drawable `ic_topic_<schluessel>`, sonst Kern-Icon,
 * sonst (und bei unbekanntem Schluessel) die Abschlusskappe.
 */
@Composable
fun TopicIcon(key: String?, size: Dp, modifier: Modifier = Modifier, active: Boolean = false) {
    val k = normalizeIcon(key)
    val context = LocalContext.current
    val res = remember(k) { context.resources.getIdentifier("ic_topic_$k", "drawable", context.packageName) }
    Box(
        modifier.size(size).clip(CircleShape).background(Black).border(1.dp, if (active) Accent else Outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val inner = size * 0.55f
        val vector = CORE_ICONS[k]
        when {
            res != 0 -> Icon(painterResource(res), contentDescription = null, tint = Color.White, modifier = Modifier.size(inner))
            vector != null -> Icon(vector, contentDescription = null, tint = Color.White, modifier = Modifier.size(inner))
            else -> LearnMark(inner)
        }
    }
}
