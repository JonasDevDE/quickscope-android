import 'dart:io';
import 'dart:math' as math;
import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/logic/upload_queue.dart';

void main() {
  UploadHttpException http(int code) => UploadHttpException(code);

  Future<UploadSummary> runQ(
    List<String> items,
    Future<void> Function(int, String) upload, {
    List<String>? log,
    List<int>? waits,
  }) =>
      runUploadQueue<String>(
        size: () => items.length,
        itemAt: (i) => items[i],
        wait: (ms) async => waits?.add(ms),
        backoffMs: (attempt) => 1000 << attempt,
        onState: (i, st, _) {
          final cap = st.name[0].toUpperCase() + st.name.substring(1);
          log?.add('$i:$cap');
        },
        upload: upload,
      );

  test('filesAreUploadedStrictlyOneAfterTheOtherInOrder', () async {
    final order = <String>[];
    var running = 0;
    var maxParallel = 0;
    final s = await runQ(['a', 'b', 'c', 'd'], (_, f) async {
      running++;
      maxParallel = math.max(maxParallel, running);
      order.add(f);
      running--;
    });
    expect(order, const ['a', 'b', 'c', 'd']);
    expect(maxParallel, 1);
    expect(s.done, 4);
    expect(s.failed, isEmpty);
    expect(uploadSummaryText(s.done, s.total), '4 von 4 hochgeladen');
  });

  test('oneFailureDoesNotStopTheOthersAndSummaryCountsThem', () async {
    final log = <String>[];
    final s = await runQ(
      ['a', 'b', 'c'],
      (_, f) async {
        if (f == 'b') throw http(413);
      },
      log: log,
    );
    expect(s.done, 2);
    expect(s.failed, const [1]);
    expect(uploadSummaryText(s.done, s.total), '2 von 3 hochgeladen');
    expect(log, const [
      '0:Uploading',
      '0:Done',
      '1:Uploading',
      '1:Failed',
      '2:Uploading',
      '2:Done',
    ]);
  });

  test('retriesUpToThreeTimesWithBackoffThenMovesOn', () async {
    final attempts = <String, int>{};
    final waits = <int>[];
    final s = await runQ(
      ['a', 'b'],
      (_, f) async {
        attempts[f] = (attempts[f] ?? 0) + 1;
        if (f == 'a') throw http(503);
        if (f == 'b' && attempts[f]! < 3) throw const SocketException('Netz');
      },
      waits: waits,
    );
    expect(attempts['a'], 3);
    expect(attempts['b'], 3);
    expect(s.done, 1);
    expect(s.failed, const [0]);
    expect(waits, const [1000, 2000, 1000, 2000]);
  });

  test('nonRetryableErrorsFailImmediately', () async {
    var tries = 0;
    final s = await runQ(['a'], (_, __) async {
      tries++;
      throw http(400);
    });
    expect(tries, 1);
    expect(s.failed, const [0]);
    expect(uploadRetryable(http(429)), isTrue);
    expect(uploadRetryable(http(500)), isTrue);
    expect(uploadRetryable(http(408)), isTrue);
    expect(uploadRetryable(const SocketException('')), isTrue);
    expect(uploadRetryable(http(413)), isFalse);
    expect(uploadRetryable(http(404)), isFalse);
    expect(uploadRetryable(ArgumentError()), isFalse);
  });

  test('filesAddedDuringTheRunAreProcessedAfterwards', () async {
    final items = ['a', 'b'];
    final done = <String>[];
    final s = await runUploadQueue<String>(
      size: () => items.length,
      itemAt: (i) => items[i],
      upload: (_, f) async {
        done.add(f);
        if (f == 'a') items.add('c');
      },
    );
    expect(done, const ['a', 'b', 'c']);
    expect(s.total, 3);
  });

  test('cancellationEndsTheRun', () async {
    var ended = false;
    try {
      await runUploadQueue<int>(
        size: () => 3,
        itemAt: (i) => i,
        upload: (i, _) async {
          if (i == 1) throw const UploadCancelledException();
        },
      );
    } on UploadCancelledException {
      ended = true;
    }
    expect(ended, isTrue);
  });

  test('progressTextAndServerMessagesAreShownInPlainLanguage', () {
    expect(uploadProgressText(3, 10), '3 von 10');
    expect(
      uploadFailureText('Datei X ist größer als 40 MB.', 413, false),
      'Datei X ist größer als 40 MB.',
    );
    expect(
      uploadFailureText('Bitte weniger Dateien auf einmal.', 400, false),
      'Bitte weniger Dateien auf einmal.',
    );
    expect(uploadFailureText(null, 413, false).contains('30'), isFalse);
    expect(uploadFailureText(null, null, true), 'Kein Netz.');
  });

  test('photosAreScaledToTheLongestEdgeAndDocumentsAreUntouched', () {
    expect(scaledSize(4000, 3000), const (2560, 1920));
    expect(scaledSize(3000, 4000), const (1920, 2560));
    expect(scaledSize(1000, 800), const (1000, 800));
    final s8k = scaledSize(8000, 6000);
    expect(math.max(s8k.$1, s8k.$2), uploadMaxEdge);
    expect(needsImageCompression('image/jpeg', 10000000, 4000, 3000), isTrue);
    expect(needsImageCompression('image/heic', 6000000, 4000, 3000), isTrue);
    expect(needsImageCompression('image/jpeg', 900000, 3200, 2400), isTrue);
    expect(needsImageCompression('image/jpeg', 900000, 1600, 1200), isFalse);
    expect(needsImageCompression('image/gif', 20000000, 4000, 3000), isFalse);
    expect(needsImageCompression('application/pdf', 20000000, 0, 0), isFalse);
    final (w, h) = scaledSize(4000, 3000);
    expect(w * h * 0.35, lessThan(2000000));
  });
}
