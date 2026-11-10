import 'dart:async';
import 'dart:io';
import 'package:just_audio/just_audio.dart';
import 'package:path_provider/path_provider.dart';
import '../data/app_log.dart';
import '../logic/feedback.dart';

/// Kurze synthetische Toene (Sinus-Arpeggios, ohne Asset-Dateien). Sie werden beim App-Start als WAV in den Cache geschrieben
/// und ueber einen Pool kleiner AudioPlayer ohne Unterbrechung des Vorlesens abgespielt.
class Sfx {
  static const String _tag = 'Sfx';

  final Map<List<Note>, String> _files = {};
  final List<AudioPlayer> _pool = [];
  int _nextPlayer = 0;
  bool _initialized = false;

  Future<void> init() async {
    if (_initialized) return;
    _initialized = true;
    try {
      final temp = await getTemporaryDirectory();
      final dir = Directory('${temp.path}/sfx')..createSync(recursive: true);
      for (var i = 0; i < Tones.all.length; i++) {
        final notes = Tones.all[i];
        final samples = synth(notes);
        final file = File('${dir.path}/tone_$i.wav');
        await file.writeAsBytes(wavBytes(samples));
        _files[notes] = file.path;
      }
      for (var i = 0; i < 3; i++) {
        _pool.add(AudioPlayer(handleAudioSessionActivation: false));
      }
    } catch (e) {
      AppLog.w(_tag, 'SFX-Initialisierung fehlgeschlagen', e);
    }
  }

  void play(List<Note> notes) {
    unawaited(_playAsync(notes));
  }

  Future<void> _playAsync(List<Note> notes) async {
    try {
      if (!_initialized) await init();
      var path = _files[notes];
      if (path == null) {
        final temp = await getTemporaryDirectory();
        final dir = Directory('${temp.path}/sfx')..createSync(recursive: true);
        final file = File('${dir.path}/tone_custom_${notes.hashCode}.wav');
        await file.writeAsBytes(wavBytes(synth(notes)));
        path = file.path;
        _files[notes] = path;
      }
      if (_pool.isEmpty) return;
      final player = _pool[_nextPlayer];
      _nextPlayer = (_nextPlayer + 1) % _pool.length;
      await player.stop();
      await player.setFilePath(path);
      unawaited(player.play());
    } catch (e) {
      AppLog.w(_tag, 'Ton konnte nicht abgespielt werden', e);
    }
  }

  void dispose() {
    for (final p in _pool) {
      p.dispose();
    }
    _pool.clear();
  }
}
