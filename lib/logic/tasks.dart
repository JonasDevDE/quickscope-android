import 'dart:collection';
import 'dart:math' as math;
import '../data/models.dart';

/// Aufbereitete Aufgabe fuer die Anzeige; unlesbare Payloads fallen auf eine einfache Karte zurueck.
sealed class Task {
  final Item item;
  const Task(this.item);
}

/// Karte: Frage, aufdecken, dann "wusste ich"/"nicht gewusst".
class TaskCard extends Task {
  final String answer;
  const TaskCard(super.item, this.answer);
}

/// mc, choice2, number, truefalse: eine richtige Option.
class TaskChoice extends Task {
  final List<String> options;
  final int correct;
  const TaskChoice(super.item, this.options, this.correct);
}

class TaskCloze extends Task {
  final String text;
  final List<String> bank;
  final String answer;
  const TaskCloze(super.item, this.text, this.bank, this.answer);
}

class TaskSpot extends Task {
  final List<String> statements;
  final int falseIndex;
  const TaskSpot(super.item, this.statements, this.falseIndex);
}

/// [leftOrder]/[rightOrder]: Spalten (Texte) in der Reihenfolge des Backends (payload.left/right, rechts gemischt mit Derangement) - bevorzugt;
/// fehlen sie (Altformat `pairs`), mischt der Client. [leftIds]/[rightIds]: Text -> id fuer die Antwort `{pairs:{leftId:rightId}}`.
class TaskMatch extends Task {
  final List<(String, String)> pairs;
  final List<String>? leftOrder;
  final List<String>? rightOrder;
  final Map<String, String>? leftIds;
  final Map<String, String>? rightIds;

  const TaskMatch(
    super.item,
    this.pairs, {
    this.leftOrder,
    this.rightOrder,
    this.leftIds,
    this.rightIds,
  });
}

class BucketEntry {
  final String id;
  final String text;
  final String categoryId;
  const BucketEntry(this.id, this.text, this.categoryId);

  @override
  bool operator ==(Object other) =>
      other is BucketEntry &&
      other.id == id &&
      other.text == text &&
      other.categoryId == categoryId;

  @override
  int get hashCode => Object.hash(id, text, categoryId);
}

/// Einsortieren: 2 bis 4 Kategorien (id, Titel), 4 bis 6 Elemente, die nacheinander einer Kategorie zugeordnet werden.
class TaskBucket extends Task {
  final List<(String, String)> categories;
  final List<BucketEntry> entries;
  const TaskBucket(super.item, this.categories, this.entries);
}

/// Satz/Formel bauen: Bausteine (id, Text) und die richtige Reihenfolge der ids.
class TaskBuild extends Task {
  final List<(String, String)> tokens;
  final List<List<String>> solutions;
  const TaskBuild(super.item, this.tokens, this.solutions);

  /// Die Hauptloesung (payload.solution); [solutions] enthaelt alle gueltigen Reihenfolgen.
  List<String> get solution => solutions.first;
}

/// [steps] in richtiger Reihenfolge; [shown] = Reihenfolge der Anzeige vom Backend (payload.items, gemischt); [ids] = Schritt-ids parallel zu [steps] fuer die Antwort `{order:[...]}`.
class TaskOrder extends Task {
  final List<String> steps;
  final List<String>? shown;
  final List<String>? ids;
  const TaskOrder(super.item, this.steps, [this.shown, this.ids]);
}

/// Loesungsweg: ein ruhiger Kartenblock mit hoechstens 3 Schritten und optionaler Merkregel.
class TaskLesson extends Task {
  final List<String> steps;
  final String? trick;
  const TaskLesson(super.item, this.steps, this.trick);
}

/// Lektion (Hoeren vor Abfragen): Titel und Stichworte, Lektion wird abgespielt.
class TaskTeach extends Task {
  final SessionLesson lesson;
  const TaskTeach(super.item, this.lesson);
}

extension _JsonMapExt on Map<String, dynamic> {
  List<String>? strings(String key) {
    final v = this[key];
    if (v is! List) return null;
    final out = <String>[];
    for (final e in v) {
      if (e is String) {
        out.add(e);
      } else if (e is num || e is bool) {
        out.add(e.toString());
      }
    }
    return out;
  }

  String? string(String key) {
    final v = this[key];
    if (v is String) return v;
    if (v is num || v is bool) return v.toString();
    return null;
  }

  int? integer(String key) {
    final v = this[key];
    if (v is int) return v;
    if (v is num) return v.toInt();
    if (v is String) return int.tryParse(v);
    return null;
  }

  List<Map<String, dynamic>>? objects(String key) {
    final v = this[key];
    if (v is! List) return null;
    return [for (final e in v) if (e is Map<String, dynamic>) e];
  }

  (String, String)? idText() {
    final id = string('id');
    final text = string('text');
    return (id != null && text != null) ? (id, text) : null;
  }
}

/// Hoechstens 3 Schritte fuer Loesungswege.
List<String> lessonSteps(Item item) =>
    (item.solutionSteps ?? const [])
        .where((s) => s.trim().isNotEmpty)
        .take(3)
        .toList();

Task parseTask(Item item) {
  final p = item.payload;
  final fallback = TaskCard(item, item.answer);
  if (item.lesson) {
    final steps = lessonSteps(item);
    if (steps.isNotEmpty) return TaskLesson(item, steps, item.trick);
  }
  switch (item.type) {
    case 'card':
      return fallback;
    case 'mc':
    case 'choice2':
    case 'number':
      final options = item.options ?? p?.strings('options');
      final correct = item.correctIndex ?? p?.integer('correctIndex');
      if (options != null &&
          options.length >= 2 &&
          correct != null &&
          correct >= 0 &&
          correct < options.length) {
        return TaskChoice(item, options, correct);
      }
      return fallback;
    case 'truefalse':
      return TaskChoice(
        item,
        const ['Wahr', 'Falsch'],
        item.answer.trim().toLowerCase() == 'wahr' ? 0 : 1,
      );
    case 'cloze':
      final text = p?.string('text') ?? item.question;
      final bank = p?.strings('bank');
      final answer = p?.string('answer') ?? item.answer;
      if (bank != null && bank.length >= 2 && bank.contains(answer)) {
        return TaskCloze(item, text, bank, answer);
      }
      return fallback;
    case 'spot':
      final statements = p?.strings('statements');
      final idx = p?.integer('falseIndex');
      if (statements != null &&
          statements.length >= 2 &&
          idx != null &&
          idx >= 0 &&
          idx < statements.length) {
        final order = shuffledFor(item, [for (var i = 0; i < statements.length; i++) i]);
        return TaskSpot(
          item,
          [for (final i in order) statements[i]],
          order.indexOf(idx),
        );
      }
      return fallback;
    case 'match':
      return _parseMatch(item, p) ?? fallback;
    case 'bucket':
      return _parseBucket(item, p) ?? fallback;
    case 'build':
      return _parseBuild(item, p) ?? fallback;
    case 'order':
      return _parseOrder(item, p) ?? fallback;
    default:
      return fallback;
  }
}

TaskMatch? _parseMatch(Item item, Map<String, dynamic>? p) {
  if (p == null) return null;
  final leftObjs = p.objects('left');
  final rightObjs = p.objects('right');
  final solution = p['solution'];
  if (leftObjs != null && rightObjs != null && solution is Map<String, dynamic>) {
    final lefts = [for (final o in leftObjs) if (o.idText() case final t?) t];
    final rights = [for (final o in rightObjs) if (o.idText() case final t?) t];
    if (lefts.length >= 2) {
      final pairs = <(String, String)>[];
      for (final (lid, ltext) in lefts) {
        final rid = solution[lid]?.toString();
        if (rid == null) break;
        (String, String)? rMatch;
        for (final r in rights) {
          if (r.$1 == rid) {
            rMatch = r;
            break;
          }
        }
        if (rMatch != null) pairs.add((ltext, rMatch.$2));
      }
      if (pairs.length == lefts.length) {
        return TaskMatch(
          item,
          pairs,
          leftOrder: [for (final l in lefts) l.$2],
          rightOrder: [for (final r in rights) r.$2],
          leftIds: {for (final l in lefts) l.$2: l.$1},
          rightIds: {for (final r in rights) r.$2: r.$1},
        );
      }
    }
  }
  final rawPairs = p.objects('pairs');
  if (rawPairs != null) {
    final pairs = <(String, String)>[];
    for (final o in rawPairs) {
      final l = o.string('left');
      final r = o.string('right');
      if (l != null && r != null) pairs.add((l, r));
    }
    if (pairs.length >= 2) return TaskMatch(item, pairs);
  }
  return null;
}

TaskOrder? _parseOrder(Item item, Map<String, dynamic>? p) {
  if (p == null) return null;
  final rawItems = p.objects('items');
  final solution = p.strings('solution');
  if (rawItems != null && solution != null && solution.length >= 2) {
    final items = [for (final o in rawItems) if (o.idText() case final t?) t];
    final byId = {for (final it in items) it.$1: it.$2};
    if (solution.every(byId.containsKey)) {
      return TaskOrder(
        item,
        [for (final id in solution) byId[id]!],
        [for (final it in items) it.$2],
        solution,
      );
    }
  }
  final steps = p.strings('steps');
  return (steps != null && steps.length >= 2) ? TaskOrder(item, steps) : null;
}

TaskBucket? _parseBucket(Item item, Map<String, dynamic>? p) {
  if (p == null) return null;
  final rawCats = p.objects('categories');
  final rawItems = p.objects('items');
  if (rawCats == null || rawItems == null) return null;
  final categories = <(String, String)>[];
  for (final o in rawCats) {
    final id = o.string('id');
    final label = o.string('label');
    if (id == null || label == null) return null;
    categories.add((id, label));
  }
  final entries = <BucketEntry>[];
  for (final o in rawItems) {
    final id = o.string('id');
    final text = o.string('text');
    final catId = o.string('categoryId');
    if (id == null || text == null || catId == null) return null;
    entries.add(BucketEntry(id, text, catId));
  }
  final catIds = {for (final c in categories) c.$1};
  if (categories.length < 2 ||
      categories.length > 4 ||
      entries.length < 2 ||
      entries.any((e) => !catIds.contains(e.categoryId))) {
    return null;
  }
  return TaskBucket(item, categories, entries);
}

TaskBuild? _parseBuild(Item item, Map<String, dynamic>? p) {
  if (p == null) return null;
  final rawTokens = p.objects('tokens');
  final solution = p.strings('solution');
  if (rawTokens == null || solution == null) return null;
  final tokens = [for (final o in rawTokens) if (o.idText() case final t?) t];
  if (tokens.length != rawTokens.length) return null;

  final others = <List<String>>[];
  final rawSolutions = p['solutions'];
  if (rawSolutions is List) {
    for (final el in rawSolutions) {
      if (el is List) {
        others.add([for (final x in el) if (x is String) x]);
      }
    }
  }

  final all = <List<String>>[];
  for (final sol in [solution, ...others]) {
    if (sol.length >= 2 && !all.any((existing) => _listEquals(existing, sol))) {
      all.add(sol);
    }
  }
  final tokenIds = {for (final t in tokens) t.$1};
  if (tokens.length < 2 ||
      all.isEmpty ||
      all.any((sol) => sol.any((id) => !tokenIds.contains(id)))) {
    return null;
  }
  return TaskBuild(item, tokens, all);
}

bool _listEquals<T>(List<T> a, List<T> b) {
  if (a.length != b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (a[i] != b[i]) return false;
  }
  return true;
}

List<T> _shuffledWith<T>(List<T> list, math.Random rnd) {
  final copy = [...list];
  copy.shuffle(rnd);
  return copy;
}

/// Mischt [base] so, dass kein Element an seiner Ausgangsstelle bleibt (Derangement), stabil je Aufgabe (seed = itemId).
List<T> derangedFor<T>(Item item, List<T> base) {
  final n = base.length;
  if (n < 2) return base;
  final rnd = math.Random(item.id.hashCode ^ 0x5DEECE66D);
  final idx = [for (var i = 0; i < n; i++) i];
  var perm = _shuffledWith(idx, rnd);
  var tries = 0;
  while (
      [for (var i = 0; i < n; i++) perm[i] == i].any((same) => same) &&
      tries++ < 50) {
    perm = _shuffledWith(idx, rnd);
  }
  if ([for (var i = 0; i < n; i++) perm[i] == i].any((same) => same)) {
    perm = [for (var i = 0; i < n; i++) (i + 1) % n];
  }
  return [for (final i in perm) base[i]];
}

/// Antwort-Ergebnis fuer das Backend: Karten und Loesungswege known/unknown, alles andere right/wrong.
String outcomeFor(Task task, bool correct) => switch (task) {
      TaskCard() || TaskLesson() || TaskTeach() => correct ? 'known' : 'unknown',
      _ => correct ? 'right' : 'wrong',
    };

/// Stabile Mischung je Aufgabe, damit die Reihenfolge beim Neuzeichnen gleich bleibt (und nie schon geloest ist).
List<T> shuffledFor<T>(Item item, List<T> list) {
  if (list.length < 2) return list;
  final rnd = math.Random(item.id.hashCode);
  var out = _shuffledWith(list, rnd);
  var tries = 0;
  while (_listEquals(out, list) && tries++ < 5) {
    out = _shuffledWith(list, rnd);
  }
  if (_listEquals(out, list)) {
    out = [...list.skip(1), list.first];
  }
  return out;
}

/// Ergebnis eines Tipps auf die rechte Seite einer Zuordnung.
enum MatchResult { none, right, wrong, revealed }

/// Zuordnung per Tippen: links waehlen, rechts waehlen.
class MatchState {
  static const int maxTries = 2;

  final List<String> lefts;
  final List<String> rights;
  final Map<String, String> _solution;

  final Set<String> matched = <String>{};
  final Set<String> revealed = <String>{};
  final Map<String, int> _tries = <String, int>{};
  final Map<String, int> _colors = <String, int>{};

  String? _selectedLeft;
  int _mistakes = 0;
  bool _dontKnow = false;

  MatchState(this.lefts, this.rights, this._solution);

  String? get selectedLeft => _selectedLeft;
  int get mistakes => _mistakes;
  bool get dontKnow => _dontKnow;
  bool get done => matched.length == lefts.length;
  bool get known => _mistakes == 0 && revealed.isEmpty;

  void tapLeft(String left) {
    if (!matched.contains(left)) {
      _selectedLeft = _selectedLeft == left ? null : left;
    }
  }

  MatchResult tapRight(String right) {
    final left = _selectedLeft;
    if (left == null) return MatchResult.none;
    _selectedLeft = null;
    if (_solution[left] == right) {
      _connect(left);
      return MatchResult.right;
    }
    _mistakes++;
    final n = (_tries[left] ?? 0) + 1;
    _tries[left] = n;
    if (n >= maxTries) {
      revealed.add(left);
      _connect(left);
      return MatchResult.revealed;
    }
    return MatchResult.wrong;
  }

  void _connect(String left) {
    matched.add(left);
    _colors[left] = _colors.length;
  }

  int colorOf(String left) => _colors[left] ?? -1;

  int colorOfRight(String right) {
    for (final l in matched) {
      if (_solution[l] == right) return colorOf(l);
    }
    return -1;
  }

  void revealAll() {
    for (final l in lefts) {
      if (!matched.contains(l)) _connect(l);
    }
    _selectedLeft = null;
    _dontKnow = true;
  }

  Map<String, String> connected() => {
        for (final l in matched)
          if (_solution[l] case final r?) l: r,
      };

  bool isRightMatched(String right) =>
      matched.any((l) => _solution[l] == right);

  static MatchState of(TaskMatch task) {
    final solution = {for (final p in task.pairs) p.$1: p.$2};
    if (task.leftOrder != null && task.rightOrder != null) {
      return MatchState(task.leftOrder!, task.rightOrder!, solution);
    }
    final pairs = shuffledFor(task.item, task.pairs);
    return MatchState(
      [for (final p in pairs) p.$1],
      derangedFor(task.item, [for (final p in pairs) p.$2]),
      solution,
    );
  }
}

/// Reihenfolge per Tippen: der Reihe nach den naechsten Schritt waehlen; jeder falsche Tipp zaehlt als Fehler.
class OrderState {
  final List<String> steps;
  final List<String> shuffled;

  int _next = 0;
  int _mistakes = 0;
  bool _revealedAll = false;

  OrderState(this.steps, this.shuffled);

  int get next => _next;
  int get mistakes => _mistakes;
  bool get revealedAll => _revealedAll;
  bool get done => _next >= steps.length;

  bool tap(String step) {
    if (done) return false;
    if (steps[_next] == step) {
      _next++;
      return true;
    } else {
      _mistakes++;
      return false;
    }
  }

  void revealAll() {
    _next = steps.length;
    _revealedAll = true;
  }

  bool isTaken(String step) => steps.take(_next).contains(step);

  static OrderState of(TaskOrder task) =>
      OrderState(task.steps, task.shown ?? shuffledFor(task.item, task.steps));
}

/// Ergebnis eines Tipps auf eine Kategorie beim Einsortieren.
enum BucketResult { right, wrong, revealed }

class BucketState {
  static const int maxTries = 2;

  final List<(String, String)> categories;
  final List<BucketEntry> entries;
  final LinkedHashMap<String, String> placed = LinkedHashMap<String, String>();

  int _index = 0;
  int _mistakes = 0;
  int _tries = 0;
  bool _anyRevealed = false;

  BucketState(this.categories, this.entries);

  int get index => _index;
  int get mistakes => _mistakes;
  bool get anyRevealed => _anyRevealed;
  BucketEntry? get current =>
      (_index >= 0 && _index < entries.length) ? entries[_index] : null;
  bool get done => _index >= entries.length;
  bool get known => _mistakes == 0;

  BucketResult? tap(String categoryId) {
    final e = current;
    if (e == null) return null;
    if (e.categoryId == categoryId) {
      placed[e.id] = categoryId;
      _index++;
      _tries = 0;
      return BucketResult.right;
    }
    _mistakes++;
    _tries++;
    if (_tries >= maxTries) {
      placed[e.id] = e.categoryId;
      _index++;
      _tries = 0;
      _anyRevealed = true;
      return BucketResult.revealed;
    }
    return BucketResult.wrong;
  }

  void revealAll() {
    for (final e in entries.skip(_index)) {
      placed[e.id] = e.categoryId;
    }
    _index = entries.length;
    _anyRevealed = true;
  }

  List<BucketEntry> placedIn(String categoryId) => [
        for (final e in entries)
          if (placed[e.id] == categoryId) e,
      ];

  static BucketState of(TaskBucket task) =>
      BucketState(task.categories, task.entries);
}

enum BuildResult { right, wrong, revealed }

class BuildState {
  static const int maxTries = 2;

  final List<(String, String)> tokens;
  final List<List<String>> solutions;
  final List<(String, String)> shuffled;
  final List<String> row = <String>[];

  int _mistakes = 0;
  bool _done = false;
  bool _revealed = false;
  int _tries = 0;
  late final int _maxLength =
      solutions.fold<int>(0, (m, s) => math.max(m, s.length));
  late final Set<int> _lengths = {for (final s in solutions) s.length};

  BuildState(this.tokens, this.solutions, this.shuffled);

  int get mistakes => _mistakes;
  bool get done => _done;
  bool get revealed => _revealed;
  bool get complete => _lengths.contains(row.length);
  bool get known => _mistakes == 0 && !_revealed;

  String textOf(String id) {
    for (final t in tokens) {
      if (t.$1 == id) return t.$2;
    }
    return '';
  }

  bool inRow(String id) => row.contains(id);

  void tapChip(String id) {
    if (!_done && !row.contains(id) && row.length < _maxLength) {
      row.add(id);
    }
  }

  void tapRow(int position) {
    if (!_done && position >= 0 && position < row.length) {
      row.removeAt(position);
    }
  }

  BuildResult? check() {
    if (!complete || _done) return null;
    if (solutions.any((s) => _listEquals(s, row))) {
      _done = true;
      return BuildResult.right;
    }
    _mistakes++;
    _tries++;
    if (_tries >= maxTries) {
      _showSolution();
      return BuildResult.revealed;
    }
    return BuildResult.wrong;
  }

  void revealAll() {
    if (!_done) _showSolution();
  }

  void _showSolution() {
    row
      ..clear()
      ..addAll(solutions.first);
    _done = true;
    _revealed = true;
  }

  static BuildState of(TaskBuild task) {
    final ids = [for (final t in task.tokens) t.$1];
    final inOrder = task.solutions.any((s) => _listEquals(s, ids));
    return BuildState(
      task.tokens,
      task.solutions,
      inOrder ? shuffledFor(task.item, task.tokens) : task.tokens,
    );
  }
}

/// Antwort fuer `POST /attempts` (Feld `answer`) bei den Anordnungs-Aufgaben.
Map<String, dynamic>? matchAnswer(TaskMatch task, MatchState state) {
  final l = task.leftIds;
  final r = task.rightIds;
  if (l == null || r == null) return null;
  if (!state.done || state.dontKnow || state.revealed.isNotEmpty) return null;
  final pairs = <String, String>{};
  for (final entry in state.connected().entries) {
    final lid = l[entry.key];
    final rid = r[entry.value];
    if (lid == null || rid == null) return null;
    pairs[lid] = rid;
  }
  return {'pairs': pairs};
}

Map<String, dynamic>? orderAnswer(TaskOrder task, OrderState state) {
  final ids = task.ids;
  if (ids == null || !state.done || state.revealedAll) return null;
  return {'order': ids};
}

Map<String, dynamic>? bucketAnswer(BucketState state) {
  if (!state.done || state.anyRevealed) return null;
  return {'assignments': Map<String, String>.from(state.placed)};
}

Map<String, dynamic>? buildAnswer(BuildState state) {
  if (!state.done || state.revealed) return null;
  return {'order': List<String>.from(state.row)};
}

/// Hoechstens zwei kurze Saetze der Erklaerung.
String? shortExplanation(String? text) {
  final t = text?.trim() ?? '';
  if (t.isEmpty) return null;
  final sentences = t.split(RegExp(r'(?<=[.!?])\s+'));
  return sentences.take(2).join(' ');
}

/// Die richtige Antwort als kurzer Text fuer das Feedback.
String correctText(Task task) => switch (task) {
      TaskCard(:final answer) => answer,
      TaskChoice(:final options, :final correct) => options[correct],
      TaskCloze(:final answer) => answer,
      TaskSpot(:final statements, :final falseIndex) => statements[falseIndex],
      TaskMatch(:final pairs) =>
        [for (final p in pairs) '${p.$1} = ${p.$2}'].join('; '),
      TaskBucket(:final entries, :final categories) => [
          for (final e in entries)
            '${e.text} = ${categories.firstWhere((c) => c.$1 == e.categoryId).$2}',
        ].join('; '),
      TaskBuild(:final solution, :final tokens) => [
          for (final id in solution) tokens.firstWhere((t) => t.$1 == id).$2,
        ].join(' '),
      TaskOrder(:final steps) => steps.join(' → '),
      TaskLesson() || TaskTeach() => '',
    };

/// Anteil der Runde, der geschafft ist (nach geschaetzter Zeit); fuer den Zeitbalken oben.
double sessionProgress(List<Item> items, int doneCount) {
  final total = items.fold<int>(0, (s, it) => s + math.max(1, it.estSeconds));
  if (total == 0) return 0.0;
  final done = items
      .take(doneCount)
      .fold<int>(0, (s, it) => s + math.max(1, it.estSeconds));
  return done / total;
}

/// Ablauf einer Karte ohne Wisch: erst aufdecken, dann "gewusst"/"nicht gewusst"; vorher gibt es kein Ergebnis.
class CardFlow {
  bool _revealed = false;
  bool get revealed => _revealed;

  void reveal() => _revealed = true;

  String? answer(bool known) =>
      !_revealed ? null : (known ? 'known' : 'unknown');
}

/// Synthetisches Item fuer ein Lektions-Element der Session (Zeitbalken nutzt estSeconds).
Item teachItem(SessionLesson lesson) => Item(
      id: 'lesson-${lesson.id}',
      type: 'lesson',
      question: lesson.title,
      estSeconds: lesson.estSeconds,
    );

/// Element der Session als Anzeige-Aufgabe: Lektion nur bei `type == "lesson"` (Lektionskarte), sonst eine Aufgabe.
Task? taskOf(SessionEntry entry) {
  if (entry.lesson != null) {
    return TaskTeach(teachItem(entry.lesson!), entry.lesson!);
  }
  if (entry.item != null) return parseTask(entry.item!);
  return null;
}

/// Ablauf "Weiss nicht": neutral, zeigt nur die richtige Antwort, zaehlt nicht fuer die Serie-Anzeige.
String dontKnowOutcome() => 'dontknow';

/// Wo eine nachgereichte Lektion eingefuegt wird: direkt nach der aktuellen Aufgabe, nie doppelt.
int? lessonInsertIndex(int current, List<Task> tasks, String lessonId) {
  final already = tasks
      .skip(current + 1)
      .any((t) => t is TaskTeach && t.lesson.id == lessonId);
  return already ? null : current + 1;
}
