# Quickscope

Quickscope ist eine Lern-App für Prüfungsvorbereitung für Leute mit wenig Zeit. Du gibst Prüfungsname, Datum, Uhrzeit und Material ein, den Rest übernimmt die KI: Themen, kurze Aufgaben, ein Lernplan und eine Prognose. Gelernt wird mit Wischen und Tippen, ohne lange Texte.

Dieses Repository enthält die **Android-App** (Kotlin, Jetpack Compose). Das Backend ist nicht Teil dieses Repositories.

## Funktionen der App

- Anmeldung mit Google (Credential Manager) und langlebige Sitzung
- Schwarzes, ruhiges Design mit Skeleton-Ladeanzeige und Haptik
- Pflicht-Updates: Die App prüft beim Start, im Vordergrund und per Live-Ereignis auf neue Versionen und installiert sie
- Live-Sync über Server-Sent Events

## Bauen

Voraussetzungen: JDK 21, Android SDK (compileSdk 37).

```
./gradlew :app:assembleRelease
```

Lege eine Datei `local.properties` an (nicht eingecheckt):

```
sdk.dir=<Pfad zum Android SDK>
GOOGLE_WEB_CLIENT_ID=<Web-Client-ID aus deiner Google Cloud Console>
BASE_URL=https://dein-backend.example/
```

Für die Google-Anmeldung brauchst du in deinem Google-Cloud-Projekt einen Web-Client und einen Android-OAuth-Client mit dem Paketnamen deiner App und dem SHA-1 deines Signaturschlüssels. Standardmäßig wird mit dem Debug-Schlüssel signiert; für eigene Releases kannst du `QUICKSCOPE_SIGNING_PROPERTIES` auf eine Datei mit `storeFile`, `storePassword`, `keyAlias` und `keyPassword` zeigen lassen.

## Backend

Die App erwartet ein Backend mit diesen Endpunkten:

- `GET /api/me`, `DELETE /api/me`
- `POST /api/auth/session` (tauscht ein Google-ID-Token gegen ein Sitzungstoken)
- `GET /api/app/latest` (Versionsinformation für Updates), `GET /api/app/latest/apk`
- `GET /api/stream` (Server-Sent Events: `hello`, `change`, `update`)
- Bei zu alter App-Version (Header `X-App-Version`) antwortet das Backend mit HTTP 426

## Lizenz

MIT, siehe `LICENSE`.
