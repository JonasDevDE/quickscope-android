import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:quickscope/config.dart';

void main() {
  test('AppConfig-Version stimmt mit pubspec.yaml ueberein', () {
    final line = File('pubspec.yaml')
        .readAsLinesSync()
        .firstWhere((l) => l.startsWith('version:'));
    final value = line.substring('version:'.length).trim();
    expect(value, '${AppConfig.versionName}+${AppConfig.versionCode}');
  });
}
