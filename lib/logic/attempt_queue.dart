import 'dart:convert';
import '../data/models.dart';

/// Ablage fuer die Warteschlange (SharedPreferences in der App, Map im Test).
abstract interface class QueueStorage {
  String? read(String key);
  void write(String key, String? value);
}

/// Lokale Warteschlange fuer Antworten je Pruefung: Antworten werden zuerst abgelegt und erst nach erfolgreichem
/// Senden entfernt, damit nichts verloren geht, wenn das Netz fehlt oder die App beendet wird.
class AttemptQueue {
  final QueueStorage _storage;

  AttemptQueue(this._storage);

  String _key(String examId) => 'attempts_$examId';

  List<AttemptRequest> pending(String examId) {
    final raw = _storage.read(_key(examId));
    if (raw == null) return const [];
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return [
        for (final e in list)
          if (e is Map<String, dynamic>) AttemptRequest.fromJson(e),
      ];
    } catch (_) {
      return const [];
    }
  }

  void add(String examId, AttemptRequest attempt) {
    final list = pending(examId);
    if (attempt.clientId != null &&
        list.any((e) => e.clientId == attempt.clientId)) {
      return;
    }
    _save(examId, [...list, attempt]);
  }

  /// Entfernt die ersten [count] Eintraege (die gerade erfolgreich gesendeten); spaeter Hinzugekommene bleiben.
  void removeSent(String examId, int count) =>
      _save(examId, pending(examId).skip(count).toList());

  void _save(String examId, List<AttemptRequest> list) => _storage.write(
        _key(examId),
        list.isEmpty ? null : jsonEncode([for (final e in list) e.toJson()]),
      );
}
