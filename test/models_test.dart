import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/data/live_sync.dart';
import 'package:quickscope/data/models.dart';

void main() {
  test('decodesMeWithMissingFields', () {
    final me = Me.fromJson(
      jsonDecode('{"id":"u1","email":"a@b.de","unbekannt":1}')
          as Map<String, dynamic>,
    );
    expect(me.id, 'u1');
    expect(me.email, 'a@b.de');
    expect(me.name, isNull);
    expect(me.pictureUrl, isNull);
  });

  test('decodesLiveChange', () {
    final change = LiveChange.fromJson(
      jsonDecode('{"kind":"me","by":"u2"}') as Map<String, dynamic>,
    );
    expect(change.kind, 'me');
    expect(change.by, 'u2');
  });

  test('toleriertNeueBackendFelder', () {
    // Neu im Vertrag: forecast.recallPercent, forecast.time, Session.topicsCovered, Status "processing"
    final session = LearnSession.fromJson(
      jsonDecode(
        '{"items":[],"topicsCovered":4,"forecast":{"scoreNowPercent":30,'
        '"recallPercent":15,"time":{"needSeconds":1354,'
        '"remainingTimeToExamSeconds":50392,"recommendedMinutesToday":29,'
        '"coveragePercentAtBudget":50,"tight":true,"note":"Schätzung"}}}',
      ) as Map<String, dynamic>,
    );
    expect(session.forecast?.scoreNowPercent, 30);
    // auch mit unerwarteten Typen kein Absturz
    final odd = Forecast.fromJson(
      jsonDecode('{"recallPercent":"x","time":[1,2],"scoreNowPercent":"12"}')
          as Map<String, dynamic>,
    );
    expect(odd.scoreNowPercent, 12);
    final exam = Exam.fromJson(
      jsonDecode('{"id":"e1","name":"N","status":"processing"}')
          as Map<String, dynamic>,
    );
    expect(exam.status, 'processing');
  });
}
