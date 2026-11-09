package com.jonasschnell.quickscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Die eine Startseite: waehrend des Ladens Skeletons (Shimmer), danach der Inhalt (derzeit nur der Leer-Hinweis). */
@Composable
fun HomePage(loading: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) {
            SkeletonCard(110.dp)
            SkeletonCard(70.dp)
            SkeletonCard(70.dp)
        } else {
            EmptyHint("Noch nichts hier")
        }
    }
}
