import 'package:flutter/foundation.dart';

/// Zentraler Logger: nur im Debug-Build, im Release ganz aus (keine Titel, Nutzertexte oder Fehlerdetails im Log).
abstract final class AppLog {
  static void d(String tag, String msg) {
    if (kDebugMode) debugPrint('D/$tag: $msg');
  }

  static void i(String tag, String msg) {
    if (kDebugMode) debugPrint('I/$tag: $msg');
  }

  static void w(String tag, String msg, [Object? error]) {
    if (kDebugMode) {
      debugPrint('W/$tag: $msg${error != null ? ' ($error)' : ''}');
    }
  }
}
