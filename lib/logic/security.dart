import 'dart:typed_data';

/// IV und Chiffretext fuer die Ablage in einem Wert zusammenfuegen (IV vorne, Laenge im ersten Byte).
Uint8List packSecret(Uint8List iv, Uint8List cipherText) =>
    Uint8List.fromList([iv.length & 0xFF, ...iv, ...cipherText]);

/// Gegenstueck zu [packSecret]; null bei ungueltigen Daten.
(Uint8List, Uint8List)? unpackSecret(Uint8List data) {
  if (data.isEmpty) return null;
  final n = data[0] & 0xFF;
  if (n <= 0 || data.length <= 1 + n) return null;
  return (data.sublist(1, 1 + n), data.sublist(1 + n));
}

/// Alter Klartextwert vorhanden: muss verschluesselt abgelegt und der Klartext geloescht werden.
bool needsSecretMigration(String? plain) => plain != null;
