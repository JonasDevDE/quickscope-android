# Quickscope

Quickscope ist eine Lern-App für Prüfungsvorbereitung für Leute mit wenig Zeit. Du gibst Prüfungsname, Datum, Uhrzeit und Material ein, den Rest übernimmt die KI: Themen, kurze Aufgaben, ein Lernplan und eine Prognose. Gelernt wird mit Wischen und Tippen, ohne lange Texte.

Dieses Repository enthält die **Quickscope-App**. Auf dem Branch `flutter` ist das der Flutter-Stand (Android und iOS, Port von Tjorven Liebe, MIT). Die native Kotlin/Compose-App liegt unverändert auf `main` (Tag `native-v0.6.4`).

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

## Flutter-Stand

Branch `flutter`, Version 0.7.0 (versionCode 31). Die Version steht in `pubspec.yaml` (`version: 0.7.0+31`) und muss zu `lib/config.dart` passen (`test/version_test.dart` prüft das). Der Server sperrt Apps mit niedrigerem `X-App-Version` als der zuletzt veröffentlichten.

Voraussetzung: Flutter stable mit Dart `^3.8.0`, JDK 17, Android SDK.

```
flutter pub get
flutter analyze
flutter test
flutter build apk --release
```

dart-defines (alle optional, Defaults in `lib/config.dart`):

- `GOOGLE_WEB_CLIENT_ID` (Web-Client-ID für den ID-Token)
- `GOOGLE_IOS_CLIENT_ID` (iOS-Client-ID)
- `BASE_URL` (Backend, Standard `http://fast-hawk.cloudgate.darkstone.de:40029/`)
- `DISABLE_AUTO_UPDATE=true` (nur Entwicklung: Updater und Versionszwang aus)

Signing Android: `android/key.properties` (lokal, nicht im Repo) mit `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Ohne diese Datei schlägt jeder Release-Build absichtlich fehl, es wird nie mit dem Debug-Key signiert.

Veröffentlichen als Update: `scripts/publish.sh "Notizen"` baut mit `flutter build apk --release`, liest die Version aus `pubspec.yaml` und lädt `build/app/outputs/flutter-apk/app-release.apk` mit `X-Publish-Key` auf das Backend (Endpunkte siehe API.md, App-Updates). Die App prüft per `GET /api/app/latest`.

CI (`.github/workflows`): Secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS`, optional `GOOGLE_WEB_CLIENT_ID`, `GOOGLE_IOS_CLIENT_ID`, `BASE_URL`, `PLAY_STORE_JSON_KEY`; für iOS `IOS_DISTRIBUTION_CERT_P12_BASE64`, `IOS_DISTRIBUTION_CERT_PASSWORD`, `IOS_PROVISIONING_PROFILE_BASE64`.

Bundle-IDs: Android `com.jonasschnell.quickscope`, iOS `com.quickscopelearn` (Tests `com.quickscopelearn.RunnerTests`). iOS nutzt Sign in with Apple (`Runner.entitlements`).

## Lizenz

MIT, siehe `LICENSE`.

---

Powered by Darkstone Cloud
