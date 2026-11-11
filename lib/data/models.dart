/// Vertrag mit dem Backend: alle Datenmodelle für Quickscope.
library;

int _asInt(dynamic v, [int fallback = 0]) {
  if (v is int) return v;
  if (v is num) return v.toInt();
  if (v is String) return int.tryParse(v) ?? fallback;
  return fallback;
}

int? _asIntOrNull(dynamic v) {
  if (v == null) return null;
  if (v is int) return v;
  if (v is num) return v.toInt();
  if (v is String) return int.tryParse(v);
  return null;
}

double _asDouble(dynamic v, [double fallback = 0.0]) {
  if (v is double) return v;
  if (v is num) return v.toDouble();
  if (v is String) return double.tryParse(v) ?? fallback;
  return fallback;
}

double? _asDoubleOrNull(dynamic v) {
  if (v == null) return null;
  if (v is double) return v;
  if (v is num) return v.toDouble();
  if (v is String) return double.tryParse(v);
  return null;
}

String _asString(dynamic v, [String fallback = '']) {
  if (v is String) return v;
  if (v != null) return v.toString();
  return fallback;
}

String? _asStringOrNull(dynamic v) {
  if (v is String) return v;
  return null;
}

bool _asBool(dynamic v, [bool fallback = false]) {
  if (v is bool) return v;
  return fallback;
}

bool? _asBoolOrNull(dynamic v) {
  if (v is bool) return v;
  return null;
}

List<String> _asStringList(dynamic v) {
  if (v is List) {
    return v.whereType<String>().toList();
  }
  return const [];
}

List<String>? _asStringListOrNull(dynamic v) {
  if (v is List) {
    return v.whereType<String>().toList();
  }
  return null;
}

class Me {
  final String id;
  final String? email;
  final String? name;
  final String? pictureUrl;

  const Me({
    required this.id,
    this.email,
    this.name,
    this.pictureUrl,
  });

  factory Me.fromJson(Map<String, dynamic> json) => Me(
        id: _asString(json['id']),
        email: _asStringOrNull(json['email']),
        name: _asStringOrNull(json['name']),
        pictureUrl: _asStringOrNull(json['pictureUrl']),
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        if (email != null) 'email': email,
        if (name != null) 'name': name,
        if (pictureUrl != null) 'pictureUrl': pictureUrl,
      };
}

class SessionResponse {
  final String token;
  const SessionResponse({required this.token});

  factory SessionResponse.fromJson(Map<String, dynamic> json) =>
      SessionResponse(token: _asString(json['token']));
}

class AppRelease {
  final int versionCode;
  final String versionName;
  final String? notes;
  final int size;
  final String sha256;

  const AppRelease({
    required this.versionCode,
    required this.versionName,
    this.notes,
    this.size = 0,
    this.sha256 = '',
  });

  factory AppRelease.fromJson(Map<String, dynamic> json) => AppRelease(
        versionCode: _asInt(json['versionCode']),
        versionName: _asString(json['versionName']),
        notes: _asStringOrNull(json['notes']),
        size: _asInt(json['size']),
        sha256: _asString(json['sha256']),
      );
}

/// Prüfung. status: empty, processing, ready, failed oder over (Beginn vorbei).
class Exam {
  final String id;
  final String name;
  final String date;
  final String time;
  final String startsAt;
  final int hoursLeft;
  final String? description;
  final int dailyMinutes;
  final String priorKnowledge;
  final String status;
  final String? mode;
  final int topicCount;
  final int itemCount;
  final int materialCount;
  final String? createdAt;
  final int daysLeft;
  final String? error;
  final String kind;
  final String? stage;
  final int? progressPercent;
  final String? icon;

  const Exam({
    required this.id,
    this.name = '',
    this.date = '',
    this.time = '08:00',
    this.startsAt = '',
    this.hoursLeft = 0,
    this.description,
    this.dailyMinutes = 20,
    this.priorKnowledge = 'some',
    this.status = 'empty',
    this.mode,
    this.topicCount = 0,
    this.itemCount = 0,
    this.materialCount = 0,
    this.createdAt,
    this.daysLeft = 0,
    this.error,
    this.kind = 'exam',
    this.stage,
    this.progressPercent,
    this.icon,
  });

  factory Exam.fromJson(Map<String, dynamic> json) => Exam(
        id: _asString(json['id']),
        name: _asString(json['name']),
        date: _asString(json['date']),
        time: _asString(json['time'], '08:00'),
        startsAt: _asString(json['startsAt']),
        hoursLeft: _asInt(json['hoursLeft']),
        description: _asStringOrNull(json['description']),
        dailyMinutes: _asInt(json['dailyMinutes'], 20),
        priorKnowledge: _asString(json['priorKnowledge'], 'some'),
        status: _asString(json['status'], 'empty'),
        mode: _asStringOrNull(json['mode']),
        topicCount: _asInt(json['topicCount']),
        itemCount: _asInt(json['itemCount']),
        materialCount: _asInt(json['materialCount']),
        createdAt: _asStringOrNull(json['createdAt']),
        daysLeft: _asInt(json['daysLeft']),
        error: _asStringOrNull(json['error']),
        kind: _asString(json['kind'], 'exam'),
        stage: _asStringOrNull(json['stage']),
        progressPercent: _asIntOrNull(json['progressPercent']),
        icon: _asStringOrNull(json['icon']),
      );

  Exam copyWith({
    String? id,
    String? name,
    String? date,
    String? time,
    String? startsAt,
    int? hoursLeft,
    String? description,
    int? dailyMinutes,
    String? priorKnowledge,
    String? status,
    String? mode,
    int? topicCount,
    int? itemCount,
    int? materialCount,
    String? createdAt,
    int? daysLeft,
    String? error,
    String? kind,
    String? stage,
    int? progressPercent,
    String? icon,
  }) =>
      Exam(
        id: id ?? this.id,
        name: name ?? this.name,
        date: date ?? this.date,
        time: time ?? this.time,
        startsAt: startsAt ?? this.startsAt,
        hoursLeft: hoursLeft ?? this.hoursLeft,
        description: description ?? this.description,
        dailyMinutes: dailyMinutes ?? this.dailyMinutes,
        priorKnowledge: priorKnowledge ?? this.priorKnowledge,
        status: status ?? this.status,
        mode: mode ?? this.mode,
        topicCount: topicCount ?? this.topicCount,
        itemCount: itemCount ?? this.itemCount,
        materialCount: materialCount ?? this.materialCount,
        createdAt: createdAt ?? this.createdAt,
        daysLeft: daysLeft ?? this.daysLeft,
        error: error ?? this.error,
        kind: kind ?? this.kind,
        stage: stage ?? this.stage,
        progressPercent: progressPercent ?? this.progressPercent,
        icon: icon ?? this.icon,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'date': date,
        'time': time,
        'startsAt': startsAt,
        'hoursLeft': hoursLeft,
        if (description != null) 'description': description,
        'dailyMinutes': dailyMinutes,
        'priorKnowledge': priorKnowledge,
        'status': status,
        if (mode != null) 'mode': mode,
        'topicCount': topicCount,
        'itemCount': itemCount,
        'materialCount': materialCount,
        if (createdAt != null) 'createdAt': createdAt,
        'daysLeft': daysLeft,
        if (error != null) 'error': error,
        'kind': kind,
        if (stage != null) 'stage': stage,
        if (progressPercent != null) 'progressPercent': progressPercent,
        if (icon != null) 'icon': icon,
      };
}

class MaterialItem {
  final String id;
  final String examId;
  final String kind;
  final String role;
  final String name;
  final int sizeBytes;
  final String status;
  final String? error;
  final String? warning;
  final int? pages;
  final String? createdAt;

  const MaterialItem({
    required this.id,
    this.examId = '',
    this.kind = '',
    this.role = 'script',
    this.name = '',
    this.sizeBytes = 0,
    this.status = 'queued',
    this.error,
    this.warning,
    this.pages,
    this.createdAt,
  });

  factory MaterialItem.fromJson(Map<String, dynamic> json) => MaterialItem(
        id: _asString(json['id']),
        examId: _asString(json['examId']),
        kind: _asString(json['kind']),
        role: _asString(json['role'], 'script'),
        name: _asString(json['name']),
        sizeBytes: _asInt(json['sizeBytes']),
        status: _asString(json['status'], 'queued'),
        error: _asStringOrNull(json['error']),
        warning: _asStringOrNull(json['warning']),
        pages: _asIntOrNull(json['pages']),
        createdAt: _asStringOrNull(json['createdAt']),
      );
}

class Source {
  final String? materialId;
  final int? page;

  const Source({this.materialId, this.page});

  factory Source.fromJson(Map<String, dynamic> json) => Source(
        materialId: _asStringOrNull(json['materialId']),
        page: _asIntOrNull(json['page']),
      );

  Map<String, dynamic> toJson() => {
        if (materialId != null) 'materialId': materialId,
        if (page != null) 'page': page,
      };
}

class Item {
  final String id;
  final String examId;
  final String topicId;
  final String type;
  final String question;
  final String answer;
  final List<String>? options;
  final int? correctIndex;
  final Map<String, dynamic>? payload;
  final String? explanation;
  final String? mnemonic;
  final List<String>? solutionSteps;
  final String? trick;
  final int difficulty;
  final int estSeconds;
  final Source? source;
  final String? sourceType;
  final bool fromExam;
  final bool aiSolution;
  final String? patternId;
  final String? variantOf;
  final bool lesson;
  final String? ttsText;

  const Item({
    this.id = '',
    this.examId = '',
    this.topicId = '',
    this.type = 'card',
    this.question = '',
    this.answer = '',
    this.options,
    this.correctIndex,
    this.payload,
    this.explanation,
    this.mnemonic,
    this.solutionSteps,
    this.trick,
    this.difficulty = 3,
    this.estSeconds = 15,
    this.source,
    this.sourceType,
    this.fromExam = false,
    this.aiSolution = false,
    this.patternId,
    this.variantOf,
    this.lesson = false,
    this.ttsText,
  });

  factory Item.fromJson(Map<String, dynamic> json) => Item(
        id: _asString(json['id']),
        examId: _asString(json['examId']),
        topicId: _asString(json['topicId']),
        type: _asString(json['type'], 'card'),
        question: _asString(json['question']),
        answer: _asString(json['answer']),
        options: _asStringListOrNull(json['options']),
        correctIndex: _asIntOrNull(json['correctIndex']),
        payload: json['payload'] is Map<String, dynamic>
            ? json['payload'] as Map<String, dynamic>
            : (json['payload'] is Map
                ? Map<String, dynamic>.from(json['payload'] as Map)
                : null),
        explanation: _asStringOrNull(json['explanation']),
        mnemonic: _asStringOrNull(json['mnemonic']),
        solutionSteps: _asStringListOrNull(json['solutionSteps']),
        trick: _asStringOrNull(json['trick']),
        difficulty: _asInt(json['difficulty'], 3),
        estSeconds: _asInt(json['estSeconds'], 15),
        source: json['source'] is Map
            ? Source.fromJson(Map<String, dynamic>.from(json['source'] as Map))
            : null,
        sourceType: _asStringOrNull(json['sourceType']),
        fromExam: _asBool(json['fromExam']),
        aiSolution: _asBool(json['aiSolution']),
        patternId: _asStringOrNull(json['patternId']),
        variantOf: _asStringOrNull(json['variantOf']),
        lesson: _asBool(json['lesson']),
        ttsText: _asStringOrNull(json['ttsText']),
      );
}

class WeakTopic {
  final String topicId;
  final String title;
  final double mastery;

  const WeakTopic({
    this.topicId = '',
    this.title = '',
    this.mastery = 0.0,
  });

  factory WeakTopic.fromJson(Map<String, dynamic> json) => WeakTopic(
        topicId: _asString(json['topicId']),
        title: _asString(json['title']),
        mastery: _asDouble(json['mastery']),
      );

  Map<String, dynamic> toJson() => {
        'topicId': topicId,
        'title': title,
        'mastery': mastery,
      };
}

class Plan {
  final List<String> todayChapters;
  final int chaptersTotal;
  final int chaptersHeard;
  final int? plannedMinutes;

  const Plan({
    this.todayChapters = const [],
    this.chaptersTotal = 0,
    this.chaptersHeard = 0,
    this.plannedMinutes,
  });

  factory Plan.fromJson(Map<String, dynamic> json) => Plan(
        todayChapters: _asStringList(json['todayChapters']),
        chaptersTotal: _asInt(json['chaptersTotal']),
        chaptersHeard: _asInt(json['chaptersHeard']),
        plannedMinutes: _asIntOrNull(json['plannedMinutes']),
      );

  Map<String, dynamic> toJson() => {
        'todayChapters': todayChapters,
        'chaptersTotal': chaptersTotal,
        'chaptersHeard': chaptersHeard,
        if (plannedMinutes != null) 'plannedMinutes': plannedMinutes,
      };
}

class Projection {
  final int minutes;
  final int scoreAtExamPercent;

  const Projection({this.minutes = 0, this.scoreAtExamPercent = 0});

  factory Projection.fromJson(Map<String, dynamic> json) => Projection(
        minutes: _asInt(json['minutes']),
        scoreAtExamPercent: _asInt(json['scoreAtExamPercent']),
      );

  Map<String, dynamic> toJson() => {
        'minutes': minutes,
        'scoreAtExamPercent': scoreAtExamPercent,
      };
}

class Milestone {
  final String date;
  final int coveragePercent;
  final int scorePercent;

  const Milestone({
    this.date = '',
    this.coveragePercent = 0,
    this.scorePercent = 0,
  });

  factory Milestone.fromJson(Map<String, dynamic> json) => Milestone(
        date: _asString(json['date']),
        coveragePercent: _asInt(json['coveragePercent']),
        scorePercent: _asInt(json['scorePercent']),
      );

  Map<String, dynamic> toJson() => {
        'date': date,
        'coveragePercent': coveragePercent,
        'scorePercent': scorePercent,
      };
}

/// Zeitbedarf bis zur Pruefung (Forecast.time); alle Felder tolerant/optional.
class TimeNeed {
  final int? needSeconds;
  final int? remainingTimeToExamSeconds;
  final int? recommendedMinutesToday;
  final int? coveragePercentAtBudget;
  final bool tight;

  const TimeNeed({
    this.needSeconds,
    this.remainingTimeToExamSeconds,
    this.recommendedMinutesToday,
    this.coveragePercentAtBudget,
    this.tight = false,
  });

  factory TimeNeed.fromJson(Map<String, dynamic> json) => TimeNeed(
        needSeconds: _asIntOrNull(json['needSeconds']),
        remainingTimeToExamSeconds:
            _asIntOrNull(json['remainingTimeToExamSeconds']),
        recommendedMinutesToday: _asIntOrNull(json['recommendedMinutesToday']),
        coveragePercentAtBudget: _asIntOrNull(json['coveragePercentAtBudget']),
        tight: _asBool(json['tight']),
      );

  Map<String, dynamic> toJson() => {
        if (needSeconds != null) 'needSeconds': needSeconds,
        if (remainingTimeToExamSeconds != null)
          'remainingTimeToExamSeconds': remainingTimeToExamSeconds,
        if (recommendedMinutesToday != null)
          'recommendedMinutesToday': recommendedMinutesToday,
        if (coveragePercentAtBudget != null)
          'coveragePercentAtBudget': coveragePercentAtBudget,
        'tight': tight,
      };
}

class Forecast {
  final int scoreNowPercent;
  final int scoreAtExamPercent;
  final int daysLeft;
  final int hoursLeft;
  final int doneSecondsToday;
  final List<WeakTopic> weakTopics;
  final String confidence;
  final String? mode;
  final int coveragePercent;
  final List<Projection> projections;
  final double? expectedGainPercentThisSession;
  final List<Milestone> milestones;
  final bool showCheatsheetSuggestion;
  final int? streakDays;
  final int? bestStreakDays;
  final Plan? plan;
  final String note;
  final int? knowledgePercent;
  final int? recallPercent;
  final TimeNeed? time;

  const Forecast({
    this.scoreNowPercent = 0,
    this.scoreAtExamPercent = 0,
    this.daysLeft = 0,
    this.hoursLeft = 0,
    this.doneSecondsToday = 0,
    this.weakTopics = const [],
    this.confidence = 'low',
    this.mode,
    this.coveragePercent = 0,
    this.projections = const [],
    this.expectedGainPercentThisSession,
    this.milestones = const [],
    this.showCheatsheetSuggestion = false,
    this.streakDays,
    this.bestStreakDays,
    this.plan,
    this.note = 'Schätzung, keine Garantie',
    this.knowledgePercent,
    this.recallPercent,
    this.time,
  });

  factory Forecast.fromJson(Map<String, dynamic> json) => Forecast(
        scoreNowPercent: _asInt(json['scoreNowPercent']),
        scoreAtExamPercent: _asInt(json['scoreAtExamPercent']),
        daysLeft: _asInt(json['daysLeft']),
        hoursLeft: _asInt(json['hoursLeft']),
        doneSecondsToday: _asInt(json['doneSecondsToday']),
        weakTopics: (json['weakTopics'] is List)
            ? (json['weakTopics'] as List)
                .whereType<Map>()
                .map((e) => WeakTopic.fromJson(Map<String, dynamic>.from(e)))
                .toList()
            : const [],
        confidence: _asString(json['confidence'], 'low'),
        mode: _asStringOrNull(json['mode']),
        coveragePercent: _asInt(json['coveragePercent']),
        projections: (json['projections'] is List)
            ? (json['projections'] as List)
                .whereType<Map>()
                .map((e) => Projection.fromJson(Map<String, dynamic>.from(e)))
                .toList()
            : const [],
        expectedGainPercentThisSession:
            _asDoubleOrNull(json['expectedGainPercentThisSession']),
        milestones: (json['milestones'] is List)
            ? (json['milestones'] as List)
                .whereType<Map>()
                .map((e) => Milestone.fromJson(Map<String, dynamic>.from(e)))
                .toList()
            : const [],
        showCheatsheetSuggestion: _asBool(json['showCheatsheetSuggestion']),
        streakDays: _asIntOrNull(json['streakDays']),
        bestStreakDays: _asIntOrNull(json['bestStreakDays']),
        plan: json['plan'] is Map
            ? Plan.fromJson(Map<String, dynamic>.from(json['plan'] as Map))
            : null,
        note: _asString(json['note'], 'Schätzung, keine Garantie'),
        knowledgePercent: _asIntOrNull(json['knowledgePercent']),
        recallPercent: _asIntOrNull(json['recallPercent']),
        time: json['time'] is Map
            ? TimeNeed.fromJson(Map<String, dynamic>.from(json['time'] as Map))
            : null,
      );

  Map<String, dynamic> toJson() => {
        'scoreNowPercent': scoreNowPercent,
        'scoreAtExamPercent': scoreAtExamPercent,
        'daysLeft': daysLeft,
        'hoursLeft': hoursLeft,
        'doneSecondsToday': doneSecondsToday,
        'weakTopics': weakTopics.map((e) => e.toJson()).toList(),
        'confidence': confidence,
        if (mode != null) 'mode': mode,
        'coveragePercent': coveragePercent,
        'projections': projections.map((e) => e.toJson()).toList(),
        if (expectedGainPercentThisSession != null)
          'expectedGainPercentThisSession': expectedGainPercentThisSession,
        'milestones': milestones.map((e) => e.toJson()).toList(),
        'showCheatsheetSuggestion': showCheatsheetSuggestion,
        if (streakDays != null) 'streakDays': streakDays,
        if (bestStreakDays != null) 'bestStreakDays': bestStreakDays,
        if (plan != null) 'plan': plan!.toJson(),
        'note': note,
        if (knowledgePercent != null) 'knowledgePercent': knowledgePercent,
        if (recallPercent != null) 'recallPercent': recallPercent,
        if (time != null) 'time': time!.toJson(),
      };
}

class SessionLesson {
  final String id;
  final String title;
  final List<String> keyPoints;
  final String ttsText;
  final String? mnemonic;
  final int estSeconds;
  final String? chapterId;
  final String? chapterTitle;
  final int? chapterOrder;
  final int? chapterCount;
  final int? lessonIndexInChapter;
  final int? lessonCountInChapter;

  const SessionLesson({
    required this.id,
    this.title = '',
    this.keyPoints = const [],
    this.ttsText = '',
    this.mnemonic,
    this.estSeconds = 60,
    this.chapterId,
    this.chapterTitle,
    this.chapterOrder,
    this.chapterCount,
    this.lessonIndexInChapter,
    this.lessonCountInChapter,
  });

  factory SessionLesson.fromJson(Map<String, dynamic> json) => SessionLesson(
        id: _asString(json['id']),
        title: _asString(json['title']),
        keyPoints: _asStringList(json['keyPoints']),
        ttsText: _asString(json['ttsText']),
        mnemonic: _asStringOrNull(json['mnemonic']),
        estSeconds: _asInt(json['estSeconds'], 60),
        chapterId: _asStringOrNull(json['chapterId']),
        chapterTitle: _asStringOrNull(json['chapterTitle']),
        chapterOrder: _asIntOrNull(json['chapterOrder']),
        chapterCount: _asIntOrNull(json['chapterCount']),
        lessonIndexInChapter: _asIntOrNull(json['lessonIndexInChapter']),
        lessonCountInChapter: _asIntOrNull(json['lessonCountInChapter']),
      );

  SessionLesson copyWith({
    String? id,
    String? title,
    List<String>? keyPoints,
    String? ttsText,
    String? mnemonic,
    int? estSeconds,
    String? chapterId,
    String? chapterTitle,
    int? chapterOrder,
    int? chapterCount,
    int? lessonIndexInChapter,
    int? lessonCountInChapter,
  }) =>
      SessionLesson(
        id: id ?? this.id,
        title: title ?? this.title,
        keyPoints: keyPoints ?? this.keyPoints,
        ttsText: ttsText ?? this.ttsText,
        mnemonic: mnemonic ?? this.mnemonic,
        estSeconds: estSeconds ?? this.estSeconds,
        chapterId: chapterId ?? this.chapterId,
        chapterTitle: chapterTitle ?? this.chapterTitle,
        chapterOrder: chapterOrder ?? this.chapterOrder,
        chapterCount: chapterCount ?? this.chapterCount,
        lessonIndexInChapter: lessonIndexInChapter ?? this.lessonIndexInChapter,
        lessonCountInChapter: lessonCountInChapter ?? this.lessonCountInChapter,
      );
}

class SessionEntry {
  final Item? item;
  final SessionLesson? lesson;

  const SessionEntry({this.item, this.lesson});

  factory SessionEntry.fromJson(Map<String, dynamic> json) {
    final type = _asStringOrNull(json['type']);
    if (type == 'lesson') {
      final l = json['lesson'];
      if (l is Map) {
        return SessionEntry(
          lesson: SessionLesson.fromJson(Map<String, dynamic>.from(l)),
        );
      }
      return const SessionEntry();
    } else {
      return SessionEntry(item: Item.fromJson(json));
    }
  }
}

class Budget {
  final int dailyMinutes;
  final int usedMinutesToday;
  final int remainingMinutes;

  const Budget({
    this.dailyMinutes = 0,
    this.usedMinutesToday = 0,
    this.remainingMinutes = 0,
  });

  factory Budget.fromJson(Map<String, dynamic> json) => Budget(
        dailyMinutes: _asInt(json['dailyMinutes']),
        usedMinutesToday: _asInt(json['usedMinutesToday']),
        remainingMinutes: _asInt(json['remainingMinutes']),
      );
}

class LearnSession {
  final List<SessionEntry> items;
  final int plannedSeconds;
  final String kind;
  final String reason;
  final Forecast? forecast;
  final bool showCheatsheetSuggestion;
  final int? finalRoundStart;
  final Budget? budget;
  final int? topicsCovered;

  const LearnSession({
    this.items = const [],
    this.plannedSeconds = 0,
    this.kind = 'daily',
    this.reason = '',
    this.forecast,
    this.showCheatsheetSuggestion = false,
    this.finalRoundStart,
    this.budget,
    this.topicsCovered,
  });

  List<Item> get quiz => items.map((e) => e.item).whereType<Item>().toList();

  factory LearnSession.fromJson(Map<String, dynamic> json) => LearnSession(
        items: (json['items'] is List)
            ? (json['items'] as List)
                .whereType<Map>()
                .map((e) => SessionEntry.fromJson(Map<String, dynamic>.from(e)))
                .toList()
            : const [],
        plannedSeconds: _asInt(json['plannedSeconds']),
        kind: _asString(json['kind'], 'daily'),
        reason: _asString(json['reason']),
        forecast: json['forecast'] is Map
            ? Forecast.fromJson(Map<String, dynamic>.from(json['forecast'] as Map))
            : null,
        showCheatsheetSuggestion: _asBool(json['showCheatsheetSuggestion']),
        finalRoundStart: _asIntOrNull(json['finalRoundStart']),
        budget: json['budget'] is Map
            ? Budget.fromJson(Map<String, dynamic>.from(json['budget'] as Map))
            : null,
        topicsCovered: _asIntOrNull(json['topicsCovered']),
      );
}

class Lesson {
  final String id;
  final String examId;
  final String topicId;
  final String chapterId;
  final String title;
  final List<String> keyPoints;
  final String ttsText;
  final String? mnemonic;
  final int estSeconds;
  final String? sourceType;
  final Source? source;
  final bool heard;
  final String? heardAt;

  const Lesson({
    required this.id,
    this.examId = '',
    this.topicId = '',
    this.chapterId = '',
    this.title = '',
    this.keyPoints = const [],
    this.ttsText = '',
    this.mnemonic,
    this.estSeconds = 60,
    this.sourceType,
    this.source,
    this.heard = false,
    this.heardAt,
  });

  factory Lesson.fromJson(Map<String, dynamic> json) => Lesson(
        id: _asString(json['id']),
        examId: _asString(json['examId']),
        topicId: _asString(json['topicId']),
        chapterId: _asString(json['chapterId']),
        title: _asString(json['title']),
        keyPoints: _asStringList(json['keyPoints']),
        ttsText: _asString(json['ttsText']),
        mnemonic: _asStringOrNull(json['mnemonic']),
        estSeconds: _asInt(json['estSeconds'], 60),
        sourceType: _asStringOrNull(json['sourceType']),
        source: json['source'] is Map
            ? Source.fromJson(Map<String, dynamic>.from(json['source'] as Map))
            : null,
        heard: _asBool(json['heard']),
        heardAt: _asStringOrNull(json['heardAt']),
      );
}

class Chapter {
  final String chapterId;
  final String title;
  final int order;
  final int lessonCount;
  final int minutes;
  final String status;
  final double mastery;

  const Chapter({
    required this.chapterId,
    this.title = '',
    this.order = 0,
    this.lessonCount = 0,
    this.minutes = 0,
    this.status = 'new',
    this.mastery = 0.0,
  });

  factory Chapter.fromJson(Map<String, dynamic> json) => Chapter(
        chapterId: _asString(json['chapterId']),
        title: _asString(json['title']),
        order: _asInt(json['order']),
        lessonCount: _asInt(json['lessonCount']),
        minutes: _asInt(json['minutes']),
        status: _asString(json['status'], 'new'),
        mastery: _asDouble(json['mastery']),
      );

  Map<String, dynamic> toJson() => {
        'chapterId': chapterId,
        'title': title,
        'order': order,
        'lessonCount': lessonCount,
        'minutes': minutes,
        'status': status,
        'mastery': mastery,
      };
}

class AudiobookItem {
  final String lessonId;
  final String chapterId;
  final String chapterTitle;
  final String title;
  final int estSeconds;

  const AudiobookItem({
    required this.lessonId,
    this.chapterId = '',
    this.chapterTitle = '',
    this.title = '',
    this.estSeconds = 60,
  });

  factory AudiobookItem.fromJson(Map<String, dynamic> json) => AudiobookItem(
        lessonId: _asString(json['lessonId']),
        chapterId: _asString(json['chapterId']),
        chapterTitle: _asString(json['chapterTitle']),
        title: _asString(json['title']),
        estSeconds: _asInt(json['estSeconds'], 60),
      );
}

class Audiobook {
  final List<AudiobookItem> items;
  final int totalSeconds;
  final String reason;
  final Budget? budget;

  const Audiobook({
    this.items = const [],
    this.totalSeconds = 0,
    this.reason = '',
    this.budget,
  });

  factory Audiobook.fromJson(Map<String, dynamic> json) => Audiobook(
        items: (json['items'] is List)
            ? (json['items'] as List)
                .whereType<Map>()
                .map((e) => AudiobookItem.fromJson(Map<String, dynamic>.from(e)))
                .toList()
            : const [],
        totalSeconds: _asInt(json['totalSeconds']),
        reason: _asString(json['reason']),
        budget: json['budget'] is Map
            ? Budget.fromJson(Map<String, dynamic>.from(json['budget'] as Map))
            : null,
      );
}

class HeardRequest {
  final double fraction;
  const HeardRequest({required this.fraction});

  Map<String, dynamic> toJson() => {'fraction': fraction};
}

class AttemptRequest {
  final String itemId;
  final String outcome;
  final int ms;
  final String? at;
  final String? clientId;
  final Object? answer;

  const AttemptRequest({
    required this.itemId,
    required this.outcome,
    required this.ms,
    this.at,
    this.clientId,
    this.answer,
  });

  AttemptRequest copyWith({
    String? itemId,
    String? outcome,
    int? ms,
    String? at,
    String? clientId,
    Object? answer,
  }) =>
      AttemptRequest(
        itemId: itemId ?? this.itemId,
        outcome: outcome ?? this.outcome,
        ms: ms ?? this.ms,
        at: at ?? this.at,
        clientId: clientId ?? this.clientId,
        answer: answer ?? this.answer,
      );

  factory AttemptRequest.fromJson(Map<String, dynamic> json) => AttemptRequest(
        itemId: _asString(json['itemId']),
        outcome: _asString(json['outcome']),
        ms: _asInt(json['ms']),
        at: _asStringOrNull(json['at']),
        clientId: _asStringOrNull(json['clientId']),
        answer: json['answer'],
      );

  Map<String, dynamic> toJson() => {
        'itemId': itemId,
        'outcome': outcome,
        'ms': ms,
        if (at != null) 'at': at,
        if (clientId != null) 'clientId': clientId,
        if (answer != null) 'answer': answer,
      };
}

class AttemptsResponse {
  final Forecast forecast;
  final int doneSecondsToday;
  final String? nextLessonId;

  const AttemptsResponse({
    required this.forecast,
    this.doneSecondsToday = 0,
    this.nextLessonId,
  });

  factory AttemptsResponse.fromJson(Map<String, dynamic> json) =>
      AttemptsResponse(
        forecast: json['forecast'] is Map
            ? Forecast.fromJson(Map<String, dynamic>.from(json['forecast'] as Map))
            : const Forecast(),
        doneSecondsToday: _asInt(json['doneSecondsToday']),
        nextLessonId: _asStringOrNull(json['nextLessonId']),
      );
}

class Bullet {
  final String topicId;
  final String topic;
  final String text;

  const Bullet({
    this.topicId = '',
    this.topic = '',
    this.text = '',
  });

  factory Bullet.fromJson(Map<String, dynamic> json) => Bullet(
        topicId: _asString(json['topicId']),
        topic: _asString(json['topic']),
        text: _asString(json['text']),
      );
}

class CheatSheet {
  final int minutes;
  final int words;
  final List<Bullet> bullets;
  final String note;

  const CheatSheet({
    this.minutes = 5,
    this.words = 0,
    this.bullets = const [],
    this.note = '',
  });

  factory CheatSheet.fromJson(Map<String, dynamic> json) => CheatSheet(
        minutes: _asInt(json['minutes'], 5),
        words: _asInt(json['words']),
        bullets: (json['bullets'] is List)
            ? (json['bullets'] as List)
                .whereType<Map>()
                .map((e) => Bullet.fromJson(Map<String, dynamic>.from(e)))
                .toList()
            : const [],
        note: _asString(json['note']),
      );
}

class Topic {
  final String id;
  final String examId;
  final String title;
  final String? oneLiner;
  final double weight;
  final int order;
  final String? parentId;
  final int level;
  final double mastery;

  const Topic({
    required this.id,
    this.examId = '',
    this.title = '',
    this.oneLiner,
    this.weight = 0.0,
    this.order = 0,
    this.parentId,
    this.level = 0,
    this.mastery = 0.0,
  });

  factory Topic.fromJson(Map<String, dynamic> json) => Topic(
        id: _asString(json['id']),
        examId: _asString(json['examId']),
        title: _asString(json['title']),
        oneLiner: _asStringOrNull(json['oneLiner']),
        weight: _asDouble(json['weight']),
        order: _asInt(json['order']),
        parentId: _asStringOrNull(json['parentId']),
        level: _asInt(json['level']),
        mastery: _asDouble(json['mastery']),
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'examId': examId,
        'title': title,
        if (oneLiner != null) 'oneLiner': oneLiner,
        'weight': weight,
        'order': order,
        if (parentId != null) 'parentId': parentId,
        'level': level,
        'mastery': mastery,
      };
}

class ExamCreate {
  final String name;
  final String? date;
  final String? time;
  final String? mode;
  final String? description;
  final int? dailyMinutes;
  final String? priorKnowledge;

  const ExamCreate({
    required this.name,
    this.date,
    this.time,
    this.mode,
    this.description,
    this.dailyMinutes,
    this.priorKnowledge,
  });

  Map<String, dynamic> toJson() => {
        'name': name,
        if (date != null) 'date': date,
        if (time != null) 'time': time,
        if (mode != null) 'mode': mode,
        if (description != null) 'description': description,
        if (dailyMinutes != null) 'dailyMinutes': dailyMinutes,
        if (priorKnowledge != null) 'priorKnowledge': priorKnowledge,
      };
}

class ExamUpdate {
  final String? name;
  final String? mode;
  final String? date;
  final String? time;
  final String? description;
  final int? dailyMinutes;
  final String? priorKnowledge;

  const ExamUpdate({
    this.name,
    this.mode,
    this.date,
    this.time,
    this.description,
    this.dailyMinutes,
    this.priorKnowledge,
  });

  Map<String, dynamic> toJson() => {
        if (name != null) 'name': name,
        if (mode != null) 'mode': mode,
        if (date != null) 'date': date,
        if (time != null) 'time': time,
        if (description != null) 'description': description,
        if (dailyMinutes != null) 'dailyMinutes': dailyMinutes,
        if (priorKnowledge != null) 'priorKnowledge': priorKnowledge,
      };
}

class WordTiming {
  final int start;
  final int end;
  final int fromMs;
  final int toMs;

  const WordTiming({
    this.start = 0,
    this.end = 0,
    this.fromMs = 0,
    this.toMs = 0,
  });

  factory WordTiming.fromJson(Map<String, dynamic> json) => WordTiming(
        start: _asInt(json['start']),
        end: _asInt(json['end']),
        fromMs: _asInt(json['fromMs']),
        toMs: _asInt(json['toMs']),
      );

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is WordTiming &&
          start == other.start &&
          end == other.end &&
          fromMs == other.fromMs &&
          toMs == other.toMs;

  @override
  int get hashCode => Object.hash(start, end, fromMs, toMs);
}

class AudioTimings {
  final String text;
  final int durationMs;
  final List<WordTiming> words;

  const AudioTimings({
    this.text = '',
    this.durationMs = 0,
    this.words = const [],
  });

  factory AudioTimings.fromJson(Map<String, dynamic> json) => AudioTimings(
        text: _asString(json['text']),
        durationMs: _asInt(json['durationMs']),
        words: (json['words'] is List)
            ? (json['words'] as List)
                .whereType<Map>()
                .map((e) => WordTiming.fromJson(Map<String, dynamic>.from(e)))
                .toList()
            : const [],
      );
}

class Profile {
  final String about;
  final int? age;
  final String? educationLevel;
  final bool onboardingDone;
  final bool? audioEnabled;

  const Profile({
    this.about = '',
    this.age,
    this.educationLevel,
    this.onboardingDone = false,
    this.audioEnabled,
  });

  factory Profile.fromJson(Map<String, dynamic> json) => Profile(
        about: _asString(json['about']),
        age: _asIntOrNull(json['age']),
        educationLevel: _asStringOrNull(json['educationLevel']),
        onboardingDone: _asBool(json['onboardingDone']),
        audioEnabled: _asBoolOrNull(json['audioEnabled']),
      );

  Map<String, dynamic> toJson() => {
        'about': about,
        if (age != null) 'age': age,
        if (educationLevel != null) 'educationLevel': educationLevel,
        'onboardingDone': onboardingDone,
        if (audioEnabled != null) 'audioEnabled': audioEnabled,
      };

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is Profile &&
          about == other.about &&
          age == other.age &&
          educationLevel == other.educationLevel &&
          onboardingDone == other.onboardingDone &&
          audioEnabled == other.audioEnabled;

  @override
  int get hashCode =>
      Object.hash(about, age, educationLevel, onboardingDone, audioEnabled);
}

class ProfileUpdate {
  final String? about;
  final int? age;
  final String? educationLevel;
  final bool? onboardingDone;
  final bool? audioEnabled;

  const ProfileUpdate({
    this.about,
    this.age,
    this.educationLevel,
    this.onboardingDone,
    this.audioEnabled,
  });

  Map<String, dynamic> toJson() => {
        if (about != null) 'about': about,
        if (age != null) 'age': age,
        if (educationLevel != null) 'educationLevel': educationLevel,
        if (onboardingDone != null) 'onboardingDone': onboardingDone,
        if (audioEnabled != null) 'audioEnabled': audioEnabled,
      };
}

class VoiceSummary {
  final String about;
  const VoiceSummary({this.about = ''});

  factory VoiceSummary.fromJson(Map<String, dynamic> json) =>
      VoiceSummary(about: _asString(json['about']));
}

class AudioStatus {
  final bool available;
  final int? latencyMs;
  final String? reason;
  final int? retryAfterSeconds;

  const AudioStatus({
    this.available = true,
    this.latencyMs,
    this.reason,
    this.retryAfterSeconds,
  });

  factory AudioStatus.fromJson(Map<String, dynamic> json) => AudioStatus(
        available: _asBool(json['available'], true),
        latencyMs: _asIntOrNull(json['latencyMs']),
        reason: _asStringOrNull(json['reason']),
        retryAfterSeconds: _asIntOrNull(json['retryAfterSeconds']),
      );
}

class ClientLog {
  final String kind;
  final String code;
  final String detail;
  final String appVersion;
  final String itemType;

  const ClientLog({
    required this.kind,
    required this.code,
    required this.detail,
    required this.appVersion,
    required this.itemType,
  });

  Map<String, dynamic> toJson() => {
        'kind': kind,
        'code': code,
        'detail': detail,
        'appVersion': appVersion,
        'itemType': itemType,
      };
}
