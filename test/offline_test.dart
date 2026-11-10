import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/data/local_data.dart';
import 'package:quickscope/data/models.dart';
import 'package:quickscope/logic/attempt_queue.dart';
import 'package:quickscope/logic/offline.dart';

class _Mem implements QueueStorage {
  final m = <String, String>{};
  @override
  String? read(String key) => m[key];
  @override
  void write(String key, String? value) {
    if (value == null) {
      m.remove(key);
    } else {
      m[key] = value;
    }
  }
}

void main() {
  SessionEntry q(String id) =>
      SessionEntry(item: Item(id: id, type: 'card', estSeconds: 20));
  SessionEntry l(String id, [int s = 60]) => SessionEntry(
        lesson: SessionLesson(
          id: id,
          title: 'T',
          keyPoints: const ['a'],
          ttsText: 'x',
          estSeconds: s,
        ),
      );

  test('packParsingFollowsTheContract', () {
    const json =
        '{"generatedAt":"2026-11-09T19:47:48Z","version":"abc123","exam":{"id":"e"},"queue":[{"id":"a","type":"card","estSeconds":8},{"type":"lesson","lesson":{"id":"L1","title":"T","estSeconds":40}},{"kaputt":true}],'
        '"reserveItems":[{"id":"r1","type":"card"}],"topics":[{"id":"t1","title":"X"}],"extra":1}';
    final p = parseOfflinePack(json)!;
    expect(p.version, 'abc123');
    expect(
      p.entries.map((e) => e.item?.id ?? e.lesson?.id).toList(),
      const ['a', 'L1'],
    );
    expect(p.reserve.length, 1);
    expect(p.topics.map((t) => t.id).toList(), const ['t1']);
    expect(parseOfflinePack('{"items":[{"id":"x","type":"card"}]}'), isNull);
    expect(parseOfflinePack('kein json'), isNull);
  });

  test('roundFollowsPackOrderSkipsDoneAndFillsFromReserve', () {
    final pack = OfflinePack(
      entries: [q('a'), q('b'), l('L1', 60), q('c')],
      reserve: [q('r1'), q('r2')],
    );
    expect(
      offlineRound(pack, const {}, 40).map((e) => e.item!.id).toList(),
      const ['a', 'b'],
    );
    expect(
      offlineRound(pack, const {'q:a'}, 60 + 20 + 20)
          .map((e) => e.item?.id ?? e.lesson!.id)
          .toList(),
      const ['b', 'L1', 'c'],
    );
    expect(
      offlineRound(pack, const {}, 10000, questionsOnly: true)
          .take(3)
          .map((e) => e.item!.id)
          .toList(),
      const ['a', 'b', 'c'],
    );
    expect(
      offlineRound(pack, const {'q:a', 'q:b', 'l:L1', 'q:c'}, 10000)
          .map((e) => e.item!.id)
          .toList(),
      const ['r1', 'r2'],
    );
    expect(offlineRound(pack, const {}, 0), isEmpty);
  });

  test('wrongAnswerComesBackAfterFourToEightItems', () {
    for (final key in ['a', 'bb', 'ccc', 'item-42', 'zzz']) {
      final at = offlineRetryIndex(2, 100, key);
      expect(at - 2 - 1, inInclusiveRange(4, 8), reason: '$key -> $at');
    }
    expect(offlineRetryIndex(2, 5, 'a'), 5);
  });

  test('dontKnowPicksNextUnseenLesson', () {
    final pack = OfflinePack(
      entries: [q('a'), l('L1'), l('L2')],
      reserve: [l('L3')],
    );
    expect(nextOfflineLesson(pack, const {'L1'}, const {})!.id, 'L2');
    expect(nextOfflineLesson(pack, const {'L1'}, const {'l:L2'})!.id, 'L3');
    expect(
      nextOfflineLesson(pack, const {'L1', 'L2', 'L3'}, const {}),
      isNull,
    );
  });

  test('attemptsAreIdempotentByOfflineId', () {
    final mem = _Mem();
    final queue = AttemptQueue(mem);
    const a = AttemptRequest(
      itemId: 'i1',
      outcome: 'correct',
      ms: 900,
      at: '2026-11-09T10:00:00Z',
      clientId: 'id-1',
    );
    queue.add('e', a);
    queue.add('e', a);
    queue.add(
      'e',
      const AttemptRequest(
        itemId: 'i2',
        outcome: 'wrong',
        ms: 500,
        clientId: 'id-2',
      ),
    );
    expect(
      queue.pending('e').map((e) => e.clientId).toList(),
      const ['id-1', 'id-2'],
    );
    final first = queue.pending('e');
    final again = queue.pending('e');
    expect(
      first.map((e) => e.toJson()).toList(),
      again.map((e) => e.toJson()).toList(),
    );
    queue.removeSent('e', 2);
    expect(queue.pending('e'), isEmpty);
  });

  test('localStoreIsAtomicVersionedAndWipes', () {
    final dir = Directory.systemTemp.createTempSync('qs');
    addTearDown(() {
      if (dir.existsSync()) dir.deleteSync(recursive: true);
    });
    final offlineDir = Directory('${dir.path}/offline');
    final local = LocalData(offlineDir);
    expect(local.exams(), isNull);
    local.saveExams(const [Exam(id: 'e1', name: 'IHK')]);
    expect(local.exams()!.first.name, 'IHK');
    local.savePack(
      'e1',
      '{"version":"v1","queue":[{"id":"a","type":"card"}]}',
      3,
      '"v1"',
    );
    expect(local.packEtag('e1'), '"v1"');
    expect(local.pack('e1'), isNotNull);
    expect(local.packDays('e1'), 3);
    local.markDone('e1', 'q:a');
    local.markDone('e1', 'q:b');
    expect(local.doneKeys('e1'), const {'q:a', 'q:b'});
    local.clearDone('e1');
    expect(local.doneKeys('e1'), isEmpty);
    File('${offlineDir.path}/exams.json').writeAsStringSync('{"v":99,"d":[]}');
    expect(local.exams(), isNull);
    File('${offlineDir.path}/pack_e1.json').writeAsStringSync('kaputt');
    expect(local.pack('e1'), isNull);
    local.clear();
    expect(local.packDays('e1'), isNull);
    expect(offlineDir.existsSync(), isFalse);
  });

  test('gatingTextAndListenAvailability', () {
    expect(needsInternet, 'Dafür brauchst du Internet.');
    expect(canListen(true, true, true), isTrue);
    expect(canListen(false, true, true), isFalse);
    expect(canListen(true, false, true), isFalse);
    expect(canListen(true, true, false), isFalse);
  });
}
