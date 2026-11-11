import 'package:shared_preferences/shared_preferences.dart';
import '../logic/attempt_queue.dart';

/// Kleine lokale Einstellungen der App.
class Store implements QueueStorage {
  final SharedPreferences _prefs;

  Store(this._prefs);

  /// Wurde der Nutzer schon einmal zur Erlaubnis "Apps installieren" geleitet?
  bool get askedInstallPermission => _prefs.getBool('askedInstall') ?? false;
  set askedInstallPermission(bool value) =>
      _prefs.setBool('askedInstall', value);

  /// Zuletzt gewaehlte Pruefung.
  String? get chosenExamId => _prefs.getString('chosenExam');
  set chosenExamId(String? value) => value == null
      ? _prefs.remove('chosenExam')
      : _prefs.setString('chosenExam', value);

  /// Vorlesen an/aus (Standard an). Bei aus gibt es keinerlei Audio-Aufrufe.
  bool get audioEnabled => _prefs.getBool('audioEnabled') ?? true;
  set audioEnabled(bool value) => _prefs.setBool('audioEnabled', value);

  /// Drosselung der Fehlermeldungen ans Backend: Zeitpunkt der letzten, Tag und Anzahl heute.
  int get logLastMs => _prefs.getInt('logLast') ?? 0;
  set logLastMs(int value) => _prefs.setInt('logLast', value);

  String? get logDay => _prefs.getString('logDay');
  set logDay(String? value) => value == null
      ? _prefs.remove('logDay')
      : _prefs.setString('logDay', value);

  int get logCount => _prefs.getInt('logCount') ?? 0;
  set logCount(int value) => _prefs.setInt('logCount', value);

  /// "Vorlesen" wurde offline geaendert und muss noch ins Profil geschrieben werden.
  bool get audioDirty => _prefs.getBool('audioDirty') ?? false;
  set audioDirty(bool value) => _prefs.setBool('audioDirty', value);

  /// Tagesserie (lokal abgeleitet): letzter Tag mit abgeschlossener Runde (ISO) und Tage in Folge.
  String? get lastSessionDay => _prefs.getString('lastSessionDay');
  set lastSessionDay(String? value) => value == null
      ? _prefs.remove('lastSessionDay')
      : _prefs.setString('lastSessionDay', value);

  int get dayStreak => _prefs.getInt('dayStreak') ?? 0;
  set dayStreak(int value) => _prefs.setInt('dayStreak', value);

  /// Alles lokal Gemerkte loeschen (Abmelden, Konto loeschen): Einstellungen und die Antwort-Warteschlange.
  Future<void> clearAll() async {
    await _prefs.clear();
  }

  @override
  String? read(String key) => _prefs.getString('q_$key');

  @override
  void write(String key, String? value) {
    if (value == null) {
      _prefs.remove('q_$key');
    } else {
      _prefs.setString('q_$key', value);
    }
  }
}
