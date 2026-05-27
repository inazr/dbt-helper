# Design: "Status" als dritter Node-Farbmodus in der Lineage

**Datum:** 2026-05-27
**Branch:** feature/shared-action-bar (bzw. neuer Feature-Branch)

## Ziel

Wenn dbt über den Runner läuft, sollen die Nodes in der Lineage ihren
Run-Status als Farbe anzeigen können. Dazu kommt eine dritte Option im
"Node color"-Combo: **Status**. Der Lebenszyklus einer Node-Karte ist:

```
queued → running → success | warn | error | skipped
```

- **queued** (Blau `#3F7BD9`) — beim Drücken von GO werden alle baubaren
  Nodes des aktuellen Graphen blau.
- **running** (Cyan `#5AC8FA`, pulsierend) — sobald dbt `START` für die Node meldet.
- **success** (Grün `#3FB950`) — erfolgreich gebaut.
- **warn** (Gelb `#E3B341`) — mit Warnung.
- **error** (Rot `#F85149`) — mit Fehler.
- **skipped** (Hellgrau `#C9CED6`) — übersprungen.

## Harte Randbedingungen

- **Die Runner-Ausgabe darf sich NICHT ändern.** Wir hängen uns passiv an den
  bestehenden `onLine`-Stream, kein zweiter dbt-Prozess, kein zusätzliches Flag.
- **Kein vollständiges Re-Rendern des Graphen, kein Verlust von Fokus/Viewport.**
  Status-Updates färben einzelne HTML-Overlay-Karten direkt um.
- Aktivierung des Status-Modus erfolgt **manuell** über das Combo (kein
  Auto-Umschalten beim Lauf).

## Mechanismus (gewählt)

**Option 1 (Stdout-Text-Parsing) für die Live-Progression + `run_results.json`
am Run-Ende als autoritative Korrektur.**

- Stdout liefert das Live-Gefühl (queued → running → terminal).
- `run_results.json` (mit `unique_id` + `status`) korrigiert am Ende alle
  Live-Mapping-Fehler, sodass der Endzustand immer korrekt ist.

## Architektur

Drei bewegliche Teile:

1. **JS-Status-Store** (`lineage.js`) — `nodeStatus: { uniqueId → status }` als
   Modul-State. Überlebt `renderGraph()`-Neuaufbauten und löst damit das
   Post-Run-Reparse-Problem (s. u.).
2. **Kotlin-Parser** (`DbtRunStatusParser`) — stateless, wandelt eine
   dbt-Logzeile in `(relationKey, status)`.
3. **Wiring** (`DbtMainPanel` → `LineageTab`) — speist den vorhandenen
   `onLine`-Stream zusätzlich in den Parser und pusht Updates über die
   JS-Bridge ins Web-View.

### Post-Run-Reparse-Problem

`DbtCommandRunner.run()` ruft nach erfolgreichem `RUN`/`BUILD`
`ManifestService.reparse()` (`DbtCommandRunner.kt:96`). Das feuert
`onManifestUpdated` → `LineageTab.refreshGraph()` → `renderGraph()`, also einen
**vollständigen Kartenneuaufbau**. Lösung: Der Status lebt im JS-Modul-State
(`nodeStatus`), und `pickBarColor(node, 'status')` liest daraus. Da
`buildNodeCards()` ohnehin `pickBarColor(node, graph.nodeColorMode)` aufruft,
bleiben die Farben nach dem Re-Render automatisch erhalten.

## JS-Seite (`lineage.js`)

- Neue Palette:
  ```js
  const STATUS_BAR_COLORS = {
      queued:  '#3F7BD9',
      running: '#5AC8FA',
      success: '#3FB950',
      warn:    '#E3B341',
      error:   '#F85149',
      skipped: '#C9CED6'
  };
  ```
- `pickBarColor(node, colorMode)`: bei `colorMode === 'status'` Farbe aus
  `STATUS_BAR_COLORS[nodeStatus[node.id]]`, sonst neutrales Grau
  (`NEUTRAL_BAR_COLOR`).
- `currentColorMode` wird in `renderGraph` aus `graph.nodeColorMode` gemerkt.
- **`window.setNodeStatuses(jsonStr)`** — merged in `nodeStatus`. Nur wenn
  `currentColorMode === 'status'`: betroffene Karten direkt über
  `--card-bar-color` umfärben (kein Re-Render). Bei Status `running` die
  CSS-Klasse `running` auf die Karte setzen, bei jedem anderen Status entfernen.
- **`window.clearNodeStatuses()`** — leert `nodeStatus`, färbt im Status-Modus
  auf Neutral zurück und entfernt alle `running`-Klassen.
- **Puls-Animation:** CSS `@keyframes` auf `.card-node.running .card-bar`
  (z. B. `opacity`/`box-shadow`). Rein CSS auf dem HTML-Overlay, kein
  Cytoscape-Re-Render. Endet automatisch, sobald die Karte einen
  Terminal-Status erhält (Klasse entfernt).

## Settings

- `DbtHelperSettings.State.nodeColorMode` bekommt den dritten gültigen Wert
  `"status"`. Default bleibt `"resource"` → keine Migration nötig.
- `DbtHelperConfigurable`: Combo "Node color" erhält dritten Eintrag
  **"Status"**. Bind-Logik wird von zwei auf drei Werte erweitert:
  `"Resource type" ↔ resource`, `"Schema name" ↔ schema`, `"Status" ↔ status`.

## Status-Pipeline (Kotlin)

### `DbtRunStatusParser` (neu, stateless)

- Strippt ANSI-Sequenzen (gleiches Muster wie `DbtRunnerTab.kt:50`).
- Regex auf dbt-Fortschrittszeilen. Erkannte Phasen → Status:
  - `START` → `running`
  - `OK` / `SUCCESS` / `PASS` → `success`
  - `WARN` → `warn`
  - `ERROR` / `FAIL` → `error`
  - `SKIP` → `skipped`
- Extrahiert `relationKey = "schema.identifier"` aus der Zeile
  (Format z. B. `... model analytics.dim_customers ...`).
- Rückgabe: `NodeStatusUpdate(relationKey, status)` oder `null`.

### Matching-Index (in `LineageTab`, aus `ManifestIndex`)

- `"schema.(alias ?: name)".lowercase() → uniqueId` für alle baubaren Nodes.
- Fallback-Variante `"database.schema.identifier".lowercase() → uniqueId`, falls
  dbt die Datenbank mitloggt.
- Reuse von `relationMap` ist nicht direkt möglich, da dieser auf dem vollen
  `relationName` (`db.schema.tbl`) keyt, während dbt-Logzeilen
  `schema.identifier` drucken (`ManifestService.kt:88`, `DbtNode.kt:23`).

## Datenfluss

1. **GO** (Verb `RUN` / `BUILD` / `TEST`) → `DbtMainPanel` ruft
   `lineageTab.beginRunStatus()`:
   - `clearNodeStatuses()`
   - Alle baubaren Karten-Nodes (Models/Seeds/Snapshots; keine Sources,
     Exposures, Stubs, Tests) des aktuellen Graphen auf `queued`.
   - Dazu merkt sich `LineageTab` die Node-IDs des zuletzt gebauten Graphen
     (`lastGraphNodeIds`), gefüllt in `refreshGraph()`.
2. **Pro Logzeile** → `onLine` füttert `DbtRunStatusParser`. Treffer wird via
   `relationKey → uniqueId` aufgelöst und (gebatcht) an
   `lineageTab.pushNodeStatus(uniqueId, status)` übergeben. Marshalling auf den
   EDT vor `executeJs` (analog zu bestehendem `pushDocsToSidebar`).
3. **Run-Ende** (`onFinished`) → `target/run_results.json` lesen und einen
   finalen Voll-Reconcile an `lineageTab.applyRunResults(...)` schicken.
   Korrigiert alle Live-Mapping-Fehler und ist die autoritative Quelle für
   den Endzustand:
   - **Status-Mapping** der dbt-Result-Status: `success`/`pass` → `success`,
     `warn` → `warn`, `error`/`fail`/`runtime error` → `error`,
     `skipped` → `skipped`.
   - **Modell-/Seed-/Snapshot-Results** färben ihre Node direkt.
   - **Test-Results** werden über `depends_on` (bzw. `parentMap`) auf das/die
     getestete(n) Modell(e) zurückgeführt und dort aggregiert.
   - **„Worst status wins":** Pro Node-Karte gewinnt der schlimmste Status aus
     allen Beiträgen (eigenes Build-Result + Test-Rollups). Rangfolge:
     `error > warn > success > skipped`. So spiegelt eine Modellkarte bei
     `build` sowohl einen Build-Fehler als auch einen fehlgeschlagenen Test
     wider, und bei reinem `test` das aggregierte Testergebnis.
   - **Queued-Rückkorrektur:** Nodes, die beim GO auf `queued` gesetzt wurden,
     aber in `run_results.json` nicht (direkt oder via Test-Rollup) vorkommen,
     werden auf **Neutral** zurückgesetzt (Status gelöscht) — sie wurden nicht
     ausgewertet.

## Edge Cases / Nicht-Ziele

- ANSI-Codes werden vor dem Parsen gestript (relevant bei "Colored output").
- Status ist **transient** — nur JS-State, nicht in Settings persistiert. Geht
  bei Tool-Window-Reload verloren (passt zur Laufzeit-Natur).
- Status wird **immer aufgezeichnet**, auch wenn der Nutzer nicht im
  Status-Modus ist; sichtbar, sobald er im Combo auf "Status" wechselt
  (Settings-Apply → `SettingsChangeListener` → `refreshGraph` → `renderGraph`
  mit `nodeColorMode='status'`).
- Tests bekommen keine eigene Karte (bestehendes Verhalten). Live (Stdout) ist
  das Test-Mapping unzuverlässig, da dbt Test-Zeilen nach Testnamen statt
  `schema.identifier` druckt — Test-Ergebnisse werden daher **nur über
  `run_results.json`** auf die Modellkarte(n) aggregiert (s. Datenfluss 3).
  Bei `dbt test -s mein_modell` bleibt die Modellkarte also bis zum Run-Ende
  `queued` (blau) und färbt sich dann auf das aggregierte Testergebnis.
- Nodes, die beim GO blau wurden, dbt aber nie auswertet, werden am Run-Ende
  über die Queued-Rückkorrektur auf Neutral zurückgesetzt (s. Datenfluss 3).

## Betroffene Dateien

- `src/main/resources/js/lineage.js` — Palette, `pickBarColor`, neue APIs,
  `currentColorMode`.
- `src/main/resources/js/lineage.html` (bzw. inline CSS) — `.running`-Puls-Keyframes.
- `src/main/kotlin/com/dbthelper/settings/DbtHelperSettings.kt` — Doc-Kommentar
  für dritten Wert (kein Code-Zwang).
- `src/main/kotlin/com/dbthelper/settings/DbtHelperConfigurable.kt` — Combo-Eintrag.
- `src/main/kotlin/com/dbthelper/actions/DbtRunStatusParser.kt` — **neu**.
- `src/main/kotlin/com/dbthelper/toolwindow/LineageTab.kt` — Matching-Index,
  `beginRunStatus`, `pushNodeStatus`, `applyRunResults`, `lastGraphNodeIds`.
- `src/main/kotlin/com/dbthelper/toolwindow/DbtMainPanel.kt` — GO-Hook,
  `onLine`-Tap, `onFinished`-Reconcile.
