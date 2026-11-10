import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/data/models.dart';
import 'package:quickscope/logic/attempt_queue.dart';
import 'package:quickscope/logic/exams.dart';
import 'package:quickscope/logic/feedback.dart';
import 'package:quickscope/logic/forecast_text.dart';
import 'package:quickscope/logic/guard.dart';
import 'package:quickscope/logic/lesson_header.dart';
import 'package:quickscope/logic/offline.dart';
import 'package:quickscope/logic/profile.dart';
import 'package:quickscope/logic/security.dart';
import 'package:quickscope/logic/tasks.dart';
import 'package:quickscope/logic/understand.dart';

class _MemoryStorage implements QueueStorage {
  final map = <String, String>{};
  @override
  String? read(String key) => map[key];
  @override
  void write(String key, String? value) {
    if (value == null) {
      map.remove(key);
    } else {
      map[key] = value;
    }
  }
}

void main() {
  final now = DateTime.parse('2026-11-09T12:00:00Z');

  Exam exam(String id, String startsAt, [String status = 'ready']) =>
      Exam(id: id, name: id, startsAt: startsAt, status: status);

  Item item(String id, String type, [String answer = 'x']) =>
      Item(id: id, type: type, question: 'Frage?', answer: answer);

  test('activeExamPrefersChosenThenNextThenLastOver', () {
    final soon = exam('soon', '2026-11-12T08:00:00Z');
    final later = exam('later', '2026-12-01T08:00:00Z');
    final over1 = exam('over1', '2026-11-01T08:00:00Z', 'over');
    final over2 = exam('over2', '2026-11-05T08:00:00Z', 'over');
    expect(pickActiveExam([soon, later, over1], 'later', now)?.id, 'later');
    expect(pickActiveExam([later, soon, over1], null, now)?.id, 'soon');
    expect(pickActiveExam([later, soon], 'gibtsnicht', now)?.id, 'soon');
    expect(pickActiveExam([over1, over2], null, now)?.id, 'over2');
    expect(pickActiveExam(const [], null, now), isNull);
  });

  test('examListSortsUpcomingBeforePast', () {
    final list = [
      exam('o', '2026-11-01T08:00:00Z', 'over'),
      exam('b', '2026-12-01T08:00:00Z'),
      exam('a', '2026-11-12T08:00:00Z'),
    ];
    expect(sortExams(list, now).map((e) => e.id).toList(), const ['a', 'b', 'o']);
  });

  test('whenTextAndPastCheck', () {
    expect(formatExamWhen('2026-11-13', '09:00'), 'Fr., 13. Nov., 09:00');
    expect(formatExamWhen('kaputt', '09:00'), 'kaputt 09:00');
    expect(isInPast(DateTime(2026, 11, 9), (8, 0), now), isTrue);
    expect(isInPast(DateTime(2026, 11, 9), (18, 0), now), isFalse);
    expect(isInPast(DateTime(2026, 11, 10), (8, 0), now), isFalse);
  });

  test('percentsAreAlwaysFlooredToFive', () {
    expect(floor5(0), 0);
    expect(floor5(4), 0);
    expect(floor5(5), 5);
    expect(floor5(78), 75);
    expect(floor5(79), 75);
    expect(floor5(80), 80);
    expect(floor5(99), 95);
    expect(floor5(100), 100);
    expect(floor5(140), 100);
    expect(floor5(-3), 0);
    expect(percentText(79), '75 %');
    expect(masteryPercent(0.39), 35);
  });

  test('forecastTextsAreShortAndHonest', () {
    const f = Forecast(
      scoreNowPercent: 40,
      scoreAtExamPercent: 78,
      confidence: 'low',
      projections: [
        Projection(minutes: 10, scoreAtExamPercent: 62),
        Projection(minutes: 20, scoreAtExamPercent: 74),
      ],
    );
    expect(
      projectionLine(f, 20),
      'Bei 20 min täglich: ca. 75 % in der Prüfung, noch grob',
    );
    expect(
      projectionLine(
        const Forecast(
          scoreNowPercent: 40,
          scoreAtExamPercent: 78,
          confidence: 'medium',
          projections: [
            Projection(minutes: 10, scoreAtExamPercent: 62),
            Projection(minutes: 20, scoreAtExamPercent: 74),
          ],
        ),
        30,
      ),
      'Bei 30 min täglich: ca. 75 % in der Prüfung',
    );
    expect(minutesChoiceLabel(f, 10), '10 min · ca. 60 %');
    expect(minutesChoiceLabel(f, 20), '20 min · ca. 70 %');
    expect(minutesChoiceLabel(f, 45), '45 min');
    expect(minutesChoiceLabel(null, 45), '45 min');
    expect(beforeAfterLine(42, 52), 'Prognose 40 % → 50 %');
    expect(beforeAfterLine(51, 52), 'Prognose 50 %');
    expect(remainingMinutes(30, 600), 20);
    expect(remainingMinutes(10, 3000), 3);
    expect(startLabel, 'Starten');
  });

  test('queueKeepsAttemptsUntilSent', () {
    final storage = _MemoryStorage();
    final q = AttemptQueue(storage);
    q.add(
      'e1',
      const AttemptRequest(
        itemId: 'a',
        outcome: 'right',
        ms: 1200,
        at: '2026-11-09T12:00:00Z',
      ),
    );
    q.add('e1', const AttemptRequest(itemId: 'b', outcome: 'unknown', ms: 800));
    q.add('e2', const AttemptRequest(itemId: 'c', outcome: 'known', ms: 500));
    final again = AttemptQueue(storage);
    expect(again.pending('e1').map((e) => e.itemId).toList(), const ['a', 'b']);
    expect(again.pending('e1')[0].outcome, 'right');
    expect(again.pending('e1')[0].ms, 1200);
    again.add('e1', const AttemptRequest(itemId: 'd', outcome: 'wrong', ms: 900));
    again.removeSent('e1', 2);
    expect(again.pending('e1').map((e) => e.itemId).toList(), const ['d']);
    again.removeSent('e1', 1);
    expect(again.pending('e1'), isEmpty);
    expect(storage.map.containsKey('attempts_e1'), isFalse);
    expect(again.pending('e2').length, 1);
  });

  test('brokenPayloadFallsBackToCard', () {
    expect(parseTask(item('1', 'match')), isA<TaskCard>());
    expect(parseTask(item('2', 'mc')), isA<TaskCard>());
    expect(parseTask(item('3', 'unbekannt')), isA<TaskCard>());
  });

  test('outcomesMatchTaskKind', () {
    final card = parseTask(item('1', 'card'));
    expect(outcomeFor(card, true), 'known');
    expect(outcomeFor(card, false), 'unknown');
    final tf = parseTask(item('2', 'truefalse', 'falsch')) as TaskChoice;
    expect(tf.correct, 1);
    expect(outcomeFor(tf, true), 'right');
    expect(outcomeFor(tf, false), 'wrong');
  });

  test('matchEvaluatesEachPairAtOnce', () {
    final s = MatchState(
      const ['a', 'b', 'c'],
      const ['3', '1', '2'],
      const {'a': '1', 'b': '2', 'c': '3'},
    );
    expect(s.tapRight('1'), MatchResult.none);
    s.tapLeft('a');
    expect(s.tapRight('2'), MatchResult.wrong);
    expect(s.mistakes, 1);
    expect(s.matched.contains('a'), isFalse);
    expect(s.selectedLeft, isNull);
    s.tapLeft('a');
    expect(s.tapRight('1'), MatchResult.right);
    expect(s.matched.contains('a'), isTrue);
    expect(s.colorOf('a'), 0);
    expect(s.colorOfRight('1'), 0);
    s.tapLeft('b');
    expect(s.tapRight('2'), MatchResult.right);
    expect(s.colorOf('b'), 1);
    expect(s.done, isFalse);
    s.tapLeft('c');
    expect(s.tapRight('3'), MatchResult.right);
    expect(s.done, isTrue);
    expect(s.known, isFalse);
  });

  test('matchRevealsPairAfterTwoWrongTries', () {
    final s = MatchState(
      const ['a', 'b'],
      const ['2', '1'],
      const {'a': '1', 'b': '2'},
    );
    s.tapLeft('a');
    expect(s.tapRight('2'), MatchResult.wrong);
    s.tapLeft('a');
    expect(s.tapRight('2'), MatchResult.revealed);
    expect(s.revealed, const {'a'});
    expect(s.matched.contains('a'), isTrue);
    expect(s.mistakes, 2);
    s.tapLeft('b');
    expect(s.tapRight('2'), MatchResult.right);
    expect(s.done, isTrue);
    expect(s.known, isFalse);
  });

  test('matchWithoutMistakesIsKnown', () {
    final s = MatchState(
      const ['a', 'b'],
      const ['2', '1'],
      const {'a': '1', 'b': '2'},
    );
    s.tapLeft('a');
    s.tapRight('1');
    s.tapLeft('b');
    s.tapRight('2');
    expect(s.done, isTrue);
    expect(s.known, isTrue);
    s.tapLeft('a');
    expect(s.selectedLeft, isNull);
  });

  test('matchTapSelectedLeftAgainClearsSelection', () {
    final s = MatchState(
      const ['a', 'b'],
      const ['2', '1'],
      const {'a': '1', 'b': '2'},
    );
    s.tapLeft('a');
    s.tapLeft('a');
    expect(s.selectedLeft, isNull);
  });

  test('orderStateNeedsRightSequence', () {
    final s = OrderState(
      const ['A', 'B', 'C'],
      const ['C', 'A', 'B'],
    );
    expect(s.tap('B'), isFalse);
    expect(s.tap('A'), isTrue);
    expect(s.tap('B'), isTrue);
    expect(s.tap('C'), isTrue);
    expect(s.done, isTrue);
    expect(s.mistakes, 1);
  });

  test('explanationIsAtMostTwoSentences', () {
    expect(shortExplanation('Eins. Zwei. Drei.'), 'Eins. Zwei.');
    expect(shortExplanation('  '), isNull);
    expect(shortExplanation(null), isNull);
  });

  test('progressFollowsEstimatedSeconds', () {
    const items = [
      Item(id: 'a', estSeconds: 10),
      Item(id: 'b', estSeconds: 30),
    ];
    expect(sessionProgress(items, 0), closeTo(0.0, 0.001));
    expect(sessionProgress(items, 1), closeTo(0.25, 0.001));
    expect(sessionProgress(items, 2), closeTo(1.0, 0.001));
    expect(sessionProgress(items, 1), isNot(0.0));
  });

  test('aboutCounterValidationAndSaveLogic', () {
    expect(aboutCounter(''), '0/600');
    expect(aboutCounter('a' * 12), '12/600');
    expect(aboutValid(''), isTrue);
    expect(aboutValid('a' * 600), isTrue);
    expect(aboutValid('a' * 601), isFalse);
    expect(clampAbout('a' * 900).length, 600);
    expect(normalizeAbout('  Snowboarder \n'), 'Snowboarder');
    expect(aboutNeedsSave('abc', ' abc '), isFalse);
    expect(aboutNeedsSave('abc', ''), isTrue);
  });

  test('ageAndProfileDraftValidation', () {
    const l = ageMin;
    expect(ageDefault, 18);
    expect(ageValid(null), isTrue);
    expect(ageValid(14), isTrue);
    expect(ageValid(99), isTrue);
    expect(ageValid(13), isFalse);
    expect(ageValid(100), isFalse);
    expect(clampAge(l - 5), 14);
    expect(l, 14);
    expect(draftOf(const Profile(age: 10)).age, 14);
    expect(clampAge(120), 99);
    const saved = ProfileDraft(age: 18, education: 'abitur', about: 'x');
    expect(draftNeedsSave(saved, saved.copyWith(about: ' x ')), isFalse);
    expect(draftNeedsSave(saved, saved.copyWith(age: 19)), isTrue);
    expect(draftNeedsSave(saved, saved.copyWith(education: 'studium')), isTrue);
    expect(draftNeedsSave(saved, saved.copyWith(age: 120)), isFalse);
    expect(draftNeedsSave(saved, saved.copyWith(about: 'a' * 601)), isFalse);
    expect(educationLabel('studium'), 'Studium');
    expect(educationLabel('other'), 'Anderes');
    expect(educationLabel(null), '–');
    expect(education.length, 6);
  });

  test('onboardingRunsOnlyWhenNotDoneAndNeverAgain', () {
    expect(
      gateFor(const Profile(onboardingDone: false)),
      OnboardingGate.needed,
    );
    expect(
      gateFor(const Profile(onboardingDone: true)),
      OnboardingGate.done,
    );
    expect(gateFor(null), OnboardingGate.failed);
  });

  test('onboardingPagesFollowAFixedOrder', () {
    const p = OnboardingPage.values;
    expect(
      p.map((e) => e.name).toList(),
      const ['welcome', 'age', 'education', 'voice'],
    );
    expect(nextPage(p[0]), OnboardingPage.age);
    expect(nextPage(p.last), isNull);
    expect(previousPage(p.first), isNull);
    expect(greeting('Lena Beispiel'), 'Hi Lena');
    expect(greeting(null), 'Hi');
    expect(greeting('  '), 'Hi');
    expect(recordTime(7), '0:07');
    expect(recordTime(recordMaxSeconds), '1:00');
  });

  test('secretPackingRoundTripsAndRejectsGarbage', () {
    final iv = Uint8List.fromList(List.generate(12, (i) => i));
    final ct = Uint8List.fromList(List.generate(40, (i) => (i * 3) & 0xFF));
    final packed = packSecret(iv, ct);
    final (iv2, ct2) = unpackSecret(packed)!;
    expect(iv2, iv);
    expect(ct2, ct);
    expect(unpackSecret(Uint8List(0)), isNull);
    expect(unpackSecret(Uint8List.fromList([0, 1, 2])), isNull);
    expect(unpackSecret(Uint8List.fromList([12, 1, 2])), isNull);
    expect(needsSecretMigration('bst_x'), isTrue);
    expect(needsSecretMigration(null), isFalse);
  });

  test('todayShowsOnlyCountdownRingAndStartWithTheRestBehindMore', () {
    expect(
      moreEntries(hasLessons: true, hasPlan: true, streak: 3),
      const [
        MoreEntry.questionsOnly,
        MoreEntry.listenOnly,
        MoreEntry.plan,
        MoreEntry.used,
        MoreEntry.streak,
        MoreEntry.material,
        MoreEntry.settings,
      ],
    );
    expect(
      moreEntries(hasLessons: false, hasPlan: false, streak: null),
      const [MoreEntry.used, MoreEntry.material, MoreEntry.settings],
    );
    expect(
      moreEntries(hasLessons: false, hasPlan: false, streak: 0),
      const [MoreEntry.used, MoreEntry.material, MoreEntry.settings],
    );
  });

  test('doubleStartAndDoubleTapAreDiscarded', () {
    expect(canStartSession(SessionPhase.closed), isTrue);
    expect(canStartSession(SessionPhase.empty), isTrue);
    expect(canStartSession(SessionPhase.error), isTrue);
    expect(canStartSession(SessionPhase.loading), isFalse);
    expect(canStartSession(SessionPhase.running), isFalse);
    expect(canStartSession(SessionPhase.finished), isFalse);
    expect(canStartBook('Closed'), isTrue);
    expect(canStartBook('Loading'), isFalse);
    expect(canStartBook('Playing'), isFalse);
    expect(acceptClick(0, 100), isTrue);
    expect(acceptClick(1000, 1300), isFalse);
    expect(acceptClick(1000, 1500), isTrue);
  });

  String day(
    String start,
    String nowStr, {
    bool startOfSentence = false,
    int? utcOffsetHours,
  }) =>
      relativeDayLabel(
        DateTime.parse(start),
        DateTime.parse(nowStr),
        utcOffsetHours: utcOffsetHours,
        startOfSentence: startOfSentence,
      );

  test('relativeDayUsesCalendarDaysNotHours', () {
    expect(day('2026-11-11T07:00:00Z', '2026-11-09T20:00:00Z'), 'übermorgen');
    expect(day('2026-11-10T07:00:00Z', '2026-11-09T20:00:00Z'), 'morgen');
    expect(
      day('2026-11-09T20:00:00Z', '2026-11-09T15:00:00Z'),
      'heute, in 5 Std',
    );
    expect(day('2026-11-09T12:20:00Z', '2026-11-09T12:00:00Z'), 'in 20 Min');
    expect(day('2026-11-14T08:00:00Z', '2026-11-09T12:00:00Z'), 'in 5 Tagen');
    expect(day('2026-11-08T08:00:00Z', '2026-11-09T12:00:00Z'), 'vorbei');
    expect(
      relativeDayLabel(null, DateTime.parse('2026-11-09T12:00:00Z')),
      'vorbei',
    );
  });

  test('relativeDayAroundMidnight', () {
    expect(day('2026-11-09T23:30:00Z', '2026-11-09T22:59:00Z'), 'morgen');
    expect(
      day('2026-11-10T07:00:00Z', '2026-11-09T23:59:00Z'),
      'heute, in 7 Std',
    );
  });

  test('relativeDayDependsOnTimeZone', () {
    const start = '2026-11-10T04:00:00Z';
    const nowStr = '2026-11-09T14:00:00Z';
    expect(day(start, nowStr), 'morgen');
    expect(day(start, nowStr, utcOffsetHours: -5), 'heute, in 14 Std');
  });

  test('relativeDayCapitalisation', () {
    expect(
      day('2026-11-10T07:00:00Z', '2026-11-09T20:00:00Z', startOfSentence: true),
      'Morgen',
    );
    expect(
      day('2026-11-11T07:00:00Z', '2026-11-09T20:00:00Z', startOfSentence: true),
      'Übermorgen',
    );
    expect(
      day('2026-11-14T08:00:00Z', '2026-11-09T12:00:00Z', startOfSentence: true),
      'In 5 Tagen',
    );
    expect(
      day('2026-11-08T08:00:00Z', '2026-11-09T12:00:00Z', startOfSentence: true),
      'Vorbei',
    );
    expect(
      'Prüfung ${day('2026-11-10T07:00:00Z', '2026-11-09T20:00:00Z')}',
      'Prüfung morgen',
    );
  });

  Forecast f({
    int scoreNowPercent = 0,
    int scoreAtExamPercent = 0,
    String confidence = 'low',
    int coveragePercent = 0,
    String? mode,
    int? knowledgePercent,
  }) =>
      Forecast(
        scoreNowPercent: scoreNowPercent,
        scoreAtExamPercent: scoreAtExamPercent,
        confidence: confidence,
        coveragePercent: coveragePercent,
        mode: mode,
        knowledgePercent: knowledgePercent,
      );

  Exam ex([String kind = 'exam']) =>
      Exam(id: 'e', status: 'ready', kind: kind);

  test('ringShowsZeroPercentNeverADash', () {
    final never = f(
      scoreNowPercent: 0,
      scoreAtExamPercent: 0,
      confidence: 'none',
      coveragePercent: 0,
    );
    expect(ringLabel(ex(), never), '0 %');
    expect(ringLabel(ex(), f(confidence: 'low', coveragePercent: 0)), '0 %');
    expect(ringLabel(ex(), null), '0 %');
    expect(ringLabel(null, null), '0 %');
    expect(
      ringLabel(ex('open'), f(mode: 'open', knowledgePercent: null)),
      '0 %',
    );
    expect(
      ringLabel(
        ex(),
        f(scoreNowPercent: 0, confidence: 'low', coveragePercent: 10),
      ),
      '0 %',
    );
    expect(
      ringLabel(
        ex(),
        f(scoreNowPercent: 63, confidence: 'low', coveragePercent: 40),
      ),
      '60 %',
    );
    expect(
      ringLabel(ex('open'), f(mode: 'open', knowledgePercent: 47)),
      '45 %',
    );
    for (final fc in [never, f(coveragePercent: 0), f(mode: 'open')]) {
      expect(ringLabel(ex(), fc).contains('–'), isFalse);
    }
  });

  test('scoreForComparisonIsNullWithoutAnswers', () {
    expect(
      f(scoreNowPercent: 0, confidence: 'none', coveragePercent: 0)
          .scoreOrNull(),
      isNull,
    );
    expect(
      f(scoreNowPercent: 0, confidence: 'low', coveragePercent: 0)
          .scoreOrNull(),
      isNull,
    );
    expect(
      f(scoreNowPercent: 0, confidence: 'low', coveragePercent: 5)
          .scoreOrNull(),
      0,
    );
    expect(f(mode: 'open', knowledgePercent: null).scoreOrNull(), isNull);
    expect(f(mode: 'open', knowledgePercent: 35).scoreOrNull(), 35);
  });

  test('startButtonHasNoMinutes', () {
    expect(
      !startLabel.contains('min') && !RegExp(r'\d').hasMatch(startLabel),
      isTrue,
    );
  });

  SessionLesson les(
    String id, [
    String? chapterId,
    String? title,
    int? order,
    int? count,
    int? idx,
    int? n,
  ]) =>
      SessionLesson(
        id: id,
        title: 'Lektion $id',
        keyPoints: const ['a'],
        ttsText: 't',
        estSeconds: 30,
        chapterId: chapterId,
        chapterTitle: title,
        chapterOrder: order,
        chapterCount: count,
        lessonIndexInChapter: idx,
        lessonCountInChapter: n,
      );

  test('lessonHeaderWithBackendFields', () {
    final a = les('1', 'c1', 'Rechnen', 2, 6, 3, 5);
    final h = lessonHeader(a, [a], lessonsOnly: true);
    expect(h.chapterLine, 'Kapitel 2 von 6 · Rechnen');
    expect(h.title, 'Lektion 1');
    expect(h.progress, const (3, 5));
    expect(h.progressText, 'Lektion 3 von 5');
  });

  test('lessonHeaderDetectsChapterChange', () {
    final run = [
      les('1', 'c1', 'Rechnen', 1, 2),
      les('2', 'c1', 'Rechnen', 1, 2),
      les('3', 'c2', 'Recht', 2, 2),
    ];
    expect(lessonHeader(run[1], run, lessonsOnly: true).newChapter, isFalse);
    expect(lessonHeader(run[2], run, lessonsOnly: true).newChapter, isTrue);
    expect(lessonHeader(run[0], run, lessonsOnly: true).newChapter, isFalse);
    expect(
      lessonHeader(run[1], run, lessonsOnly: true).progress,
      const (2, 2),
    );
    expect(
      lessonHeader(run[2], run, lessonsOnly: true).progress,
      const (1, 1),
    );
  });

  test('lessonHeaderToleratesMissingFields', () {
    final bare = les('1');
    final h = lessonHeader(bare, [bare, les('2')], lessonsOnly: true);
    expect(h.chapterLine, isNull);
    expect(h.progress, const (1, 2));
    expect(lessonHeader(bare, [bare], lessonsOnly: false).progress, isNull);
    expect(
      lessonHeader(les('1', 'c1', 'Rechnen'), const [], lessonsOnly: false)
          .chapterLine,
      'Rechnen',
    );
  });

  test('enrichFillsOnlyMissingChapterFields', () {
    const chapters = [
      Chapter(chapterId: 'c1', title: 'Rechnen', order: 1),
      Chapter(chapterId: 'c2', title: 'Recht', order: 2),
    ];
    final e = enrichLesson(les('7'), const {'7': 'c2'}, chapters);
    expect(e.chapterId, 'c2');
    expect(e.chapterTitle, 'Recht');
    expect(e.chapterOrder, 2);
    expect(e.chapterCount, 2);
    final keep = enrichLesson(
      les('7', 'c1', 'Eigener Titel', 9, 9),
      const {'7': 'c2'},
      chapters,
    );
    expect(keep.chapterTitle, 'Eigener Titel');
    expect(keep.chapterOrder, 9);
    final none = enrichLesson(les('8'), const {}, chapters);
    expect(none.chapterTitle, isNull);
    expect(none.chapterCount, isNull);
  });

  Chapter ch(
    String id,
    int order, [
    String status = 'new',
    String? title,
  ]) =>
      Chapter(
        chapterId: id,
        title: title ?? 'K$order',
        order: order,
        status: status,
      );

  SessionLesson sl(String id, String? chapterId) => SessionLesson(
        id: id,
        title: 'L$id',
        keyPoints: const ['a'],
        ttsText: 't',
        estSeconds: 30,
        chapterId: chapterId,
      );

  test('understandListIsOrderedByChapterAndStartsAtFirstUnheard', () {
    final chapters = [ch('c2', 2), ch('c1', 1)];
    final lessons = [
      sl('3', 'c2'),
      sl('1', 'c1'),
      sl('4', 'c2'),
      sl('2', 'c1'),
    ];
    final u = buildUnderstandList(chapters, lessons, const {'1'});
    expect(
      u.lessons.map((e) => e.id).toList(),
      const ['1', '2', '3', '4'],
    );
    expect(u.start, 1);
    expect(
      (u.lessons[0].lessonIndexInChapter, u.lessons[0].lessonCountInChapter),
      const (1, 2),
    );
    expect(
      (u.lessons[3].lessonIndexInChapter, u.lessons[3].lessonCountInChapter),
      const (2, 2),
    );
    expect(u.lessons[2].chapterOrder, 2);
    expect(u.lessons[0].chapterCount, 2);
    expect(
      buildUnderstandList(chapters, lessons, const {'1', '2', '3', '4'}).start,
      0,
    );
    final many = [for (var i = 1; i <= 20; i++) sl('$i', 'c1')];
    expect(
      buildUnderstandList([ch('c1', 1)], many, const {}).lessons.length,
      20,
    );
  });

  test('stepsCrossChapterBoundariesAndStopAtTheEnds', () {
    expect(stepIndex(2, 5, 1), 3);
    expect(stepIndex(2, 5, -1), 1);
    expect(stepIndex(0, 5, -1), isNull);
    expect(stepIndex(4, 5, 1), isNull);
    final u = buildUnderstandList(
      [ch('c1', 1), ch('c2', 2)],
      [sl('1', 'c1'), sl('2', 'c1'), sl('3', 'c2')],
      const {},
    );
    final next = stepIndex(1, u.lessons.length, 1)!;
    expect(u.lessons[next].chapterId, 'c2');
    expect(u.lessons[stepIndex(next, u.lessons.length, -1)!].chapterId, 'c1');
  });

  test('chapterListShowsStatusAndJumpsToFirstUnheard', () {
    final chapters = [
      ch('c1', 1, 'solid'),
      ch('c2', 2, 'heard'),
      ch('c3', 3, 'new'),
    ];
    final u = buildUnderstandList(
      chapters,
      [sl('1', 'c1'), sl('2', 'c2'), sl('3', 'c2'), sl('4', 'c3')],
      const {'2'},
    );
    final e = chapterEntries(u.lessons, chapters, const {'2'});
    expect(e.map((c) => c.status).toList(), const ['sitzt', 'gehört', 'neu']);
    expect(e.map((c) => c.jumpIndex).toList(), const [0, 2, 3]);
    final noApi = chapterEntries(u.lessons, const [], const {'2', '3'});
    expect(
      noApi.firstWhere((c) => c.chapterId == 'c2').status,
      'gehört',
    );
    expect(
      noApi.firstWhere((c) => c.chapterId == 'c3').status,
      'neu',
    );
    expect(
      chapterEntries(u.lessons, chapters, const {'2', '3'})
          .firstWhere((c) => c.chapterId == 'c2')
          .jumpIndex,
      1,
    );
  });

  test('heardReportRules', () {
    double? r(
      bool played,
      int pos,
      int dur,
      bool fin,
      int shown,
      bool fwd,
    ) =>
        heardToReport(
          audioPlayed: played,
          positionMs: pos,
          durationMs: dur,
          finished: fin,
          shownMs: shown,
          forward: fwd,
        );
    expect(r(true, 30000, 30000, true, 31000, true), closeTo(1.0, 0.0001));
    expect(r(true, 15000, 30000, false, 16000, true), closeTo(0.5, 0.0001));
    expect(r(true, 500, 30000, false, 600, true), isNull);
    expect(r(false, 0, 30000, false, 5000, true), closeTo(0.8, 0.0001));
    expect(r(false, 0, 30000, false, 4999, true), isNull);
    expect(r(false, 0, 30000, false, 20000, false), isNull);
  });

  TaskMatch matchTask(
    String id,
    int n, {
    List<String>? lefts,
    List<String>? rights,
  }) =>
      TaskMatch(
        Item(id: id, type: 'match'),
        [for (var i = 1; i <= n; i++) ('L$i', 'R$i')],
        leftOrder: lefts,
        rightOrder: rights,
      );

  test('matchNeverShowsPartnersSideBySide', () {
    for (var n = 2; n <= 6; n++) {
      for (final id in [
        'a',
        'i-match',
        'xyz',
        'item-42',
        'q7',
        'zz',
        '0',
        'long-item-id-1',
      ]) {
        final state = MatchState.of(matchTask(id, n));
        expect(state.lefts.length, n);
        expect(
          [...state.lefts.map((l) => 'R${l.substring(1)}')]..sort(),
          [...state.rights]..sort(),
        );
        for (var row = 0; row < state.lefts.length; row++) {
          final left = state.lefts[row];
          expect(
            state.rights[row] != 'R${lSub(left)}',
            isTrue,
            reason: '$id n=$n row=$row',
          );
        }
      }
    }
  });

  test('matchMixIsStablePerItemAndPrefersBackendColumns', () {
    final a = MatchState.of(matchTask('stable', 4));
    final b = MatchState.of(matchTask('stable', 4));
    expect(a.lefts, b.lefts);
    expect(a.rights, b.rights);
    final other = MatchState.of(matchTask('other-seed', 4));
    expect(
      a.rights.join(',') != other.rights.join(',') ||
          a.lefts.join(',') != other.lefts.join(','),
      isTrue,
    );
    final given = MatchState.of(
      matchTask(
        'stable',
        3,
        lefts: const ['L3', 'L1', 'L2'],
        rights: const ['R1', 'R2', 'R3'],
      ),
    );
    expect(given.lefts, const ['L3', 'L1', 'L2']);
    expect(given.rights, const ['R1', 'R2', 'R3']);
  });

  test('derangementAndStrictShuffle', () {
    const it = Item(id: 'd1', type: 'order');
    for (var n = 2; n <= 8; n++) {
      final base = [for (var i = 0; i < n; i++) i];
      final d = derangedFor(it, base);
      expect([...d]..sort(), base);
      expect(
        List.generate(n, (i) => d[i] == base[i]).any((x) => x),
        isFalse,
      );
      expect(shuffledFor(it, base), isNot(equals(base)));
    }
    expect(derangedFor(it, const [1]), const [1]);
    const order = TaskOrder(it, ['a', 'b', 'c']);
    expect(OrderState.of(order).shuffled, isNot(equals(const ['a', 'b', 'c'])));
  });

  Item jsonItem(String type, String payload) => Item.fromJson(
        jsonDecode(
          '{"id":"x1","type":"$type","question":"q","answer":"a","payload":$payload}',
        ) as Map<String, dynamic>,
      );

  test('bucketParsesAndScoresPerAssignment', () {
    const payload =
        '{"categories":[{"id":"c1","label":"Aktiv"},{"id":"c2","label":"Passiv"}],"items":[{"id":"i1","text":"Kasse","categoryId":"c1"},{"id":"i2","text":"Kredit","categoryId":"c2"},{"id":"i3","text":"Bank","categoryId":"c1"},{"id":"i4","text":"Eigenkapital","categoryId":"c2"}]}';
    final task = parseTask(jsonItem('bucket', payload)) as TaskBucket;
    expect(
      task.categories.map((c) => c.$2).toList(),
      const ['Aktiv', 'Passiv'],
    );
    expect(
      BucketState.of(task).entries.map((e) => e.id).toList(),
      const ['i1', 'i2', 'i3', 'i4'],
    );
    final state = BucketState.of(task);
    while (!state.done) {
      expect(state.tap(state.current!.categoryId), BucketResult.right);
    }
    expect(state.known, isTrue);
    expect(
      jsonEncode(bucketAnswer(state)),
      '{"assignments":{"i1":"c1","i2":"c2","i3":"c1","i4":"c2"}}',
    );
    final s2 = BucketState.of(task);
    final wrong = task.categories
        .firstWhere((c) => c.$1 != s2.current!.categoryId)
        .$1;
    expect(s2.tap(wrong), BucketResult.wrong);
    expect(s2.mistakes, 1);
    expect(bucketAnswer(s2), isNull);
    final first = s2.current!;
    expect(s2.tap(wrong), BucketResult.revealed);
    expect(s2.placed[first.id], first.categoryId);
    expect(s2.known, isFalse);
    while (!s2.done) {
      s2.tap(s2.current!.categoryId);
    }
    expect(bucketAnswer(s2), isNull);
    final s3 = BucketState.of(task);
    s3.revealAll();
    expect(s3.done, isTrue);
    expect(bucketAnswer(s3), isNull);
    expect(
      parseTask(
        jsonItem(
          'bucket',
          '{"categories":[{"id":"c1","title":"A"},{"id":"c2","title":"B"}],"items":[]}',
        ),
      ),
      isA<TaskCard>(),
    );
  });

  test('buildChecksAgainstAllSolutions', () {
    const payload =
        '{"tokens":[{"id":"t2","text":"kapital"},{"id":"t1","text":"Eigen"},{"id":"t3","text":"quote"}],"solution":["t1","t2","t3"],"solutions":[["t1","t2","t3"],["t3","t1","t2"]]}';
    final task = parseTask(jsonItem('build', payload)) as TaskBuild;
    expect(task.solutions.length, 2);
    final state = BuildState.of(task);
    expect(
      state.shuffled.map((e) => e.$1).toList(),
      const ['t2', 't1', 't3'],
    );
    expect(state.check(), isNull);
    state.tapChip('t2');
    state.tapChip('t1');
    state.tapChip('t3');
    state.tapRow(0);
    expect(state.row, const ['t1', 't3']);
    state.tapChip('t2');
    expect(state.check(), BuildResult.wrong);
    expect(state.mistakes, 1);
    expect(state.check(), BuildResult.revealed);
    expect(state.row, const ['t1', 't2', 't3']);
    expect(state.known, isFalse);
    expect(buildAnswer(state), isNull);
    final s2 = BuildState.of(task);
    for (final t in ['t3', 't1', 't2']) {
      s2.tapChip(t);
    }
    expect(s2.check(), BuildResult.right);
    expect(s2.known, isTrue);
    expect(jsonEncode(buildAnswer(s2)), '{"order":["t3","t1","t2"]}');
    final body = jsonEncode(
      AttemptRequest(
        itemId: 'x1',
        outcome: 'right',
        ms: 900,
        clientId: 'u',
        answer: buildAnswer(s2),
      ).toJson(),
    );
    expect(body, contains('"answer":{"order":["t3","t1","t2"]}'));
    expect(
      parseTask(
        jsonItem('build', '{"tokens":["a","b"],"order":["0","1"]}'),
      ),
      isA<TaskCard>(),
    );
  });

  test('mixedRoundLabelsRetryPlacementProgressAndOfflineOrder', () {
    const lesson = SessionLesson(
      id: 'L1',
      title: 'T',
      keyPoints: ['a'],
      ttsText: 't',
      estSeconds: 40,
    );
    final teach = TaskTeach(teachItem(lesson), lesson);
    Task q(String id, int sec) =>
        parseTask(Item(id: id, type: 'card', estSeconds: sec));
    expect(teachButtonLabel(q('a', 10)), 'Weiter zum Test');
    expect(teachButtonLabel(teach), 'Weiter');
    expect(teachButtonLabel(null), 'Weiter');
    final tasks = [
      q('a', 10),
      q('b', 10),
      teach,
      q('c', 10),
      q('d', 10),
      q('e', 10),
    ];
    expect(afterUpcomingLessons(tasks, 0, 2), 3);
    expect(afterUpcomingLessons(tasks, 0, 5), 5);
    expect(afterUpcomingLessons([q('a', 10), q('b', 10)], 0, 1), 1);
    final items = [q('a', 10), teach, q('c', 30)].map((e) => e.item).toList();
    expect(sessionProgress(items, 1), closeTo(0.125, 0.0001));
    expect(sessionProgress(items, 2), closeTo(0.625, 0.0001));
    final entries = [
      const SessionEntry(lesson: lesson),
      const SessionEntry(item: Item(id: 'q1', type: 'card')),
      const SessionEntry(item: Item(id: 'q2', type: 'card')),
      SessionEntry(lesson: lesson.copyWith(id: 'L2')),
      const SessionEntry(item: Item(id: 'q3', type: 'card')),
    ];
    final round = offlineRound(
      OfflinePack(entries: entries, reserve: const []),
      const {},
      10000,
    );
    expect(
      round.map(entryKey).whereType<String>().toList(),
      const ['l:L1', 'q:q1', 'q:q2', 'l:L2', 'q:q3'],
    );
  });
}

String lSub(String left) => left.substring(1);
