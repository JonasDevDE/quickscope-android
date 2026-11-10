import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/data/attempt_sync.dart';
import 'package:quickscope/data/models.dart';
import 'package:quickscope/data/network.dart';
import 'package:quickscope/logic/audio.dart';
import 'package:quickscope/logic/audiobook.dart';
import 'package:quickscope/logic/exams.dart';
import 'package:quickscope/logic/feedback.dart';
import 'package:quickscope/logic/forecast_text.dart';
import 'package:quickscope/logic/highlight.dart';
import 'package:quickscope/logic/offline.dart';
import 'package:quickscope/logic/profile.dart';
import 'package:quickscope/logic/tasks.dart';
import 'package:quickscope/logic/topic_icons.dart';

void main() {
  String raw(String name) =>
      File('test/resources/contract/$name').readAsStringSync();

  Map<String, dynamic> map(String name) =>
      jsonDecode(raw(name)) as Map<String, dynamic>;

  List<dynamic> list(String name) => jsonDecode(raw(name)) as List<dynamic>;

  List<Exam> exams() => [
        for (final e in list('exams.json'))
          Exam.fromJson(e as Map<String, dynamic>),
      ];

  test('me', () {
    final me = Me.fromJson(map('me.json'));
    expect(me.id, 'u1');
    expect(me.name, 'Lena Beispiel');
    expect(me.pictureUrl, 'https://example.org/lena.png');
  });

  test('examsInAllStates', () {
    final all = exams();
    final byId = {for (final e in all) e.id: e};
    expect(
      all.map((e) => e.status).toList(),
      const ['over', 'failed', 'processing', 'ready', 'empty'],
    );
    final ready = byId['e-ready']!;
    expect(ready.name, 'IHK Fachinformatiker AP2');
    expect(ready.date, '2026-11-30');
    expect(ready.time, '08:00');
    expect(ready.startsAt, '2026-11-30T07:00:00Z');
    expect(ready.hoursLeft, inInclusiveRange(400, 500));
    expect(ready.dailyMinutes, 30);
    expect(ready.itemCount, 13);
    expect(ready.materialCount, 5);
    expect(ready.daysLeft, 21);
    expect(
      ready.description!.startsWith('IHK Fachinformatiker AP2, Wirtschaft'),
      isTrue,
    );
    expect(
      byId['e-failed']!.error,
      'Im Material wurden keine Themen gefunden',
    );
    expect(ready.error, isNull);
  });

  test('singleAndCreatedExam', () {
    final exam = Exam.fromJson(map('exam.json'));
    expect(exam.id, 'e-ready');
    expect(exam.status, 'ready');
    final created = Exam.fromJson(map('exam-created.json'));
    expect(created.id, 'e-new');
    expect(created.status, 'processing');
    expect(created.time, '09:00');
    expect(created.description, 'Abitur Biologie, Schwerpunkt Genetik');
    expect(created.dailyMinutes, 30);
  });

  test('materials', () {
    final byId = {
      for (final m in list('materials.json'))
        MaterialItem.fromJson(m as Map<String, dynamic>).id:
            MaterialItem.fromJson(m),
    };
    expect(byId.length, 5);
    final scan = byId['m-scan']!;
    expect(scan.kind, 'scan');
    expect(scan.role, 'exam');
    expect(scan.pages, 3);
    expect(scan.warning, 'Seite 3 schwer lesbar');
    expect(scan.sizeBytes, 5100000);
    expect(byId['m-pdf']!.role, 'script');
    expect(byId['m-image']!.status, 'failed');
    expect(byId['m-image']!.error, 'KI nicht eingerichtet');
    expect(byId['m-audio']!.status, 'processing');
    expect(byId['m-audio']!.kind, 'audio');
    final created = MaterialItem.fromJson(map('material-created.json'));
    expect(created.id, 'm-new');
    expect(created.status, 'queued');
    expect(created.role, 'exam');
  });

  test('topics', () {
    final tList = [
      for (final t in list('topics.json'))
        Topic.fromJson(t as Map<String, dynamic>),
    ];
    final leaf = tList.firstWhere((t) => t.title == 'Zinsrechnung');
    expect(leaf.weight, closeTo(0.4, 0.0001));
    expect(leaf.level, 1);
    expect(leaf.mastery, closeTo(0.58, 0.0001));
    expect(leaf.parentId, isNotNull);
    final group = tList.firstWhere((t) => t.title == 'Rechnen');
    expect(group.level, 0);
    expect(group.parentId, isNull);
    final labels = topicLabels(tList);
    expect(labels[leaf.id], 'Rechnen › Zinsrechnung');
    expect(labels[group.id], 'Rechnen');
  });

  test('forecasts', () {
    final far = Forecast.fromJson(map('forecast-far.json'));
    expect(far.scoreNowPercent, 70);
    expect(far.coveragePercent, 65);
    expect(far.scoreAtExamPercent, 95);
    expect(far.daysLeft, 21);
    expect(far.hoursLeft, inInclusiveRange(400, 500));
    expect(far.weakTopics.length, 3);
    expect(far.weakTopics[0].title.trim().isNotEmpty, isTrue);
    expect(far.weakTopics[0].mastery, inInclusiveRange(0.0, 1.0));
    expect(
      far.projections.map((p) => p.minutes).toList(),
      const [10, 20, 30, 45, 60],
    );
    expect(far.projections[1].scoreAtExamPercent, 95);
    expect(far.confidence, 'medium');
    expect(far.note, 'Schätzung, keine Garantie');

    final proj = Forecast.fromJson(map('forecast-projections.json'));
    expect(proj.scoreNowPercent, 40);
    expect(proj.scoreAtExamPercent, 70);
    expect(
      proj.projections.firstWhere((p) => p.minutes == 10).scoreAtExamPercent,
      85,
    );
    expect(
      proj.projections.firstWhere((p) => p.minutes == 20).scoreAtExamPercent,
      95,
    );
    expect(
      projectionLine(proj, 20),
      'Bei 20 min täglich: ca. 70 % in der Prüfung, noch grob',
    );
    expect(minutesChoiceLabel(proj, 10), '10 min · ca. 85 %');

    final low = Forecast.fromJson(map('forecast-confidence-low.json'));
    expect(low.confidence, 'low');
    expect(low.scoreNowPercent, 55);
    expect(low.coveragePercent, 45);
    expect(
      Forecast.fromJson(map('forecast-confidence-medium.json')).confidence,
      'medium',
    );
    final high = Forecast.fromJson(map('forecast-confidence-high.json'));
    expect(high.confidence, 'high');
    expect(high.scoreNowPercent, 95);
    expect(high.coveragePercent, 100);

    final over = Forecast.fromJson(map('forecast-over.json'));
    expect(over.scoreNowPercent, 75);
    expect(over.daysLeft, 0);
    expect(over.projections, isEmpty);
  });

  test('streakDaysIsOptional', () {
    final without = Forecast.fromJson(map('forecast-far.json'));
    if (without.streakDays == null) {
      final withStreak = Forecast.fromJson(
        jsonDecode(
          '{"scoreNowPercent":50,"scoreAtExamPercent":70,"streakDays":7}',
        ) as Map<String, dynamic>,
      );
      expect(withStreak.streakDays, 7);
    } else {
      expect(without.streakDays!, greaterThanOrEqualTo(0));
    }
  });

  test('attemptsResponse', () {
    final r = AttemptsResponse.fromJson(map('attempts.json'));
    expect(r.forecast.scoreNowPercent, 55);
    expect(r.forecast.scoreAtExamPercent, 95);
    expect(r.doneSecondsToday, 0);
    expect(r.forecast.coveragePercent, 45);
    expect(r.forecast.projections.length, 5);
  });

  test('audioTimingsMatchTheQuestionAndDriveTheHighlight', () {
    final q = AudioTimings.fromJson(map('timings-question.json'));
    expect(q.text, 'Zins: 1.000 € bei 3 %, 1 Jahr?');
    expect(q.durationMs, 2200);
    expect(q.words.length, 8);
    expect(
      q.words[0],
      const WordTiming(start: 0, end: 5, fromMs: 200, toMs: 438),
    );
    expect(q.text.substring(q.words[0].start, q.words[0].end), 'Zins:');
    expect(q.text.substring(q.words.last.start, q.words.last.end), 'Jahr?');
    expect(timingsMatch('Zins: 1.000 € bei 3 %, 1 Jahr?', q), isTrue);
    expect(timingsMatch('Zins: 1.000 € bei 3 %, 1 Jahr', q), isFalse);
    expect(wordPosAt(q.words, 100), const WordPos(0, -1));
    expect(wordPosAt(q.words, 500), const WordPos(1, 1));
    expect(wordPosAt(q.words, 2200), const WordPos(8, -1));
    final a = AudioTimings.fromJson(map('timings-answer.json'));
    expect(a.text, 'Richtig: 30 €.');
    expect(a.words.length, 3);
    expect(timingsMatch(a.text, a), isTrue);
  });

  test('regenerateAndPrefetchResponses', () {
    final e = Exam.fromJson(map('regenerate.json'));
    expect(e.id, 'e-ready');
    expect(e.status, 'processing');
    expect(e.itemCount, 13);
    final queued = '${map('audio-prefetch.json')['queued']}';
    expect(queued, '2');
  });

  test('cheatsheet', () {
    final c = CheatSheet.fromJson(map('cheatsheet.json'));
    expect(c.minutes, 5);
    expect(c.words, 70);
    expect(c.bullets.length, 10);
    expect(c.bullets[0].topic, 'Zinsrechnung');
    expect(c.bullets[0].text.startsWith('Zinseszins berechnen'), isTrue);
    expect(c.note.startsWith('Nur aus deinem Material'), isTrue);
  });

  test('diagnosticSessionAllTypes', () {
    final s = LearnSession.fromJson(map('session-diagnostic.json'));
    expect(s.kind, 'diagnostic');
    expect(s.plannedSeconds, 85);
    expect(s.quiz.length, 8);
    expect(s.reason.startsWith('Einstufung: 8 Fragen'), isTrue);
    expect(s.finalRoundStart, isNull);
    expect(s.forecast, isNotNull);
    expect(
      s.quiz.map((e) => e.type).toSet(),
      const {
        'card',
        'cloze',
        'number',
        'match',
        'choice2',
        'mc',
        'truefalse',
      },
    );
    final tasks = {for (final it in s.quiz) it.id: parseTask(it)};
    expect(tasks['i-card'], isA<TaskCard>());
    final cloze = tasks['i-cloze'] as TaskCloze;
    expect(cloze.answer, 'Gewinn');
    expect(cloze.bank, const ['Gewinn', 'Skonto', 'Rabatt']);
    final num = tasks['i-aisol'] as TaskChoice;
    expect(num.options.length, 4);
    expect(num.options[num.correct], '1.081,60 €');
    expect(num.item.aiSolution, isTrue);
    expect(num.item.solutionSteps!.length, 3);
    expect(num.item.trick, 'Faktor zweimal');
    final c2 = tasks['i-choice2'] as TaskChoice;
    expect(c2.options, const ['Verkäufer', 'Käufer']);
    expect(c2.correct, 1);
    final mc = tasks['i-mc'] as TaskChoice;
    expect(mc.correct, 0);
    final tf = tasks['i-tf'] as TaskChoice;
    expect(tf.correct, 0);
    final match = tasks['i-match'] as TaskMatch;
    expect(match.pairs.length, 3);
    expect(match.pairs[0], const ('Selbstkosten', 'Herstellkosten + Verwaltung'));
    final card = s.quiz.firstWhere((it) => it.id == 'i-card');
    expect(card.explanation, 'Steigen mit Betrag, Laufzeit.');
    expect(card.sourceType, 'material');
    expect(card.source?.materialId, 'm-pdf');
    expect(card.source?.page, 2);
    expect(card.estSeconds, 8);
    expect(card.ttsText!.trim().isNotEmpty, isTrue);
  });

  test('crashSessionLessonFinalRoundAndVariants', () {
    final s = LearnSession.fromJson(map('session-crash.json'));
    expect(s.quiz.length, 7);
    expect(s.finalRoundStart, 4);
    expect(s.showCheatsheetSuggestion, isTrue);
    expect(
      s.forecast!.expectedGainPercentThisSession!,
      closeTo(35.0, 0.0001),
    );
    final crashTasks = {for (final it in s.quiz) it.id: parseTask(it)};
    final order = crashTasks['i-order'] as TaskOrder;
    expect(order.steps, const [
      'Selbstkosten',
      'Gewinnzuschlag',
      'Barverkaufspreis',
      'Listenpreis',
    ]);
    final spot = crashTasks['i-spot'] as TaskSpot;
    expect(spot.statements[spot.falseIndex], 'Verträge: immer Schriftform.');
    expect(spot.statements.length, 3);
    final lesson = s.quiz.firstWhere((it) => it.lesson);
    final task = parseTask(lesson) as TaskLesson;
    expect(task.steps.length, 3);
    expect(task.trick, 'Faktor 1,0x merken');
    expect(lesson.fromExam, isTrue);
    expect(lesson.patternId, 'p-1');
    final variant = s.quiz.firstWhere((it) => it.id == 'i-variant');
    expect(variant.variantOf, 'i-aisol');
    expect(variant.aiSolution, isFalse);
  });

  test('dailySessionAndOver', () {
    final d = LearnSession.fromJson(map('session-daily.json'));
    expect(d.kind, 'daily');
    expect(d.quiz.length, 12);
    expect(d.finalRoundStart, isNull);
    expect(d.showCheatsheetSuggestion, isFalse);
    final over = LearnSession.fromJson(map('session-over.json'));
    expect(over.kind, 'over');
    expect(over.quiz, isEmpty);
    expect(over.reason, 'Die Prüfung ist vorbei.');
    expect(over.forecast!.scoreNowPercent, 75);
  });

  test('errorMessagesAreReadable', () {
    String? msg(String file, int code) =>
        backendMessage(ApiHttpException(code, raw(file)));
    expect(
      msg('error-413-file-too-large.json', 413),
      'Die Datei ist größer als 30 MB',
    );
    expect(
      msg('error-415-format.json', 415),
      'Dieses Format kann ich noch nicht lesen: .xyz',
    );
    expect(msg('error-415-legacy-format.json', 415)!, contains('.docx'));
    expect(msg('error-409-attempt-over.json', 409), 'Die Prüfung ist vorbei');
    expect(
      msg('error-409-exam-limit.json', 409),
      'Höchstens 20 Prüfungen je Nutzer',
    );
    expect(msg('error-400-validation.json', 400)!, contains(':'));
    expect(msg('error-400-time.json', 400), isNotNull);
    expect(msg('error-400-attempt-outcome.json', 400), isNotNull);
    expect(msg('error-400-broken-pdf.json', 400), isNotNull);
    expect(
      msg('error-503-audio-limit.json', 503),
      'Gerade nicht verfügbar.',
    );
    expect(
      msg('error-503-audio-not-configured.json', 503),
      'Gerade nicht verfügbar.',
    );
    expect(
      msg('error-503-timings-not-configured.json', 503),
      'Gerade nicht verfügbar.',
    );
    expect(msg('error-503-prefetch-not-configured.json', 503), isNotNull);
    expect(
      msg('error-409-regenerate-no-topics.json', 409),
      'Noch keine Themen',
    );
    expect(
      msg('error-400-audio-part.json', 400),
      'part: question oder answer',
    );
    expect(
      msg('error-400-timings-part.json', 400),
      'part: question oder answer',
    );
    expect(
      msg('error-404-audio-item.json', 404),
      'Aufgabe nicht gefunden',
    );
    expect(
      msg('error-404-timings-item.json', 404),
      'Aufgabe nicht gefunden',
    );
    expect(msg('error-404-exam.json', 404), isNotNull);
    expect(msg('error-404-material.json', 404), isNotNull);
    expect(
      msg('error-426-upgrade.json', 426),
      'Bitte aktualisiere die App.',
    );
  });

  test('patternsFileIsValidJson', () {
    final arr = list('patterns.json');
    final first = arr[0] as Map<String, dynamic>;
    expect(first['id'], 'p-1');
    expect('${first['occurrences']}', '4');
  });

  test('lessonsChaptersAndAudiobook', () {
    final chapters = [
      for (final c in list('chapters.json'))
        Chapter.fromJson(c as Map<String, dynamic>),
    ];
    expect(chapters.length, 2);
    expect(chapters[0].title, 'Rechnen');
    expect(chapters[0].order, 1);
    expect(chapters[0].lessonCount, 2);
    expect(chapters[0].minutes, 2);
    expect(chapters[0].status, 'learning');
    expect(chapters[0].mastery, closeTo(0.22, 0.0001));
    expect(chapters[1].status, 'new');
    expect(chapters[1].order, 2);

    final lessons = [
      for (final l in list('lessons.json'))
        Lesson.fromJson(l as Map<String, dynamic>),
    ];
    expect(lessons.length, 3);
    final first = lessons[0];
    expect(first.title, 'Zinsen verstehen');
    expect(first.keyPoints.length, 4);
    expect(first.keyPoints[0], 'Zins = Preis für Geld');
    expect(
      first.ttsText.startsWith('Zinsen sind der Preis für geliehenes Geld.'),
      isTrue,
    );
    expect(first.mnemonic, 'Zins = Miete für Geld');
    expect(first.estSeconds, 48);
    expect(first.heard, isTrue);
    expect(first.sourceType, 'material');
    expect(first.source?.materialId, 'm-pdf');
    expect(lessons[1].heard, isFalse);
    expect(lessons[1].heardAt, isNull);
    expect(list('lessons-chapter.json').length, 2);

    final book = Audiobook.fromJson(map('audiobook.json'));
    expect(book.items.length, 2);
    expect(book.totalSeconds, 83);
    expect(book.items[0].title, 'Preis kalkulieren');
    expect(book.items[0].chapterTitle, 'Rechnen');
    expect(book.items[0].estSeconds, 42);
    expect(book.reason, 'Zuhören: 2 Lektionen, 1 Min.');
    final q = AudiobookQueue(book.items);
    expect(q.current?.lessonId, book.items[0].lessonId);
    expect(q.ahead().length, 2);
    expect(q.next(), isTrue);
    expect(q.ahead().length, 1);
    expect(q.next(), isTrue);
    expect(q.finished, isTrue);
    expect(q.next(), isFalse);
    expect(q.previous(), isTrue);
    expect(q.remainingSeconds(), 41);
    expect(
      Audiobook.fromJson(map('audiobook-repeat.json')).items,
      isNotEmpty,
    );
    final t = AudioTimings.fromJson(map('lesson-timings.json'));
    expect(t.words, isNotEmpty);
    expect(timingsMatch(t.text, t), isTrue);
  });

  test('sessionWithLessonElementsKeepsLessonAndQuestionApart', () {
    final s = LearnSession.fromJson(map('session-lesson.json'));
    expect(s.items.length, 14);
    expect(s.items[0].lesson, isNotNull);
    expect(s.items[0].item, isNull);
    expect(s.items[0].lesson!.title, 'Zinsen verstehen');
    expect(s.items[0].lesson!.keyPoints.length, 4);
    expect(s.items[0].lesson!.estSeconds, 48);
    expect(s.items[0].lesson!.mnemonic, 'Zins = Miete für Geld');
    expect(s.items.where((e) => e.lesson != null).length, 3);
    expect(s.quiz.length, 11);
    final tasks = s.items.map(taskOf).whereType<Task>().toList();
    expect(tasks.length, 14);
    expect(tasks[0], isA<TaskTeach>());
    expect(tasks[1], isA<TaskCard>());
    expect(tasks[5], isA<TaskTeach>());
    expect(tasks[0].item.id, 'lesson-${s.items[0].lesson!.id}');
    expect(tasks[0].item.estSeconds, 48);
    expect(tasks.whereType<TaskTeach>().length, 3);
  });

  test('dontKnowResponseCarriesNextLesson', () {
    final r = AttemptsResponse.fromJson(map('attempts-dontknow.json'));
    expect(r.nextLessonId!.trim().isNotEmpty, isTrue);
    expect(r.forecast.scoreNowPercent, 55);
    expect(r.forecast.streakDays, 2);
    expect(r.forecast.bestStreakDays, 3);
    expect(
      r.forecast.plan?.todayChapters,
      const ['Rechnen', 'Vertragsrecht'],
    );
    expect(r.forecast.plan?.chaptersTotal, 2);
    expect(r.forecast.plan?.chaptersHeard, 0);
    expect(planLine(r.forecast.plan), 'Heute: Rechnen und Vertragsrecht');
    expect(AttemptsResponse.fromJson(map('attempts.json')).nextLessonId, isNull);
  });

  test('priorKnowledgeOnExams', () {
    final all = exams();
    expect(all.every((e) => e.priorKnowledge == 'some'), isTrue);
    expect(priorLabel('none'), 'Nichts');
    expect(priorLabel('some'), 'Etwas');
    expect(priorLabel('much'), 'Viel');
    expect(priorLabel('unbekannt'), 'Etwas');
  });

  test('lessonErrorMessagesAndEmptyHeardResponse', () {
    String? msg(String file, int code) =>
        backendMessage(ApiHttpException(code, raw(file)));
    expect(msg('error-400-heard.json', 400), 'fraction: 0 bis 1');
    expect(msg('error-404-lesson.json', 404), 'Lektion nicht gefunden');
    expect(msg('error-404-lesson-audio.json', 404), isNotNull);
    expect(raw('lesson-heard.json').trim(), '');
  });

  test('forecastOnlyShownWhenReliable', () {
    final far = Forecast.fromJson(map('forecast-none.json'));
    final ready = exams().firstWhere((e) => e.id == 'e-ready');
    expect(forecastReliable(ready, far), isFalse);
    final answered = Forecast.fromJson(map('forecast-confidence-low.json'));
    expect(forecastReliable(ready, answered), isTrue);
    expect(
      forecastReliable(ready.copyWith(status: 'processing'), answered),
      isFalse,
    );
    expect(forecastReliable(null, answered), isFalse);
    expect(forecastReliable(ready, null), isFalse);
  });

  test('forecastWithoutNumbersIsParsedAndShowsNoScore', () {
    final none = Forecast.fromJson(map('forecast-none.json'));
    expect(none.confidence, 'none');
    expect(none.coveragePercent, 0);
    expect(none.projections, isEmpty);
    expect(none.milestones, isEmpty);
    expect(none.expectedGainPercentThisSession, isNull);
    expect(none.streakDays, 3);
    expect(none.weakTopics.length, 3);
    expect(none.plan?.chaptersTotal, 2);
    expect(none.scoreOrNull(), isNull);
    final ready = exams().firstWhere((e) => e.id == 'e-ready');
    expect(forecastReliable(ready, none), isFalse);
    final far = Forecast.fromJson(map('forecast-far.json'));
    expect(far.scoreOrNull(), isNotNull);
    final s = LearnSession.fromJson(
      jsonDecode(
        '{"items":[],"plannedSeconds":0,"kind":"daily","reason":"x","forecast":{"confidence":"none","coveragePercent":0}}',
      ) as Map<String, dynamic>,
    );
    expect(s.forecast!.scoreOrNull(), isNull);
    expect(s.forecast!.confidence, 'none');
  });

  test('profileContract', () {
    final done = Profile.fromJson(map('profile-get-done.json'));
    expect(
      done.about,
      'Ich bin Snowboarder, arbeite im Marketing und interessiere mich fuer Autos.',
    );
    expect(done.age, 17);
    expect(done.educationLevel, 'realschule');
    expect(done.onboardingDone, isTrue);
    expect(gateFor(done), OnboardingGate.done);
    expect(aboutValid(done.about), isTrue);
    final fresh = Profile.fromJson(map('profile-get-new.json'));
    expect(fresh.about, '');
    expect(fresh.age, isNull);
    expect(fresh.educationLevel, isNull);
    expect(fresh.onboardingDone, isFalse);
    expect(gateFor(fresh), OnboardingGate.needed);
    expect(
      Profile.fromJson(map('profile-put-done.json')).toJson(),
      done.toJson(),
    );
    final partial = Profile.fromJson(map('profile-put-partial.json'));
    expect(partial.age, 17);
    expect(partial.educationLevel, isNull);
    expect(partial.onboardingDone, isFalse);
    final v = VoiceSummary.fromJson(map('profile-voice-response.json'));
    expect(
      v.about,
      'Ich bin Snowboarder und arbeite im Marketing. Ich interessiere mich fuer Autos.',
    );
    expect(aboutValid(v.about), isTrue);
    final settings = jsonEncode(
      const ProfileUpdate(
        about: 'x',
        age: 20,
        educationLevel: 'abitur',
      ).toJson(),
    );
    expect(settings.contains('onboardingDone'), isFalse);
    expect(settings, '{"about":"x","age":20,"educationLevel":"abitur"}');
    final finish = jsonEncode(
      const ProfileUpdate(age: 20, onboardingDone: true).toJson(),
    );
    expect(finish, '{"age":20,"onboardingDone":true}');
  });

  test('profileErrorsAndVoiceOutcomes', () {
    String? msg(String file, int code) =>
        backendMessage(ApiHttpException(code, raw(file)));
    expect(msg('error-400-profile-age.json', 400), 'age: 10 bis 99');
    expect(
      msg('error-400-profile-onboarding.json', 400),
      'onboardingDone: nur von false auf true',
    );
    expect(msg('error-400-profile-about.json', 400), isNotNull);
    expect(msg('error-400-profile-education.json', 400), isNotNull);
    expect(
      msg('error-400-profile-voice-format.json', 400)!.startsWith('file:'),
      isTrue,
    );
    expect(
      msg('error-503-profile-voice.json', 503),
      'Gerade nicht verfügbar.',
    );
    final o = voiceOutcomeForStatus(422);
    expect(o, const VoiceNothingHeard());
    expect(voiceOutcomeMessage(o), 'Nichts verstanden. Nochmal aufnehmen?');
    expect(voiceOutcomeForStatus(503), const VoiceUnavailable());
    expect(
      voiceOutcomeMessage(const VoiceUnavailable()),
      'Gerade nicht verfügbar. Du kannst auch tippen.',
    );
    expect(voiceOutcomeMessage(const VoiceText('x')), isNull);
    expect(voiceFileExt, 'aac');
    expect(voiceMime, 'audio/aac');
  });

  test('questionsOnlySessionBudgetPlanAndAudioStatus', () {
    final s = LearnSession.fromJson(map('session-questions-only.json'));
    expect(s.items.length, 12);
    expect(s.quiz.length, 12);
    expect(s.items.every((e) => e.lesson == null), isTrue);
    expect(s.reason, 'Heute: 12 neue Fragen, 3 Themen.');
    expect(s.plannedSeconds, 149);
    expect(s.budget?.dailyMinutes, 30);
    expect(s.budget?.usedMinutesToday, 0);
    expect(s.budget?.remainingMinutes, 30);
    final mixed = LearnSession.fromJson(map('session-lesson.json'));
    expect(mixed.budget, isNotNull);
    final filtered = filterForMode(
      mixed.items,
      (e) => e.lesson != null,
      StartMode.questionsOnly,
    );
    expect(filtered.length, mixed.quiz.length);
    final plan = Forecast.fromJson(map('forecast-far.json')).plan!;
    expect(plan.plannedMinutes, 5);
    expect(plan.todayChapters, const ['Rechnen', 'Vertragsrecht']);
    final book = Audiobook.fromJson(map('audiobook-default.json'));
    expect(book.items.length, 2);
    expect(book.totalSeconds, 83);
    expect(book.budget, isNotNull);
    final st = AudioStatus.fromJson(
      jsonDecode('{"available":true,"latencyMs":420,"extra":1}')
          as Map<String, dynamic>,
    );
    expect(st.available, isTrue);
    expect(st.latencyMs, 420);
  });

  test('dayGoalAndBudgetLine', () {
    expect(dayGoalReached(20, 20 * 60), isTrue);
    expect(dayGoalReached(20, 20 * 60 - 44), isTrue);
    expect(dayGoalReached(20, 20 * 60 - 45), isFalse);
    expect(dayGoalReached(20, 0), isFalse);
    expect(budgetLine(30, 12 * 60 + 59), 'Heute: 12 von 30 min');
    expect(extraMinutes, 10);
  });

  test('openLearningHasNoDateAndNeverEndsOrCountsDown', () {
    final open = Exam.fromJson(map('exam-open.json'));
    expect(open.kind, 'open');
    expect(open.mode, 'open');
    expect(open.date, '');
    expect(open.startsAt, '');
    expect(open.dailyMinutes, 15);
    expect(open.status, 'ready');
    expect(isOpen(open), isTrue);
    expect(isOver(open, DateTime.parse('2030-01-01T00:00:00Z')), isFalse);
    expect(examSubtitle(open, DateTime.now()), 'Kein Prüfungsdatum');
    final created = Exam.fromJson(map('exam-open-created.json'));
    expect(created.kind, 'open');
    expect(created.status, 'processing');
    expect(created.dailyMinutes, 20);
    expect(exams().every((e) => e.kind == 'exam'), isTrue);
    final f = Forecast.fromJson(map('forecast-open.json'));
    expect(f.mode, 'open');
    expect(f.knowledgePercent, 70);
    expect(f.coveragePercent, 65);
    expect(f.projections, isEmpty);
    expect(f.milestones, isEmpty);
    expect(f.showCheatsheetSuggestion, isFalse);
    expect(f.scoreOrNull(), 70);
    expect(forecastReliable(open, f), isTrue);
    expect(ringPercent(open, f), 70);
    final none = Forecast.fromJson(
      jsonDecode('{"mode":"open","confidence":"none","coveragePercent":0}')
          as Map<String, dynamic>,
    );
    expect(none.knowledgePercent, isNull);
    expect(none.scoreOrNull(), isNull);
    expect(forecastReliable(open, none), isFalse);
    final s = LearnSession.fromJson(map('session-open.json'));
    expect(s.items, isNotEmpty);
    final empty = LearnSession.fromJson(
      jsonDecode(
        '{"items":[],"plannedSeconds":0,"kind":"open","reason":"Alles gelernt. Neuer Stoff wird erstellt."}',
      ) as Map<String, dynamic>,
    );
    expect(empty.items, isEmpty);
    expect(empty.reason, contains('Alles gelernt'));
  });

  test('openListAndNavigationLogic', () {
    final now = DateTime.parse('2026-11-09T12:00:00Z');
    final ex = exams().firstWhere((e) => e.id == 'e-ready');
    final open = Exam.fromJson(map('exam-open.json'));
    final sorted = sortExams([open, ex], now);
    expect(sorted.map((e) => e.id).toList(), const ['e-ready', 'e-open']);
    expect(pickActiveExam([open], null, now)?.id, 'e-open');
    expect(pickActiveExam([open, ex], null, now)?.id, 'e-ready');
    expect(pickActiveExam([open, ex], 'e-open', now)?.id, 'e-open');
    final examPages = newExamPages(false);
    final openPages = newExamPages(true);
    expect(examPages.first, NewExamPage.kind);
    expect(examPages.contains(NewExamPage.when), isTrue);
    expect(openPages.contains(NewExamPage.when), isFalse);
    expect(openPages.length, examPages.length - 1);
    expect(openPages.last, NewExamPage.daily);
    final body = jsonEncode(
      const ExamCreate(name: 'SQL', mode: 'open', dailyMinutes: 20).toJson(),
    );
    expect(body.contains('date'), isFalse);
    expect(body.contains('"mode":"open"'), isTrue);
  });

  test('offlinePackAttemptsBatchAndAudioStatusSamples', () {
    final pack = parseOfflinePack(raw('offline-pack.json'))!;
    expect(pack.entries.length, 8);
    expect(
      pack.entries.every((e) => e.item != null || e.lesson != null),
      isTrue,
    );
    expect(pack.entries.first.item?.id, 'i-card');
    expect(pack.entries.first.item?.type, 'card');
    expect(pack.entries.first.item?.estSeconds, 8);
    expect(pack.reserve, isEmpty);
    expect(pack.topics.length, 4);
    expect(pack.version.trim().isNotEmpty, isTrue);
    expect(
      pack.entries
          .map((e) => e.item?.type)
          .whereType<String>()
          .toSet()
          .intersection(const {'card', 'cloze', 'number', 'match', 'choice2'}),
      const {'card', 'cloze', 'number', 'match', 'choice2'},
    );
    final status = AudioStatus.fromJson(map('audio-status.json'));
    expect(status.available, isTrue);
    expect(status.latencyMs, 0);
    expect(statusAvailable(status), isTrue);
    final resp = AttemptsResponse.fromJson(map('attempts-batch.json'));
    expect(resp.forecast.scoreNowPercent, 55);
    expect(resp.forecast.scoreAtExamPercent, 95);
    expect(resp.doneSecondsToday, 0);
    final body = jsonEncode(
      const AttemptRequest(
        itemId: 'i',
        outcome: 'correct',
        ms: 800,
        at: '2026-11-09T10:00:00Z',
        clientId: 'u-1',
      ).toJson(),
    );
    expect(body, contains('"clientId":"u-1"'));
    expect(AttemptSync.maxBatch, 500);
  });

  test('topicIconIsNullableAndFallsBackToGraduation', () {
    final withIcon = Exam.fromJson(
      jsonDecode('{"id":"e","name":"X","icon":"brain"}')
          as Map<String, dynamic>,
    );
    expect(withIcon.icon, 'brain');
    expect(normalizeIcon(withIcon.icon), 'brain');
    final without = Exam.fromJson(
      jsonDecode('{"id":"e","name":"X"}') as Map<String, dynamic>,
    );
    expect(without.icon, isNull);
    expect(normalizeIcon(without.icon), 'graduation');
    expect(normalizeIcon('unbekannt'), 'graduation');
    expect(topicIconKeys.toSet().length, 59);
    expect(const ExamUpdate(name: 'X').toJson().containsKey('icon'), isFalse);
  });

  test('everyTaskTypeIsRenderedAsItsOwnTaskNotAsCard', () {
    final pack = parseOfflinePack(raw('offline-pack.json'))!;
    final types = {
      for (final it in pack.entries.map((e) => e.item).whereType<Item>())
        it.type: parseTask(it),
    };
    expect(types['card'], isA<TaskCard>());
    expect(types['cloze'], isA<TaskCloze>());
    expect(types['match'], isA<TaskMatch>());
    expect(types['choice2'], isA<TaskChoice>());
    expect(types['number'], isA<TaskChoice>());
    Task task(String type, String payload) => parseTask(
          Item.fromJson(
            jsonDecode(
              '{"id":"x","type":"$type","question":"q","answer":"a","payload":$payload}',
            ) as Map<String, dynamic>,
          ),
        );
    expect(task('order', '{"steps":["a","b","c"]}'), isA<TaskOrder>());
    expect(
      task('spot', '{"statements":["a","b","c"],"falseIndex":1}'),
      isA<TaskSpot>(),
    );
    expect(
      task('mc', '{"options":["a","b","c"],"correctIndex":2}'),
      isA<TaskChoice>(),
    );
    expect(task('truefalse', '{}'), isA<TaskChoice>());
    expect(task('order', '{"steps":["a"]}'), isA<TaskCard>());
    expect(task('neuertyp', '{}'), isA<TaskCard>());
  });

  test('arrangementItemsFollowTheRealSamples', () {
    final match =
        parseTask(Item.fromJson(map('item-match.json'))) as TaskMatch;
    expect(match.leftOrder, const ['Selbstkosten', 'Listenpreis', 'Zielpreis']);
    expect(match.rightOrder, const [
      'Preis vor Rabatt',
      'Preis vor Skonto',
      'Herstellkosten + Verwaltung',
    ]);
    expect(match.pairs, const [
      ('Selbstkosten', 'Herstellkosten + Verwaltung'),
      ('Listenpreis', 'Preis vor Rabatt'),
      ('Zielpreis', 'Preis vor Skonto'),
    ]);
    final ms = MatchState.of(match);
    expect(ms.lefts, match.leftOrder);
    expect(ms.rights, match.rightOrder);
    for (final l in ms.lefts) {
      ms.tapLeft(l);
      ms.tapRight(match.pairs.firstWhere((p) => p.$1 == l).$2);
    }
    expect(ms.done, isTrue);
    expect(
      jsonEncode(matchAnswer(match, ms)),
      '{"pairs":{"l1":"r1","l2":"r2","l3":"r3"}}',
    );
    final order =
        parseTask(Item.fromJson(map('item-order.json'))) as TaskOrder;
    expect(order.steps, const [
      'Selbstkosten',
      'Gewinnzuschlag',
      'Barverkaufspreis',
      'Listenpreis',
    ]);
    expect(order.shown, const [
      'Barverkaufspreis',
      'Listenpreis',
      'Gewinnzuschlag',
      'Selbstkosten',
    ]);
    expect(order.ids, const ['s1', 's2', 's3', 's4']);
    final os = OrderState.of(order);
    expect(os.shuffled, order.shown);
    for (final step in order.steps) {
      os.tap(step);
    }
    expect(os.done, isTrue);
    expect(
      jsonEncode(orderAnswer(order, os)),
      '{"order":["s1","s2","s3","s4"]}',
    );
    final bucket =
        parseTask(Item.fromJson(map('item-bucket.json'))) as TaskBucket;
    expect(bucket.categories, const [('c1', 'Kosten'), ('c2', 'Ertrag')]);
    expect(
      bucket.entries.map((e) => e.id).toList(),
      const ['b4', 'b3', 'b1', 'b5', 'b2'],
    );
    expect(bucket.entries.firstWhere((e) => e.id == 'b4').categoryId, 'c2');
    final bs = BucketState.of(bucket);
    while (!bs.done) {
      bs.tap(bs.current!.categoryId);
    }
    expect(
      jsonEncode(bucketAnswer(bs)),
      '{"assignments":{"b4":"c2","b3":"c2","b1":"c1","b5":"c1","b2":"c1"}}',
    );
    final build =
        parseTask(Item.fromJson(map('item-build.json'))) as TaskBuild;
    expect(
      [...build.tokens.map((t) => t.$2)]..sort(),
      [...const ['p', 'K', '/', 'Z', '100', '=', '×']]..sort(),
    );
    expect(build.tokens.length, 7);
    expect(build.solution, const ['t1', 't2', 't3', 't4', 't5', 't6', 't7']);
    expect(build.solutions, const [
      ['t1', 't2', 't3', 't4', 't5', 't6', 't7'],
    ]);
    expect(build.tokens.first.$1, 't5');
    final resp = AttemptsResponse.fromJson(map('attempts-bucket-build.json'));
    expect(resp.forecast.scoreNowPercent, 50);
    expect(resp.doneSecondsToday, 0);
  });

  test('mixedSessionParsesInOrderAndRendersEveryType', () {
    final s = LearnSession.fromJson(map('session-mixed.json'));
    final tasks = s.items.map(taskOf).whereType<Task>().toList();
    expect(tasks.length, s.items.length);
    expect(tasks.first, isA<TaskTeach>());
    expect(tasks.whereType<TaskTeach>().length, 3);
    expect(tasks.any((t) => t is TaskOrder && t.ids != null), isTrue);
    expect(tasks.any((t) => t is TaskMatch && t.leftIds != null), isTrue);
    expect(tasks.any((t) => t is TaskBuild), isTrue);
    expect(tasks.any((t) => t is TaskBucket), isTrue);
    expect(tasks.any((t) => t is TaskSpot), isTrue);
    expect(tasks.any((t) => t is TaskCloze), isTrue);
    for (var i = 0; i < tasks.length; i++) {
      final t = tasks[i];
      if (t is TaskTeach) {
        final next = (i + 1 < tasks.length) ? tasks[i + 1] : null;
        expect(
          teachButtonLabel(next),
          (next != null && next is! TaskTeach) ? 'Weiter zum Test' : 'Weiter',
        );
      }
    }
  });
}
