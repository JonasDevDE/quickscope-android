package com.jonasschnell.quickscope.logic

/** "Verstehen" ist sichtbar, sobald es Lektionen gibt (online aus der Planung, offline aus dem Offline-Paket); Audio spielt dabei keine Rolle. */
fun understandVisible(hasLessons: Boolean): Boolean = hasLessons

/** Ton nachtraeglich: laeuft im Hintergrund der Preflight, wird der Audiomodus erst danach (und nur bei An, online, verfuegbar) eingeschaltet. */
fun audioAfterCheck(enabled: Boolean, online: Boolean, available: Boolean): Boolean = enabled && online && available

/** In "Verstehen" blaettert die Seite nach dem Ende des Streams von selbst weiter; ohne Ton bleibt sie lesbar mit "Weiter". */
fun autoAdvance(lessonsOnly: Boolean, finishedPlaying: Boolean): Boolean = lessonsOnly && finishedPlaying
