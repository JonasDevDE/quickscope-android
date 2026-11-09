# Quickscope

Quickscope ist eine Lern-App für Prüfungsvorbereitung für Leute mit wenig Zeit. Du gibst Prüfungsname, Datum, Uhrzeit und Material ein, den Rest übernimmt die KI: Themen, kurze Aufgaben, ein Lernplan und eine Prognose. Gelernt wird mit Wischen und Tippen, ohne lange Texte.

Dieses Repository enthält die **Android-App** (Kotlin, Jetpack Compose).

## Funktionen der App

- Anmeldung mit Google und langlebige Sitzung
- Schwarzes, ruhiges Design mit Skeleton-Ladeanzeige und Haptik
- Pflicht-Updates: Die App prüft beim Start, im Vordergrund und per Live-Ereignis auf neue Versionen
- Live-Sync

## Bauen

Voraussetzungen: JDK 21, Android SDK (compileSdk 37).

```
./gradlew :app:assembleRelease
```

## Lizenz

MIT, siehe `LICENSE`.

---

Powered by Darkstone Cloud
