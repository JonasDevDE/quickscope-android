/// "Verstehen" ist sichtbar, sobald es Lektionen gibt (online aus der Planung, offline aus dem Offline-Paket); Audio spielt dabei keine Rolle.
bool understandVisible(bool hasLessons) => hasLessons;

/// Ton nachtraeglich: laeuft im Hintergrund der Preflight, wird der Audiomodus erst danach (und nur bei An, online, verfuegbar) eingeschaltet.
bool audioAfterCheck(bool enabled, bool online, bool available) =>
    enabled && online && available;

/// In "Verstehen" blaettert die Seite nach dem Ende des Streams von selbst weiter; ohne Ton bleibt sie lesbar mit "Weiter".
bool autoAdvance(bool lessonsOnly, bool finishedPlaying) =>
    lessonsOnly && finishedPlaying;
