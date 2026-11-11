import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/data/models.dart';
import 'package:quickscope/logic/highlight.dart';

void main() {
  const text = 'Was ist Zins';
  const words = [
    WordTiming(start: 0, end: 3, fromMs: 100, toMs: 300),
    WordTiming(start: 4, end: 7, fromMs: 300, toMs: 500),
    WordTiming(start: 8, end: 12, fromMs: 700, toMs: 1000),
  ];

  test('currentWordFollowsTime', () {
    expect(wordPosAt(words, 0), const WordPos(0, -1));
    expect(wordPosAt(words, 99), const WordPos(0, -1));
    expect(wordPosAt(words, 100), const WordPos(0, 0));
    expect(wordPosAt(words, 299), const WordPos(0, 0));
    expect(wordPosAt(words, 300), const WordPos(1, 1));
    expect(wordPosAt(words, 500), const WordPos(2, -1));
    expect(wordPosAt(words, 699), const WordPos(2, -1));
    expect(wordPosAt(words, 700), const WordPos(2, 2));
    expect(wordPosAt(words, 1000), const WordPos(3, -1));
    expect(wordPosAt(words, 99999), const WordPos(3, -1));
    expect(wordPosAt(const [], 500), const WordPos(0, -1));
  });

  test('timingsMustMatchDisplayedTextExactly', () {
    const t = AudioTimings(text: text, durationMs: 1000, words: words);
    expect(timingsMatch(text, t), isTrue);
    expect(timingsMatch('Was ist Zins?', t), isFalse);
    expect(timingsMatch('was ist Zins', t), isFalse);
    expect(
      timingsMatch(text, const AudioTimings(text: text, durationMs: 1000)),
      isFalse,
    );
    expect(
      timingsMatch(
        text,
        const AudioTimings(
          text: text,
          durationMs: 1000,
          words: [WordTiming(start: 0, end: 99, fromMs: 0, toMs: 10)],
        ),
      ),
      isFalse,
    );
    expect(
      timingsMatch(
        text,
        const AudioTimings(
          text: text,
          durationMs: 1000,
          words: [
            WordTiming(start: 4, end: 7, fromMs: 0, toMs: 10),
            WordTiming(start: 0, end: 3, fromMs: 10, toMs: 20),
          ],
        ),
      ),
      isFalse,
    );
    expect(
      timingsMatch(
        text,
        const AudioTimings(
          text: text,
          durationMs: 1000,
          words: [WordTiming(start: 0, end: 3, fromMs: 50, toMs: 10)],
        ),
      ),
      isFalse,
    );
  });

  test('deviceVoiceMapsCharactersToWords', () {
    final w = splitWords('Was ist  Zins?');
    expect(
      w.map((e) => (e.start, e.end)).toList(),
      const [(0, 3), (4, 7), (9, 14)],
    );
    expect(wordAtChar(w, 0), 0);
    expect(wordAtChar(w, 4), 1);
    expect(wordAtChar(w, 7), 2);
    expect(wordAtChar(w, 9), 2);
    expect(wordAtChar(w, 14), -1);
  });

  group('fetchMatchingTimings', () {
    const good = AudioTimings(text: text, words: words);
    const bad = AudioTimings(text: 'anderer Text', words: words);
    const unit = Duration(milliseconds: 1);

    test('wiederholt bei fehlender Antwort und Abweichung, max. 3 Mal', () async {
      var calls = 0;
      final fails = <String>[];
      final r = await fetchMatchingTimings(
        () async {
          calls++;
          return calls == 1 ? null : (calls == 2 ? bad : good);
        },
        text,
        unit: unit,
        onFail: fails.add,
      );
      expect(r, good);
      expect(calls, 3);
      expect(fails.length, 2);
      expect(fails[0], contains('keine Antwort'));
      expect(fails[1], contains('passen nicht'));
    });

    test('gibt nach 3 Fehlversuchen auf', () async {
      var calls = 0;
      final fails = <String>[];
      final r = await fetchMatchingTimings(
        () async {
          calls++;
          throw Exception('x');
        },
        text,
        unit: unit,
        onFail: fails.add,
      );
      expect(r, isNull);
      expect(calls, 3);
      expect(fails.length, 3);
    });

    test('Erfolg beim ersten Versuch ohne Wiederholung', () async {
      var calls = 0;
      final r = await fetchMatchingTimings(
        () async {
          calls++;
          return good;
        },
        text,
        unit: unit,
      );
      expect(r, good);
      expect(calls, 1);
    });

    test('Abbruch stoppt weitere Versuche', () async {
      var calls = 0;
      final r = await fetchMatchingTimings(
        () async {
          calls++;
          return null;
        },
        text,
        unit: unit,
        cancelled: () => calls >= 1,
      );
      expect(r, isNull);
      expect(calls, 1);
    });
  });
}
