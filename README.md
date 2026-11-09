# Quickscope

Quickscope ist eine Lern-App für Prüfungsvorbereitung für Leute mit wenig Zeit. Du gibst Prüfungsname, Datum, Uhrzeit und Material ein, den Rest übernimmt die KI: Themen, kurze Aufgaben, ein Lernplan und eine Prognose. Gelernt wird mit Wischen und Tippen, ohne lange Texte.

Dieses Repository enthält die **Android-App** (Kotlin, Jetpack Compose).

## Funktionen der App

- Anmeldung mit Google und langlebige Sitzung
- Prüfungen mit Datum und Uhrzeit, Material per Foto, Galerie oder Datei, optional eine Beschreibung
- Heute-Ansicht mit Prognose-Ring (Schätzung, keine Garantie), Restzeit und einem Knopf für die passende Lernrunde
- Lernrunde mit einem Tipp oder Wisch pro Aufgabe: Karten, Auswahl, Lückentext, Zahlen, Fehler finden, Zuordnen, Reihenfolge
- Sofortiges Feedback, Lösungswege, Merkzettel für die letzte Runde, Audio-Modus mit Sprachausgabe
- Antworten werden offline vorgemerkt und später gesendet
- Material verwalten, Ziel und Lernzeit pro Tag einstellen
- Schwarzes, ruhiges Design mit Skeleton-Ladeanzeige und Haptik
- Pflicht-Updates: Die App prüft beim Start, im Vordergrund und per Live-Ereignis auf neue Versionen
- Live-Sync

## Download

Die aktuelle Android-App (APK) gibt es hier:

**http://fast-hawk.cloudgate.darkstone.de:40029/download**

Beim ersten Installieren fragt Android, ob dein Browser Apps installieren darf. Das musst du einmal erlauben. Danach aktualisiert sich die App selbst.

## Bauen

Voraussetzungen: JDK 21, Android SDK (compileSdk 37).

```
./gradlew :app:assembleRelease
```

## Lizenz

MIT, siehe `LICENSE`.

---

Powered by Darkstone Cloud
