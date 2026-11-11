import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/logic/export.dart';
import 'package:quickscope/logic/markdown.dart';

void main() {
  test('exportCopiesAllBytesAndCountsSaved', () async {
    final data = Uint8List.fromList(List.generate(300000, (i) => i % 251));
    final out = BytesBuilder(copy: false);
    final controller = StreamController<List<int>>(sync: true);
    controller.stream.listen(out.add);
    final stream = Stream<List<int>>.fromIterable([
      data.sublist(0, 100000),
      data.sublist(100000),
    ]);
    final n = await copyExport(stream, controller.sink);
    await controller.close();
    expect(n, 300000);
    expect(out.takeBytes(), data);
    expect(exportOutcome(n), ExportOutcome.saved);
  });

  test('emptyOrFailedExportIsAFailure', () {
    expect(exportOutcome(0), ExportOutcome.failed);
    expect(exportOutcome(null), ExportOutcome.failed);
    expect(
      exportFailureReason(const FileSystemException('kaputt')),
      contains('kaputt'),
    );
  });

  test('markdownRendersHeadingsListsAndBold', () {
    const md =
        '# Titel\n\n~ klein\n\n## Abschnitt\n\nText mit **fett** und [Platzhalter].\nzweite Zeile\n\n- eins\n\n- **zwei** x\n';
    final b = parseMarkdown(md);
    expect(b[0], const MdH1('Titel'));
    expect(b[1], const MdNote('klein'));
    expect(b[2], const MdH2('Abschnitt'));
    final para = b[3] as MdPara;
    expect(para.spans, const [
      MdSpan('Text mit '),
      MdSpan('fett', true),
      MdSpan(' und [Platzhalter].\nzweite Zeile'),
    ]);
    expect(b[4], const MdBullet([MdSpan('eins')]));
    expect(b[5], const MdBullet([MdSpan('zwei', true), MdSpan(' x')]));
    expect(b.length, 6);
    expect(parseSpans('a ** b'), const [MdSpan('a ** b')]);
  });

  test('legalPagesExistAndKeepPlaceholders', () {
    for (final page in LegalPage.values) {
      final f = File('assets/legal/${page.file}.md');
      expect(f.existsSync(), isTrue, reason: '${f.path} fehlt');
      final text = f.readAsStringSync();
      final blocks = parseMarkdown(text);
      expect(blocks.first, isA<MdH1>());
      expect(blocks.length, greaterThan(5));
      expect(text.contains('[Vor- und Nachname'), isFalse);
      expect(
        text.contains('[Anschrift]') || text.contains('[PLZ Ort]'),
        isFalse,
      );
    }
    expect(LegalPage.values.length, 3);
  });

  test('legalTextsAreFinalWithNameAndEmail', () {
    for (final page in LegalPage.values) {
      final t = File('assets/legal/${page.file}.md').readAsStringSync();
      expect(
        t.contains('[') || t.contains(']'),
        isFalse,
        reason: '${page.file}: eckige Klammern',
      );
      expect(t.contains('Entwurf'), isFalse, reason: '${page.file}: Entwurf');
      expect(t.contains('Jonas Schnell'), isTrue);
      expect(t.contains('Wuhlestraße 7a'), isTrue);
      expect(t.contains('12683 Berlin'), isTrue);
      expect(t.contains('jonas.schnell@darkstone.de'), isTrue);
    }
    final privacy = File('assets/legal/datenschutz.md').readAsStringSync();
    expect(privacy.contains('höchstens 7 Tage'), isTrue);
    expect(privacy.contains('ab 14 Jahren'), isTrue);
    expect(privacy.contains('TLS'), isFalse);
    expect(
      File('assets/legal/impressum.md').readAsStringSync().contains('Telefon'),
      isFalse,
    );
    expect(LegalPage.terms.title, 'Allgemeine Geschäftsbedingungen');
  });
}
