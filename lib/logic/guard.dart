enum SessionPhase { closed, loading, running, finished, empty, error }

/// Eine Runde darf nur starten, wenn gerade keine laeuft oder laedt (Doppeltipp auf "Los" startet nicht zweimal).
bool canStartSession(SessionPhase phase) =>
    phase == SessionPhase.closed ||
    phase == SessionPhase.empty ||
    phase == SessionPhase.error;

/// Gleiches fuer das Hoerbuch (Phasen als Namen, damit die Logik ohne Android-Klassen testbar bleibt).
bool canStartBook(String phaseName) =>
    phaseName == 'Closed' ||
    phaseName == 'Empty' ||
    phaseName == 'Error' ||
    phaseName == 'Finished';

/// Mehrfach-Tap-Schutz: Klicks innerhalb von [windowMs] nach dem letzten angenommenen Klick werden verworfen.
bool acceptClick(int lastMs, int nowMs, [int windowMs = 500]) =>
    lastMs == 0 || nowMs - lastMs >= windowMs;
