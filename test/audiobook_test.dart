import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/data/models.dart';
import 'package:quickscope/data/network.dart';
import 'package:quickscope/logic/audiobook.dart';
import 'package:quickscope/logic/exams.dart';
import 'package:quickscope/logic/tasks.dart';

void main() {
  test('keyPointsAreHighlightedEvenlyOverTheLesson', () {
    expect(activeKeyPoint(0, 1000, 60000), -1);
    expect(activeKeyPoint(4, 1000, 0), -1);
    expect(activeKeyPoint(4, 0, 60000), 0);
    expect(activeKeyPoint(4, 14999, 60000), 0);
    expect(activeKeyPoint(4, 15000, 60000), 1);
    expect(activeKeyPoint(4, 40000, 60000), 2);
    expect(activeKeyPoint(4, 59999, 60000), 3);
    expect(activeKeyPoint(4, 90000, 60000), 3);
    expect(activeKeyPoint(4, -5, 60000), 0);
  });

  test('heardFractionAndMnemonicTiming', () {
    expect(heardFraction(10, 100, true), 1.0);
    expect(heardFraction(500, 1000, false), closeTo(0.5, 0.0001));
    expect(heardFraction(5000, 1000, false), 1.0);
    expect(heardFraction(500, 0, false), 0.0);
    expect(showMnemonic(80000, 100000), isFalse);
    expect(showMnemonic(90000, 100000), isTrue);
    expect(showMnemonic(1, 0), isFalse);
    expect(chapterLabel(2, 6), 'Kapitel 2 von 6');
    expect(chapterLabel(0, 6), '');
  });

  test('queueStaysInBoundsAndSkipsAhead', () {
    final items = [
      for (var i = 1; i <= 5; i++)
        AudiobookItem(
          lessonId: 'l$i',
          chapterId: 'c1',
          chapterTitle: 'Kapitel',
          title: 'T$i',
          estSeconds: 40,
        ),
    ];
    final q = AudiobookQueue(items);
    expect(q.previous(), isFalse);
    expect(q.ahead(), const ['l1', 'l2', 'l3']);
    q.jumpTo(3);
    expect(q.ahead(), const ['l4', 'l5']);
    expect(q.remainingSeconds(), 80);
    q.jumpTo(99);
    expect(q.finished, isTrue);
    expect(q.current, isNull);
  });

  test('planLineIsShort', () {
    expect(planLine(null), isNull);
    expect(
      planLine(const Plan(todayChapters: [], chaptersTotal: 0, chaptersHeard: 0)),
      isNull,
    );
    expect(
      planLine(
        const Plan(
          todayChapters: ['Rechnen'],
          chaptersTotal: 8,
          chaptersHeard: 3,
        ),
      ),
      'Heute: Rechnen',
    );
    expect(
      planLine(
        const Plan(
          todayChapters: ['A', 'B', 'C'],
          chaptersTotal: 8,
          chaptersHeard: 3,
        ),
      ),
      'Heute: A, B und 1 weitere',
    );
    expect(
      planLine(
        const Plan(todayChapters: [], chaptersTotal: 8, chaptersHeard: 3),
      ),
      '3 von 8 Kapiteln gehört',
    );
  });

  test('lessonIsInsertedRightAfterTheCurrentTaskOnlyOnce', () {
    const lesson = SessionLesson(
      id: 'L1',
      title: 'Titel',
      keyPoints: ['a'],
      ttsText: 'text',
      estSeconds: 40,
    );
    final teach = TaskTeach(teachItem(lesson), lesson);
    const tasks = <Task>[
      TaskCard(Item(id: 'q1'), 'x'),
      TaskCard(Item(id: 'q2'), 'y'),
    ];
    expect(lessonInsertIndex(0, tasks, 'L1'), 1);
    expect(lessonInsertIndex(0, [...tasks, teach], 'L1'), isNull);
    expect(lessonInsertIndex(1, [...tasks, teach], 'L2'), 2);
  });

  test('startModesAndLessonFiltering', () {
    expect(secondaryModes(false), isEmpty);
    expect(secondaryModes(true), const [
      StartMode.questionsOnly,
      StartMode.listenOnly,
    ]);
    const entries = ['L1', 'q1', 'q2', 'L2', 'q3'];
    bool isLesson(String s) => s.startsWith('L');
    expect(filterForMode(entries, isLesson, StartMode.all), entries);
    expect(
      filterForMode(entries, isLesson, StartMode.questionsOnly),
      const ['q1', 'q2', 'q3'],
    );
    expect(filterForMode(entries, isLesson, StartMode.listenOnly), entries);
    expect(
      filterForMode(<String>[], isLesson, StartMode.questionsOnly),
      isEmpty,
    );
  });

  test('neutralMessagesHideProviderHints', () {
    expect(neutralMessage('KI nicht eingerichtet'), 'Gerade nicht verfügbar.');
    expect(neutralMessage('Sprach-Limit erreicht'), 'Gerade nicht verfügbar.');
    expect(neutralMessage('Gemini antwortet nicht'), 'Gerade nicht verfügbar.');
    expect(
      neutralMessage('Die Datei ist größer als 30 MB'),
      'Die Datei ist größer als 30 MB',
    );
    expect(neutralMessage('Skizze fertig'), 'Skizze fertig');
  });
}
