import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/data/models.dart';
import 'package:quickscope/logic/forecast_text.dart';

void main() {
  Map<String, dynamic> map(String n) =>
      jsonDecode(File('test/resources/contract/$n').readAsStringSync())
          as Map<String, dynamic>;

  test('Forecast.time und recallPercent aus Sample', () {
    final f = Forecast.fromJson(map('forecast-urgent.json'));
    expect(f.time!.needSeconds, 1354);
    expect(f.time!.recommendedMinutesToday, 29);
    expect(f.time!.tight, isTrue);
    expect(f.time!.coveragePercentAtBudget, 50);
    final back = Forecast.fromJson(f.toJson());
    expect(back.time!.needSeconds, 1354);
  });

  test('fehlende oder kaputte Felder sind tolerant', () {
    final f = Forecast.fromJson({'time': 'x', 'recallPercent': 'abc'});
    expect(f.time, isNull);
    expect(f.recallPercent, isNull);
    final t = TimeNeed.fromJson({'needSeconds': 'x', 'tight': 'ja'});
    expect(t.needSeconds, isNull);
    expect(t.tight, isFalse);
    expect(Forecast.fromJson({'recallPercent': 40}).recallPercent, 40);
  });

  test('Session: topicsCovered nullable, Lektionen im crash-Fenster bleiben', () {
    final s = LearnSession.fromJson(map('session-urgent.json'));
    expect(s.forecast?.time?.tight, isTrue);
    expect(LearnSession.fromJson({'items': []}).topicsCovered, isNull);
    expect(LearnSession.fromJson({'topicsCovered': 4}).topicsCovered, 4);
    final c = LearnSession.fromJson(map('session-crash.json'));
    final all = filterModeCount(c);
    expect(all, c.items.length);
  });

  test('formatiereZeitbedarf', () {
    expect(
      formatiereZeitbedarf(const TimeNeed(needSeconds: 1354, recommendedMinutesToday: 29)),
      'Noch ca. 25 Min bis zur Prüfung nötig, heute empfohlen: 29 Min',
    );
    expect(
      formatiereZeitbedarf(const TimeNeed(needSeconds: 200, recommendedMinutesToday: 10)),
      'Noch ca. 4 Min bis zur Prüfung nötig, heute empfohlen: 10 Min',
    );
    expect(formatiereZeitbedarf(null), isNull);
    expect(formatiereZeitbedarf(const TimeNeed(recommendedMinutesToday: 10)), isNull);
    expect(formatiereZeitbedarf(const TimeNeed(needSeconds: 0, recommendedMinutesToday: 10)), isNull);
    expect(roundNeedMinutes(1), 1);
    expect(roundNeedMinutes(600), 10);
    expect(roundNeedMinutes(601), 15);
  });

  test('Knapp-Hinweis und Abruf-Text', () {
    expect(zeitKnappText(const TimeNeed(tight: true)), contains('knapp'));
    expect(zeitKnappText(const TimeNeed()), isNull);
    expect(zeitKnappText(null), isNull);
    expect(abrufText(null), isNull);
    expect(abrufText(42), startsWith('Abruf: 40 %'));
  });
}

int filterModeCount(LearnSession s) => s.items.length;
