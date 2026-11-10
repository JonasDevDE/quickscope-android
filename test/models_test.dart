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
}
