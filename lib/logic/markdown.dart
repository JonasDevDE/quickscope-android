/// Die rechtlichen Seiten, als Texte in den App-Assets (`assets/legal/<datei>.md`).
enum LegalPage {
  privacy('datenschutz', 'Datenschutzerklärung'),
  terms('agb', 'Allgemeine Geschäftsbedingungen'),
  imprint('impressum', 'Impressum');

  final String file;
  final String title;
  const LegalPage(this.file, this.title);
}

/// Ein Stueck fett oder normal gesetzten Textes.
class MdSpan {
  final String text;
  final bool bold;
  const MdSpan(this.text, [this.bold = false]);

  @override
  bool operator ==(Object other) =>
      other is MdSpan && other.text == text && other.bold == bold;

  @override
  int get hashCode => Object.hash(text, bold);
}

/// Bausteine des kleinen Markdown-Light: Ueberschriften, Absaetze, Listenpunkte und graue Hinweiszeilen.
sealed class MdBlock {
  const MdBlock();
}

class MdH1 extends MdBlock {
  final String text;
  const MdH1(this.text);

  @override
  bool operator ==(Object other) => other is MdH1 && other.text == text;

  @override
  int get hashCode => text.hashCode;
}

class MdH2 extends MdBlock {
  final String text;
  const MdH2(this.text);

  @override
  bool operator ==(Object other) => other is MdH2 && other.text == text;

  @override
  int get hashCode => text.hashCode;
}

class MdPara extends MdBlock {
  final List<MdSpan> spans;
  const MdPara(this.spans);

  @override
  bool operator ==(Object other) =>
      other is MdPara &&
      other.spans.length == spans.length &&
      [for (var i = 0; i < spans.length; i++) spans[i] == other.spans[i]]
          .every((e) => e);

  @override
  int get hashCode => Object.hashAll(spans);
}

class MdBullet extends MdBlock {
  final List<MdSpan> spans;
  const MdBullet(this.spans);

  @override
  bool operator ==(Object other) =>
      other is MdBullet &&
      other.spans.length == spans.length &&
      [for (var i = 0; i < spans.length; i++) spans[i] == other.spans[i]]
          .every((e) => e);

  @override
  int get hashCode => Object.hashAll(spans);
}

class MdNote extends MdBlock {
  final String text;
  const MdNote(this.text);

  @override
  bool operator ==(Object other) => other is MdNote && other.text == text;

  @override
  int get hashCode => text.hashCode;
}

/// **fett** in Spans zerlegen; ein nicht geschlossenes ** bleibt als normaler Text stehen.
List<MdSpan> parseSpans(String text) {
  final out = <MdSpan>[];
  var i = 0;
  var bold = false;
  while (i < text.length) {
    final at = text.indexOf('**', i);
    if (at < 0) {
      out.add(MdSpan(text.substring(i), bold));
      break;
    }
    if (bold || text.indexOf('**', at + 2) >= 0) {
      if (at > i) out.add(MdSpan(text.substring(i, at), bold));
      bold = !bold;
      i = at + 2;
    } else {
      out.add(MdSpan(text.substring(i), bold));
      break;
    }
  }
  return [for (final s in out) if (s.text.isNotEmpty) s];
}

/// Zeilenweise: `# ` und `## ` Ueberschriften, `- ` Listenpunkt, `~ ` kleine graue Zeile, Leerzeile beendet den Absatz;
/// aufeinanderfolgende Textzeilen bilden einen Absatz (Zeilenumbrueche bleiben).
List<MdBlock> parseMarkdown(String src) {
  final blocks = <MdBlock>[];
  final para = <String>[];
  void flush() {
    if (para.isNotEmpty) {
      blocks.add(MdPara(parseSpans(para.join('\n'))));
      para.clear();
    }
  }

  for (final raw in src.split(RegExp(r'\r?\n'))) {
    final line = raw.trim();
    if (line.isEmpty) {
      flush();
    } else if (line.startsWith('## ')) {
      flush();
      blocks.add(MdH2(line.substring(3).trim()));
    } else if (line.startsWith('# ')) {
      flush();
      blocks.add(MdH1(line.substring(2).trim()));
    } else if (line.startsWith('- ')) {
      flush();
      blocks.add(MdBullet(parseSpans(line.substring(2).trim())));
    } else if (line.startsWith('~ ')) {
      flush();
      blocks.add(MdNote(line.substring(2).trim()));
    } else {
      para.add(line);
    }
  }
  flush();
  return blocks;
}
