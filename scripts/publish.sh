#!/usr/bin/env bash
# Baut das signierte Flutter-Release-APK und veroeffentlicht es als Update auf dem Quickscope-Backend (Git Bash).
# Nutzung: scripts/publish.sh ["Release-Notizen"]
# Schluessel: Env-Var QUICKSCOPE_PUBLISH_KEY, sonst Datei ../.publish-key (relativ zu android/). Wird nie ausgegeben.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${QUICKSCOPE_PUBLISH_BASE:-https://quickscope.invalid/api/app}"
PUBSPEC="$REPO/pubspec.yaml"
NOTES="${1:-${QUICKSCOPE_RELEASE_NOTES:-}}"

# Flutter-Stand: Version aus pubspec.yaml (version: NAME+CODE), einzige Quelle.
PUBVER="$(sed -n 's/^version:[[:space:]]*//p' "$PUBSPEC" | head -n1 | tr -d '\r ')"
VERSION_NAME="${PUBVER%%+*}"
VERSION_CODE="${PUBVER#*+}"
if [ -z "$VERSION_NAME" ] || ! [[ "$VERSION_CODE" =~ ^[0-9]+$ ]]; then
  echo "Fehler: version (NAME+CODE) konnte nicht aus pubspec.yaml gelesen werden." >&2
  exit 1
fi

KEY="${QUICKSCOPE_PUBLISH_KEY:-}"
if [ -z "$KEY" ] && [ -f "$REPO/../.publish-key" ]; then
  KEY="$(tr -d '\r\n ' < "$REPO/../.publish-key")"
fi
if [ -z "$KEY" ]; then
  echo "Fehler: Kein Veroeffentlichungsschluessel. QUICKSCOPE_PUBLISH_KEY setzen oder Datei ../.publish-key anlegen." >&2
  exit 1
fi

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(ls -d "$HOME"/.jdks/corretto-21* 2>/dev/null | sort | tail -n1 || true)"
  if [ -z "$JAVA_HOME" ]; then
    echo "Fehler: Kein Corretto 21 unter $HOME/.jdks gefunden und JAVA_HOME nicht gesetzt." >&2
    exit 1
  fi
  export JAVA_HOME
fi

if [ ! -f "$REPO/android/key.properties" ]; then
  echo "Fehler: android/key.properties fehlt. Release-APKs werden nie mit dem Debug-Key veroeffentlicht." >&2
  exit 1
fi
FLUTTER_BIN="${FLUTTER:-flutter}"
command -v "$FLUTTER_BIN" >/dev/null 2>&1 || { echo "Fehler: flutter nicht gefunden (PATH oder Env-Var FLUTTER setzen)." >&2; exit 1; }

# Optionale dart-defines aus der Umgebung (Defaults stehen in lib/config.dart).
DEFINES=()
[ -n "${GOOGLE_WEB_CLIENT_ID:-}" ] && DEFINES+=("--dart-define=GOOGLE_WEB_CLIENT_ID=$GOOGLE_WEB_CLIENT_ID")
[ -n "${GOOGLE_IOS_CLIENT_ID:-}" ] && DEFINES+=("--dart-define=GOOGLE_IOS_CLIENT_ID=$GOOGLE_IOS_CLIENT_ID")
[ -n "${BASE_URL:-}" ] && DEFINES+=("--dart-define=BASE_URL=$BASE_URL")

echo "Baue Version $VERSION_NAME (versionCode $VERSION_CODE) ..."
(cd "$REPO" && "$FLUTTER_BIN" build apk --release ${DEFINES[@]+"${DEFINES[@]}"})

APK="$REPO/build/app/outputs/flutter-apk/app-release.apk"
[ -f "$APK" ] || { echo "Fehler: APK nicht gefunden: $APK" >&2; exit 1; }

SIZE="$(stat -c%s "$APK" 2>/dev/null || wc -c < "$APK")"
SIZE="$(echo "$SIZE" | tr -d ' \r\n')"
echo "Lade hoch ($SIZE Bytes) ..."

RESPONSE="$(mktemp)"
CURL_CONFIG="$(mktemp)"
WORK="$(mktemp -d)"
trap 'rm -rf "$RESPONSE" "$CURL_CONFIG" "$WORK"' EXIT
# Schluessel per Config-Datei statt Argument, damit er nicht in der Prozessliste auftaucht.
chmod 600 "$CURL_CONFIG"
printf 'header = "X-Publish-Key: %s"\n' "$KEY" > "$CURL_CONFIG"
CURL_CONFIG_W="$(cygpath -m "$CURL_CONFIG" 2>/dev/null || echo "$CURL_CONFIG")"

fail_http() { # $1 = Schritt, $2 = HTTP-Code
  case "$2" in
    409) echo "Fehler: Version $VERSION_CODE existiert bereits. Bitte version in pubspec.yaml und AppConfig (lib/config.dart) erhoehen." >&2 ;;
    403) echo "Fehler: Ungueltiger Veroeffentlichungsschluessel (HTTP 403)." >&2 ;;
    *) echo "Fehler bei '$1': Server antwortete mit HTTP $2." >&2; head -c 300 "$RESPONSE" >&2; echo >&2 ;;
  esac
  exit 1
}

# 0. Direkter Upload in einem Stueck (ein Proxy kann das Limit fuer Anfragegroessen setzen); bei Fehler weiter mit Stuecken.
APK_W="$(cygpath -m "$APK" 2>/dev/null || echo "$APK")"
DIRECT_ARGS=(-F "file=@$APK_W;type=application/vnd.android.package-archive" -F "versionCode=$VERSION_CODE" --form-string "versionName=$VERSION_NAME")
[ -n "$NOTES" ] && DIRECT_ARGS+=(--form-string "notes=$NOTES")
HTTP="$(curl -sS --max-time 600 -K "$CURL_CONFIG_W" -o "$RESPONSE" -w '%{http_code}' -X POST "${DIRECT_ARGS[@]}" "$BASE/releases" 2>/dev/null)" || HTTP="000"
case "$HTTP" in
  201|200) echo "Veroeffentlicht: v$VERSION_NAME (versionCode $VERSION_CODE)."; exit 0 ;;
  403|409) fail_http "Veroeffentlichen" "$HTTP" ;;
  *) echo "Direkter Upload nicht moeglich (HTTP $HTTP), lade in Stuecken hoch ..." ;;
esac

# 1. Upload anlegen
HTTP="$(curl -sS -K "$CURL_CONFIG_W" -o "$RESPONSE" -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' -d "{\"size\":$SIZE}" "$BASE/release-uploads")" || { echo "Fehler: Verbindung zum Server fehlgeschlagen (Upload anlegen)." >&2; exit 1; }
[ "$HTTP" = "201" ] || fail_http "Upload anlegen" "$HTTP"
UPLOAD_ID="$(sed -n 's/.*"uploadId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$RESPONSE" | head -n1)"
CHUNK_SIZE="$(sed -n 's/.*"chunkSize"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$RESPONSE" | head -n1)"
if [ -z "$UPLOAD_ID" ]; then echo "Fehler: Antwort ohne uploadId." >&2; exit 1; fi
# Nie groesser als der Vertrag (786432) und nie groesser als vom Server vorgegeben.
if [ -z "$CHUNK_SIZE" ] || [ "$CHUNK_SIZE" -gt 786432 ]; then CHUNK_SIZE=786432; fi

# 2. Stuecke in einem Temp-Verzeichnis ablegen und einzeln senden (relative Namen: Git-Bash/curl-Pfadproblem).
cp "$CURL_CONFIG" "$WORK/.curlcfg"
split -b "$CHUNK_SIZE" -d -a 5 "$APK" "$WORK/chunk_"
cd "$WORK"
TOTAL="$(ls chunk_* | wc -l | tr -d '\r ')"
INDEX=0
for PART in chunk_*; do
  ATTEMPT=1
  while true; do
    HTTP="$(curl -sS -K .curlcfg -o "$RESPONSE" -w '%{http_code}' -X PUT -H 'Content-Type: application/octet-stream' \
      --data-binary "@$PART" "$BASE/release-uploads/$UPLOAD_ID/chunks/$INDEX" || true)"
    [ "$HTTP" = "204" ] && break
    case "$HTTP" in 403) fail_http "Stueck $((INDEX + 1))" "$HTTP" ;; esac
    if [ "$ATTEMPT" -ge 3 ]; then fail_http "Stueck $((INDEX + 1)) von $TOTAL" "${HTTP:-000}"; fi
    echo "Stueck $((INDEX + 1)) von $TOTAL fehlgeschlagen (HTTP ${HTTP:-000}), neuer Versuch ..." >&2
    ATTEMPT=$((ATTEMPT + 1))
    sleep $((ATTEMPT * 2))
  done
  INDEX=$((INDEX + 1))
  printf '\r  %s/%s Stuecke' "$INDEX" "$TOTAL"
done
echo

# 3. Veroeffentlichen
if [ -n "$NOTES" ]; then
  BS='\'
  ESC_NOTES="$NOTES"
  ESC_NOTES="${ESC_NOTES//"$BS"/"$BS$BS"}"
  ESC_NOTES="${ESC_NOTES//'"'/"$BS\""}"
  ESC_NOTES="${ESC_NOTES//$'\r'/}"
  ESC_NOTES="${ESC_NOTES//$'\n'/"${BS}n"}"
  ESC_NOTES="${ESC_NOTES//$'\t'/"${BS}t"}"
  NOTES_JSON="\"$ESC_NOTES\""
else
  NOTES_JSON="null"
fi
HTTP="$(curl -sS -K .curlcfg -o "$RESPONSE" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -d "{\"uploadId\":\"$UPLOAD_ID\",\"versionCode\":$VERSION_CODE,\"versionName\":\"$VERSION_NAME\",\"notes\":$NOTES_JSON}" \
  "$BASE/releases/from-upload")" || { echo "Fehler: Verbindung zum Server fehlgeschlagen (Veroeffentlichen)." >&2; exit 1; }
case "$HTTP" in
  201|200) echo "Veroeffentlicht: v$VERSION_NAME (versionCode $VERSION_CODE)." ;;
  *) fail_http "Veroeffentlichen" "$HTTP" ;;
esac
