import '../data/models.dart';

/// Hoechstlaenge des Profiltextes "Ueber mich" (wie im Backend).
const int aboutMax = 600;

/// Alter: erlaubt 14 bis 99, Standard 18 (Mindestalter 14).
const int ageMin = 14;
const int ageMax = 99;
const int ageDefault = 18;

/// Bildungsstand: Schluessel des Backends mit Anzeigetext.
const List<(String, String)> education = [
  ('hauptschule', 'Hauptschule'),
  ('realschule', 'Realschule'),
  ('abitur', 'Abitur'),
  ('ausbildung', 'Ausbildung'),
  ('studium', 'Studium'),
  ('other', 'Anderes'),
];

String educationLabel(String? key) {
  for (final e in education) {
    if (e.$1 == key) return e.$2;
  }
  return '–';
}

bool ageValid(int? age) => age == null || (age >= ageMin && age <= ageMax);

int clampAge(int age) => age.clamp(ageMin, ageMax);

/// Eingabe auf die Hoechstlaenge begrenzen (Einfuegen langer Texte schneidet ab).
String clampAbout(String text) =>
    text.length > aboutMax ? text.substring(0, aboutMax) : text;

/// Zaehler "12/600".
String aboutCounter(String text) => '${text.length}/$aboutMax';

/// Gueltig, wenn nicht laenger als [aboutMax]; leer ist erlaubt (kein Pflichtfeld).
bool aboutValid(String text) => text.length <= aboutMax;

/// Zu sendender Text: Raender weg, Zeilenumbrueche bleiben.
String normalizeAbout(String text) => text.trim();

/// Muss gespeichert werden? Nur bei Aenderung gegenueber dem gespeicherten Stand und wenn gueltig.
bool aboutNeedsSave(String saved, String current) =>
    aboutValid(current) && normalizeAbout(current) != normalizeAbout(saved);

/// Bearbeitungsstand des Profils in den Einstellungen.
class ProfileDraft {
  final int? age;
  final String? education;
  final String about;

  const ProfileDraft({this.age, this.education, this.about = ''});

  ProfileDraft copyWith({
    int? age,
    bool clearAge = false,
    String? education,
    bool clearEducation = false,
    String? about,
  }) =>
      ProfileDraft(
        age: clearAge ? null : (age ?? this.age),
        education: clearEducation ? null : (education ?? this.education),
        about: about ?? this.about,
      );

  @override
  bool operator ==(Object other) =>
      other is ProfileDraft &&
      other.age == age &&
      other.education == education &&
      other.about == about;

  @override
  int get hashCode => Object.hash(age, education, about);
}

/// Bestehende Werte unter dem Mindestalter werden auf 14 angehoben.
ProfileDraft draftOf(Profile p) => ProfileDraft(
      age: p.age == null ? null : clampAge(p.age!),
      education: p.educationLevel,
      about: p.about,
    );

bool draftValid(ProfileDraft d) => ageValid(d.age) && aboutValid(d.about);

bool draftNeedsSave(ProfileDraft saved, ProfileDraft cur) =>
    draftValid(cur) &&
    (cur.age != saved.age ||
        cur.education != saved.education ||
        normalizeAbout(cur.about) != normalizeAbout(saved.about));

// ---------------- Onboarding

/// Ob das Onboarding laeuft: wird nach dem Login aus dem Profil bestimmt.
enum OnboardingGate { unknown, needed, done, failed }

/// Nur wenn das Profil geladen ist und onboardingDone false ist, kommt das Onboarding; bei Netzfehler Failed (Retry), nie endlos blockierend.
OnboardingGate gateFor(Profile? profile) {
  if (profile == null) return OnboardingGate.failed;
  return profile.onboardingDone ? OnboardingGate.done : OnboardingGate.needed;
}

/// Seiten des Onboardings in fester Reihenfolge.
enum OnboardingPage { welcome, age, education, voice }

OnboardingPage? nextPage(OnboardingPage p) {
  final next = p.index + 1;
  return next < OnboardingPage.values.length
      ? OnboardingPage.values[next]
      : null;
}

OnboardingPage? previousPage(OnboardingPage p) {
  final prev = p.index - 1;
  return prev >= 0 ? OnboardingPage.values[prev] : null;
}

/// Begruessung mit dem Vornamen aus dem Google-Profil.
String greeting(String? name) {
  final trimmed = name?.trim() ?? '';
  if (trimmed.isEmpty) return 'Hi';
  final first = trimmed.split(' ').first;
  return first.isNotEmpty ? 'Hi $first' : 'Hi';
}

/// Aufnahme: hoechstens 60 Sekunden; Anzeige "0:07".
const int recordMaxSeconds = 60;

String recordTime(int seconds) =>
    '${seconds ~/ 60}:${(seconds % 60).toString().padLeft(2, '0')}';

/// Ausgang einer Sprachnachricht: Text, "nichts verstanden" (422), nicht verfuegbar (503) oder sonstiger Fehler.
sealed class VoiceOutcome {
  const VoiceOutcome();
}

class VoiceText extends VoiceOutcome {
  final String about;
  const VoiceText(this.about);
}

class VoiceNothingHeard extends VoiceOutcome {
  const VoiceNothingHeard();
}

class VoiceUnavailable extends VoiceOutcome {
  const VoiceUnavailable();
}

class VoiceFailed extends VoiceOutcome {
  const VoiceFailed();
}

VoiceOutcome voiceOutcomeForStatus(int? code) => switch (code) {
      422 => const VoiceNothingHeard(),
      503 => const VoiceUnavailable(),
      _ => const VoiceFailed(),
    };

/// Ruhiger Hinweis zum Ausgang (ohne Technik); null bei Erfolg.
String? voiceOutcomeMessage(VoiceOutcome o) => switch (o) {
      VoiceText() => null,
      VoiceNothingHeard() => 'Nichts verstanden. Nochmal aufnehmen?',
      VoiceUnavailable() => 'Gerade nicht verfügbar. Du kannst auch tippen.',
      VoiceFailed() => 'Das hat nicht geklappt. Nochmal versuchen?',
    };

/// Aufnahme als AAC (ADTS): Dateiendung und Content-Type fuer den Upload.
const String voiceFileExt = 'aac';
const String voiceMime = 'audio/aac';
