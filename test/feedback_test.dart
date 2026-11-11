import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/data/models.dart';
import 'package:quickscope/logic/feedback.dart';
import 'package:quickscope/logic/tasks.dart';

void main() {
  test('xpGrowsWithDifficultyAndStreak', () {
    expect(xpFor(3, 1), 10);
    expect(xpFor(1, 1), 10);
    expect(xpFor(4, 1), 13);
    expect(xpFor(5, 1), 16);
    expect(xpFor(3, 5), 15);
    expect(xpFor(3, 10), 20);
    expect(xpFor(3, 15), 25);
    expect(xpFor(3, 40), 25);
  });

  test('celebrationsAtMilestones', () {
    expect(celebrationFor(1), Celebration.small);
    expect(celebrationFor(4), Celebration.small);
    expect(celebrationFor(5), Celebration.medium);
    expect(celebrationFor(15), Celebration.medium);
    expect(celebrationFor(10), Celebration.big);
    expect(celebrationFor(20), Celebration.big);
    expect(celebrationFor(0), Celebration.small);
  });

  test('trackerCountsStreakBestXpAndComeback', () {
    final t = FeedbackTracker();
    for (var i = 0; i < 4; i++) {
      expect(t.record(true, 3).correct, isTrue);
    }
    final fifth = t.record(true, 3);
    expect(fifth.streak, 5);
    expect(fifth.celebration, Celebration.medium);
    expect(fifth.xp, 15);
    expect(fifth.comeback, isFalse);
    final miss = t.record(false, 3);
    expect(miss.streak, 0);
    expect(miss.bestStreak, 5);
    expect(miss.xp, 0);
    final back = t.record(true, 3);
    expect(back.comeback, isTrue);
    expect(back.streak, 1);
    expect(t.record(true, 3).comeback, isFalse);
    expect(t.correct, 7);
    expect(t.answers, 8);
    expect(t.xp, 4 * 10 + 15 + 10 + 10);
    expect(t.best, 5);
  });

  test('retriesGiveNoXpAndDoNotCountTwice', () {
    final t = FeedbackTracker();
    t.record(false, 3);
    final r = t.record(true, 4, retry: true);
    expect(r.correct, isTrue);
    expect(r.xp, 0);
    expect(r.streak, 1);
    expect(t.answers, 1);
    expect(t.correct, 0);
    expect(t.xp, 0);
  });

  test('retryComesBackAfterThreeToFiveTasks', () {
    for (final key in ['a', 'b', 'i-aisol', 'i-spot', 'x1', 'zzz', '']) {
      final idx = retryInsertIndex(2, 30, key);
      expect(
        idx,
        inInclusiveRange(
          2 + 1 + FeedbackTuning.retryMinGap,
          2 + 1 + FeedbackTuning.retryMaxGap,
        ),
      );
    }
    expect(retryInsertIndex(0, 20, 'k'), retryInsertIndex(0, 20, 'k'));
    expect(retryInsertIndex(4, 5, 'k'), 5);
    expect(retryInsertIndex(10, 7, 'k').clamp(0, 7), 7);
  });

  test('topicSitsOnlyWhenCrossingThreshold', () {
    const prev = {'a': 0.7, 'b': 0.85, 'c': 0.2};
    const now = {'a': 0.82, 'b': 0.9, 'c': 0.5, 'd': 0.81};
    expect(newlySitting(prev, now).toSet(), const {'a', 'd'});
    expect(newlySitting(now, now), isEmpty);
    expect(newlySitting(const {'a': 0.79}, const {'a': 0.8}), const ['a']);
  });

  test('dayStreakCountsConsecutiveDays', () {
    final today = DateTime(2026, 11, 9);
    expect(nextDayStreak(null, 0, today), 1);
    expect(
      nextDayStreak(today.subtract(const Duration(days: 1)), 3, today),
      4,
    );
    expect(nextDayStreak(today, 3, today), 3);
    expect(
      nextDayStreak(today.subtract(const Duration(days: 2)), 9, today),
      1,
    );
    expect(visibleDayStreak(today, 7, today), 7);
    expect(
      visibleDayStreak(today.subtract(const Duration(days: 1)), 7, today),
      7,
    );
    expect(
      visibleDayStreak(today.subtract(const Duration(days: 2)), 7, today),
      isNull,
    );
    expect(visibleDayStreak(null, 7, today), isNull);
    expect(visibleDayStreak(today, 0, today), isNull);
  });

  test('synthesizedTonesAreShortQuietAndNonEmpty', () {
    for (final notes in Tones.all) {
      final pcm = synth(notes);
      final expected = notes.fold<int>(
        0,
        (s, n) => s + (n.ms * FeedbackTuning.sampleRate ~/ 1000),
      );
      expect(pcm.length, expected);
      final peak = pcm.fold<int>(0, (m, v) => v.abs() > m ? v.abs() : m);
      expect(peak, greaterThan(1000));
      expect(
        peak,
        lessThanOrEqualTo((FeedbackTuning.sfxVolume * 32767).toInt() + 1),
      );
      expect(pcm.length, lessThan(FeedbackTuning.sampleRate * 2));
      expect(pcm.first.abs(), lessThan(400));
      expect(pcm.last.abs(), lessThan(400));
    }
    final pling = Tones.correct;
    expect(pling.length, 2);
    final sumMs = pling.fold<int>(0, (s, n) => s + n.ms);
    expect(sumMs, inInclusiveRange(150, 250));
    expect(pling[1].hz, greaterThan(pling[0].hz));
    expect(synth(Tones.correct).length, lessThan(synth(Tones.done).length));
  });

  Outcome outcome(bool correct, int streak) => Outcome(
        correct: correct,
        retry: false,
        streak: streak,
        bestStreak: streak,
        xp: correct ? 10 : 0,
        celebration: celebrationFor(streak),
        comeback: false,
      );

  test('everyCorrectAnswerPlingsAndWrongOrDontKnowStaySilent', () {
    expect(soundFor(outcome(true, 1)), Tones.correct);
    expect(soundFor(outcome(true, 2)), Tones.correct);
    expect(soundFor(outcome(true, 3)), Tones.streak3);
    expect(soundFor(outcome(true, 5)), Tones.medium);
    expect(soundFor(outcome(true, 10)), Tones.big);
    expect(soundFor(outcome(false, 0)), isNull);
    expect(dontKnowOutcome(), 'dontknow');
  });

  Item item(String type, [String id = 'x']) =>
      Item(id: id, type: type, question: 'Q?', answer: 'A');

  test('everyTaskTypePlingsOnCorrectAndStaysSilentOnWrongOrDontKnow', () {
    for (final type in [
      'card',
      'mc',
      'cloze',
      'truefalse',
      'choice2',
      'number',
      'match',
      'order',
      'spot',
    ]) {
      final task = parseTask(item(type));
      final tracker = FeedbackTracker();
      final right = tracker.record(true, task.item.difficulty);
      expect(soundFor(right), Tones.correct, reason: 'richtig bei $type');
      final wrong = FeedbackTracker().record(false, task.item.difficulty);
      expect(soundFor(wrong), isNull, reason: 'falsch bei $type');
    }
    expect(
      soundFor(FeedbackTracker().record(true, 3, retry: true)),
      Tones.correct,
    );
    expect(soundForMatch(MatchResult.right), Tones.correct);
    expect(soundForMatch(MatchResult.wrong), isNull);
    expect(soundForMatch(MatchResult.revealed), isNull);
    expect(soundForMatch(MatchResult.none), isNull);
  });

  test('wavFileHasValidHeaderAndLittleEndianSamples', () {
    final pcm = synth(Tones.correct);
    final wav = wavBytes(pcm);
    expect(wav.length, 44 + pcm.length * 2);
    expect(ascii.decode(wav.sublist(0, 4)), 'RIFF');
    expect(ascii.decode(wav.sublist(8, 12)), 'WAVE');
    expect(ascii.decode(wav.sublist(36, 40)), 'data');
    int le32(int o) =>
        (wav[o] & 0xFF) |
        ((wav[o + 1] & 0xFF) << 8) |
        ((wav[o + 2] & 0xFF) << 16) |
        ((wav[o + 3] & 0xFF) << 24);
    expect(le32(24), FeedbackTuning.sampleRate);
    expect(le32(40), pcm.length * 2);
    final k = pcm.length ~/ 2;
    var v = (wav[44 + 2 * k] & 0xFF) | ((wav[45 + 2 * k] & 0xFF) << 8);
    if (v >= 0x8000) v -= 0x10000;
    expect(v, pcm[k]);
    final peak = pcm.fold<int>(0, (m, x) => x.abs() > m ? x.abs() : m);
    expect(peak, greaterThan(5000));
  });
}
