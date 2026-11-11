import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/data/models.dart';
import 'package:quickscope/logic/audio.dart';
import 'package:quickscope/logic/listen.dart';
import 'package:quickscope/logic/tasks.dart';

void main() {
  test('speechSpeedIsFixed', () {
    expect(speechSpeed, closeTo(1.15, 0.0001));
  });

  test('cardFlowNeedsRevealBeforeAnswer', () {
    final f = CardFlow();
    expect(f.answer(true), isNull);
    expect(f.revealed, isFalse);
    f.reveal();
    expect(f.answer(true), 'known');
    expect(f.answer(false), 'unknown');
  });

  test('speechStartsWheneverAnItemAppearsWithSoundOn', () {
    expect(shouldSpeakQuestion(true, null, false), isTrue);
    expect(shouldSpeakQuestion(false, null, false), isFalse);
    expect(shouldSpeakQuestion(true, true, false), isFalse);
    expect(shouldSpeakQuestion(true, null, true), isFalse);
  });

  test('neverTwoPlaybacksAtOnceAndOldCallbacksAreDropped', () {
    final m = PlaybackMachine();
    final first = m.begin();
    expect(m.state, PlayState.loading);
    expect(m.playing(first), isTrue);
    expect(m.state, PlayState.playing);
    final second = m.begin();
    expect(m.isCurrent(first), isFalse);
    expect(m.isCurrent(second), isTrue);
    expect(m.playing(first), isFalse);
    expect(m.finish(first), isFalse);
    expect(m.state, PlayState.loading);
    expect(m.playing(second), isTrue);
    expect(m.playing(second), isFalse);
    m.pause(second);
    expect(m.state, PlayState.paused);
    m.resume(second);
    expect(m.state, PlayState.playing);
    expect(m.finish(second), isTrue);
    expect(m.state, PlayState.idle);
    expect(m.isCurrent(second), isFalse);
  });

  test('stopInvalidatesEverythingInFlight', () {
    final m = PlaybackMachine();
    final g = m.begin();
    m.stop();
    expect(m.isCurrent(g), isFalse);
    expect(m.playing(g), isFalse);
    expect(m.state, PlayState.idle);
    final a = m.begin();
    final b = m.begin();
    final c = m.begin();
    expect(m.isCurrent(a), isFalse);
    expect(m.isCurrent(b), isFalse);
    expect(m.isCurrent(c), isTrue);
    expect(m.generation, c);
  });

  test('backgroundStopsRoundPlaybackAndAutoplayRestsUntilNextItem', () {
    expect(shouldStopForLifecycle(true, PlayState.playing), isFalse);
    expect(shouldStopForLifecycle(false, PlayState.playing), isTrue);
    expect(shouldStopForLifecycle(false, PlayState.loading), isTrue);
    expect(shouldStopForLifecycle(false, PlayState.paused), isTrue);
    expect(shouldStopForLifecycle(false, PlayState.idle), isFalse);
    expect(mayAutoplay(null, 3), isTrue);
    expect(mayAutoplay(3, 3), isFalse);
    expect(mayAutoplay(3, 4), isTrue);
    expect(playTap(PlayState.idle), PlayTap.restart);
    final m = PlaybackMachine();
    final g = m.begin();
    m.playing(g);
    m.stop();
    expect(m.isCurrent(g), isFalse);
    expect(m.state, PlayState.idle);
  });

  test('playButtonShowsOnlyPlayOrPause', () {
    expect(playButtonKind(PlayState.playing), PlayButtonKind.pause);
    expect(playButtonKind(PlayState.idle), PlayButtonKind.play);
    expect(playButtonKind(PlayState.paused), PlayButtonKind.play);
    expect(playButtonKind(PlayState.loading), PlayButtonKind.play);
    expect(PlayButtonKind.values.length, 2);
  });

  test('tapPausesResumesOrRestarts', () {
    expect(playTap(PlayState.playing), PlayTap.pause);
    expect(playTap(PlayState.paused), PlayTap.resume);
    expect(playTap(PlayState.idle), PlayTap.restart);
    expect(playTap(PlayState.loading), PlayTap.restart);
  });

  test('backoffDoublesUpToSixtySecondsAndHonoursRetryAfter', () {
    final seq = [for (var i = 0; i <= 9; i++) retryDelayMs(i)];
    expect(seq, const [
      1000,
      2000,
      4000,
      8000,
      16000,
      32000,
      60000,
      60000,
      60000,
      60000,
    ]);
    expect(retryDelayMs(0, 30000), 30000);
    expect(retryDelayMs(3, 2000), 8000);
    expect(retryDelayMs(0, 600000), 120000);
    expect(retryDelayMs(-3), 1000);
    expect(parseRetryAfter(' 30 '), 30000);
    expect(parseRetryAfter(null), isNull);
    expect(parseRetryAfter('Wed, 21 Oct 2026 07:28:00 GMT'), isNull);
    expect(parseRetryAfter('-5'), isNull);
  });

  test('audiobookRetriesQuietlyThenSkipsAfterThreeFailures', () {
    expect(nextAfterFailure(1), const FailureRetry(1000));
    expect(nextAfterFailure(2), const FailureRetry(2000));
    expect(nextAfterFailure(3), const FailureSkip());
    expect(nextAfterFailure(7), const FailureSkip());
  });

  test('retryStopsWhenItemChangesOrAppGoesToBackground', () {
    final m = PlaybackMachine();
    final g = m.begin();
    expect(m.isCurrent(g), isTrue);
    final next = m.begin();
    expect(m.isCurrent(g), isFalse);
    expect(m.isCurrent(next), isTrue);
    m.stop();
    expect(m.isCurrent(next), isFalse);
    expect(shouldStopForLifecycle(false, PlayState.loading), isTrue);
  });

  test('preflightDecidesBetweenSoundAndSilent', () {
    expect(audioModeFor(true, true, true), AudioMode.on);
    expect(audioModeFor(true, true, false), AudioMode.silent);
    expect(audioModeFor(true, true, null), AudioMode.on);
    expect(audioModeFor(true, true, statusAvailable(null)), AudioMode.on);
    expect(
      audioModeFor(
        true,
        true,
        statusAvailable(
          const AudioStatus(
            available: false,
            reason: 'all-models-blocked',
            retryAfterSeconds: 30,
          ),
        ),
      ),
      AudioMode.silent,
    );
    expect(
      audioModeFor(
        true,
        true,
        statusAvailable(const AudioStatus(available: false)),
      ),
      AudioMode.on,
    );
    expect(audioModeFor(true, false, true), AudioMode.silent);
    expect(audioModeFor(false, true, true), AudioMode.silent);
  });

  test('noAudioRequestWhenReadAloudIsOff', () {
    expect(mayRequestAudio(false), isFalse);
    expect(mayRequestAudio(true), isTrue);
    expect(
      shouldRecheckAudio(AudioMode.silent, false, 0, 10 * audioRecheckMs),
      isFalse,
    );
  });

  test('silentRoundRetriesOnNextItemAfterInterval', () {
    expect(
      shouldRecheckAudio(AudioMode.silent, true, 1000, 1000 + audioRecheckMs - 1),
      isFalse,
    );
    expect(
      shouldRecheckAudio(AudioMode.silent, true, 1000, 1000 + audioRecheckMs),
      isTrue,
    );
    expect(
      shouldRecheckAudio(AudioMode.on, true, 0, 10 * audioRecheckMs),
      isFalse,
    );
    expect(streamStartTimeoutMs(false), 12000);
  });

  test('understandIsVisibleAsSoonAsLessonsExist', () {
    expect(understandVisible(true), isTrue);
    expect(understandVisible(false), isFalse);
  });

  test('audioStartsLateOnlyWhenEnabledOnlineAndAvailable', () {
    expect(audioAfterCheck(true, true, true), isTrue);
    expect(audioAfterCheck(true, true, false), isFalse);
    expect(audioAfterCheck(false, true, true), isFalse);
    expect(audioAfterCheck(true, false, true), isFalse);
  });

  test('readingModeNeverAutoAdvancesWithoutFinishedAudio', () {
    expect(autoAdvance(true, true), isTrue);
    expect(autoAdvance(true, false), isFalse);
    expect(autoAdvance(false, true), isFalse);
  });

  test('streamTimeoutsDifferForQuestionAndLesson', () {
    expect(streamStartTimeoutMs(false), 12000);
    expect(streamStartTimeoutMs(true), 35000);
    expect(audioStatusTimeoutMs, 6000);
    expect(
      streamStartTimeoutMs(true),
      greaterThan(streamStartTimeoutMs(false)),
    );
  });

  test('lateAudioStartsWhileCardIsCurrentButNotAfterSwipe', () {
    bool within(int arrivalMs, bool lesson) =>
        arrivalMs <= streamStartTimeoutMs(lesson);
    expect(within(9000, false), isTrue);
    expect(within(13000, false), isFalse);
    expect(within(16000, true), isTrue);
    final m = PlaybackMachine();
    final old = m.begin();
    final next = m.begin();
    expect(m.playing(old), isFalse);
    expect(m.playing(next), isTrue);
    expect(audioAfterCheck(true, true, true), isTrue);
  });

  test('prefetchTargetsAreTheNextTwoQuestionsOrTheNextLesson', () {
    Task q(String id) => parseTask(Item(id: id, type: 'card'));
    const l = SessionLesson(
      id: 'L1',
      title: 'T',
      keyPoints: ['a'],
      ttsText: 'x',
      estSeconds: 40,
    );
    final teach = TaskTeach(teachItem(l), l);
    final tasks = [q('a'), q('b'), q('c'), q('d')];
    expect(
      prefetchTargets(tasks, 0).map((e) => e.id).toList(),
      const ['b', 'c'],
    );
    expect(
      prefetchTargets(tasks, 2).map((e) => e.id).toList(),
      const ['d'],
    );
    expect(prefetchTargets(tasks, 3), isEmpty);
    final withLesson = [q('a'), teach, q('b')];
    expect(prefetchTargets(withLesson, 0), const [
      PrefetchTarget('L1', true),
      PrefetchTarget('b', false),
    ]);
    expect(prefetchTargets(withLesson, 0, count: 1), const [
      PrefetchTarget('L1', true),
    ]);
  });

  test('buttonStaysVisibleThroughLoadingAndFailedAttempts', () {
    final h = AudioHealth();
    final seen = <bool>[];
    h.failure(1000);
    seen.add(h.visible);
    h.success(2000);
    seen.add(h.visible);
    h.failure(3000);
    seen.add(h.visible);
    h.failure(4000);
    seen.add(h.visible);
    h.success(5000);
    seen.add(h.visible);
    expect(seen.every((x) => x), isTrue);
  });

  test('buttonHidesAfterThreeFailuresInARowAndReturnsOnlyAfterHysteresis', () {
    final h = AudioHealth();
    h.failure(0);
    h.failure(1000);
    expect(h.visible, isTrue);
    h.failure(2000);
    expect(h.visible, isFalse);
    h.success(30000);
    expect(h.visible, isFalse);
    h.failure(40000);
    expect(h.visible, isFalse);
    h.success(2000 + 60000);
    expect(h.visible, isTrue);
    h.reset();
    expect(h.visible, isTrue);
  });

  test('clientLogIsThrottledAndShort', () {
    expect(clientLogAllowed(100000, 0, 0), isTrue);
    expect(clientLogAllowed(105000, 100000, 1), isFalse);
    expect(clientLogAllowed(110000, 100000, 1), isTrue);
    expect(clientLogAllowed(500000, 100000, 20), isFalse);
    expect(clientLogDetail('x' * 500).length, 120);
    expect(clientLogDetail('a\nb').contains('\n'), isFalse);
  });

  test('errorCodesDescribeHttpPlayerTimeoutAndHeader', () {
    String c(int? h, int? w, int? e, bool t, String? ct) => audioErrorCode(
          httpCode: h,
          playerWhat: w,
          playerExtra: e,
          timeout: t,
          contentType: ct,
        );
    expect(c(503, null, null, false, null), 'http_503');
    expect(c(404, null, null, false, 'audio/wav'), 'http_404');
    expect(c(null, 1, -1004, false, null), 'player_error:1:-1004');
    expect(c(null, null, null, true, null), 'timeout');
    expect(c(200, null, null, false, 'text/html'), 'header:text/html');
    expect(c(206, null, null, false, 'audio/wav'), 'error');
  });
}
