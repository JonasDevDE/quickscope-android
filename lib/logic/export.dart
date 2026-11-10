/// Streamt den Datenexport in die vom Nutzer gewaehlte Datei und liefert die Anzahl Bytes.
Future<int> copyExport(Stream<List<int>> input, Sink<List<int>> out) async {
  var total = 0;
  await for (final chunk in input) {
    out.add(chunk);
    total += chunk.length;
  }
  return total;
}

enum ExportOutcome { saved, failed }

/// Gespeichert nur, wenn wirklich Daten geschrieben wurden; null (Fehler) oder 0 Bytes gelten als Fehlschlag.
ExportOutcome exportOutcome(int? bytes) =>
    (bytes != null && bytes > 0) ? ExportOutcome.saved : ExportOutcome.failed;

/// Fehlergrund fuer das Debug-Log (nie fuer den Nutzer): Klasse, HTTP-Status oder Meldung.
String exportFailureReason(Object e) => '${e.runtimeType}: $e';
