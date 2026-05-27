# Lineage Run-Status Node Coloring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a third "Status" node-color mode to the lineage graph that colors nodes by their dbt run status (queued → running → success/warn/error/skipped) live while a dbt command runs, without re-rendering the graph or losing focus.

**Architecture:** A JS module-level `nodeStatus` map drives card bar colors via the existing `pickBarColor` path, so colors survive the post-run manifest re-render. A stateless Kotlin parser (`DbtRunStatusParser`) turns dbt's human-readable progress lines into `(relationKey, status)`; `LineageTab` resolves those to `unique_id`s and pushes them to JS. At run end, `RunResultsReconciler` reads `target/run_results.json` and pushes an authoritative full map (with test results rolled up onto model cards, "worst status wins").

**Tech Stack:** Kotlin 2.0 / JVM 21, IntelliJ Platform Gradle Plugin 2.x, JCEF webview, Cytoscape.js + HTML overlay cards, Jackson.

**Testing note:** This project has **no test source set** (`CLAUDE.md`): do not add one or invent test commands. Each task verifies via `./gradlew compileKotlin` (compile) and the runtime behavior is verified manually in `./gradlew runIde` at the end (Task 7).

---

## File Structure

- `src/main/resources/js/lineage.js` — **modify**: status palette, `pickBarColor` status branch, `currentColorMode`, `nodeStatus` store, new APIs `setNodeStatuses` / `clearNodeStatuses` / `applyRunResults`.
- `src/main/resources/js/lineage.html` — **modify**: `.card-node.running` pulse keyframes.
- `src/main/kotlin/com/dbthelper/settings/DbtHelperSettings.kt` — **modify**: doc comment for the third `nodeColorMode` value.
- `src/main/kotlin/com/dbthelper/settings/DbtHelperConfigurable.kt` — **modify**: add "Status" combo entry + 3-way bind.
- `src/main/kotlin/com/dbthelper/actions/DbtRunStatusParser.kt` — **create**: stateless line parser.
- `src/main/kotlin/com/dbthelper/actions/RunResultsReconciler.kt` — **create**: reads `run_results.json`, rolls up test results, "worst status wins".
- `src/main/kotlin/com/dbthelper/toolwindow/LineageTab.kt` — **modify**: matching index, `lastBuildableNodeIds`, `beginRunStatus`, `onRunnerLine`, `applyRunResults`.
- `src/main/kotlin/com/dbthelper/toolwindow/DbtMainPanel.kt` — **modify**: GO hook, `onLine` tap, `onFinished` reconcile.

## Status vocabulary (shared, exact strings)

`"queued"`, `"running"`, `"success"`, `"warn"`, `"error"`, `"skipped"`.
Severity rank for "worst status wins": `error` (3) > `warn` (2) > `success` (1) > `skipped` (0).

---

## Task 1: JS status store, palette, and APIs

**Files:**
- Modify: `src/main/resources/js/lineage.js`

- [ ] **Step 1: Add the status palette next to the existing color constants**

In `lineage.js`, immediately after the `NEUTRAL_BAR_COLOR` declaration (around line 23), add:

```js
// Run-status colors (used when nodeColorMode === 'status'). Set live from Kotlin.
const STATUS_BAR_COLORS = {
    queued:  '#3F7BD9',
    running: '#5AC8FA',
    success: '#3FB950',
    warn:    '#E3B341',
    error:   '#F85149',
    skipped: '#C9CED6'
};
```

- [ ] **Step 2: Add module state for statuses and the current color mode**

After the existing `let nodeCards = {};` line (around line 74), add:

```js
let currentColorMode = 'resource';
let nodeStatus = {}; // uniqueId -> status string (see STATUS_BAR_COLORS keys)
```

- [ ] **Step 3: Teach `pickBarColor` about status mode**

Replace the existing `pickBarColor` function (lines 60-68) with:

```js
    function pickBarColor(node, colorMode) {
        if (colorMode === 'status') {
            var st = nodeStatus[node.id];
            return (st && STATUS_BAR_COLORS[st]) || NEUTRAL_BAR_COLOR;
        }
        if (colorMode === 'schema') {
            return node.schema ? schemaColor(node.schema) : NEUTRAL_BAR_COLOR;
        }
        if (node.resourceType !== 'model') return NODE_BAR_COLORS[node.resourceType] || '#888';
        var mat = (node.materialization || 'view').toLowerCase();
        if (mat === 'table' || mat === 'incremental' || mat === 'materialized_view') return NODE_BAR_COLORS.model_table;
        return NODE_BAR_COLORS.model_view;
    }
```

- [ ] **Step 4: Record the color mode and re-apply running class on (re-)render**

In `initCytoscape`, the function receives `elements, currentNodeId, edgeCurveStyle, layoutDirection` but not the color mode. The color mode reaches JS via `graph.nodeColorMode` in `renderGraph`. Set `currentColorMode` inside `renderGraph` right after the graph is parsed. In `window.renderGraph` (line 496), change the parse line block so that immediately after `const graph = ...;` you add:

```js
            currentColorMode = graph.nodeColorMode || 'resource';
```

- [ ] **Step 5: Make `buildNodeCards` apply the running CSS class**

In `buildNodeCards`, inside the `else` branch that sets `--card-bar-color` (around line 188, `card.style.setProperty('--card-bar-color', data.barColor);`), add right after it:

```js
                if (currentColorMode === 'status' && nodeStatus[data.id] === 'running') {
                    card.classList.add('running');
                }
```

- [ ] **Step 6: Add a helper to recolor one card without re-render, and the public APIs**

Add this block in the "Public API" section, right before `window.renderGraph` (line 496):

```js
    // Recolor a single card from its current nodeStatus entry (status mode only).
    function applyStatusToCard(id) {
        var card = nodeCards[id];
        if (!card || card.classList.contains('stub')) return;
        var st = nodeStatus[id];
        card.style.setProperty('--card-bar-color', (st && STATUS_BAR_COLORS[st]) || NEUTRAL_BAR_COLOR);
        card.classList.toggle('running', st === 'running');
    }

    // Repaint every card from nodeStatus (status mode only). Not a graph re-render.
    function repaintAllStatusCards() {
        Object.keys(nodeCards).forEach(applyStatusToCard);
    }

    // Merge {uniqueId: status} into the store; live-update cards if in status mode.
    window.setNodeStatuses = function (jsonStr) {
        try {
            var map = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            Object.keys(map).forEach(function (id) { nodeStatus[id] = map[id]; });
            if (currentColorMode === 'status') {
                Object.keys(map).forEach(applyStatusToCard);
            }
        } catch (e) { console.error('setNodeStatuses error:', e); }
    };

    // Replace the store wholesale (authoritative final state). Absent ids -> neutral.
    window.applyRunResults = function (jsonStr) {
        try {
            nodeStatus = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            if (currentColorMode === 'status') repaintAllStatusCards();
        } catch (e) { console.error('applyRunResults error:', e); }
    };

    // Clear all statuses (called at GO before seeding queued).
    window.clearNodeStatuses = function () {
        nodeStatus = {};
        if (currentColorMode === 'status') repaintAllStatusCards();
    };
```

- [ ] **Step 7: Compile-check the bundle loads (smoke build)**

Run: `./gradlew buildPlugin -q`
Expected: `BUILD SUCCESSFUL`. (JS is inlined as a resource; this confirms the resource is present and the plugin packages. JS syntax itself is verified in Task 7's runIde check.)

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/js/lineage.js
git commit -m "lineage.js: add status color mode, nodeStatus store, and status APIs"
```

---

## Task 2: Running-state pulse animation (CSS)

**Files:**
- Modify: `src/main/resources/js/lineage.html`

- [ ] **Step 1: Add the running pulse styles**

In `lineage.html`, inside the `<style>` block, immediately after the `.card-node.stub .card-name { ... }` rule (line 226), add:

```css
        .card-node.running .card-bar {
            animation: card-running-pulse 1.2s ease-in-out infinite;
        }
        @keyframes card-running-pulse {
            0%, 100% { opacity: 1; box-shadow: 0 0 0 0 var(--card-bar-color, #5AC8FA); }
            50%      { opacity: 0.45; box-shadow: 0 0 6px 1px var(--card-bar-color, #5AC8FA); }
        }
```

- [ ] **Step 2: Build to confirm the resource packages**

Run: `./gradlew buildPlugin -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/js/lineage.html
git commit -m "lineage.html: add running-state pulse animation for status cards"
```

---

## Task 3: Settings — third color mode value

**Files:**
- Modify: `src/main/kotlin/com/dbthelper/settings/DbtHelperSettings.kt:27`
- Modify: `src/main/kotlin/com/dbthelper/settings/DbtHelperConfigurable.kt:84-92`

- [ ] **Step 1: Update the settings doc comment**

In `DbtHelperSettings.kt`, replace the comment on line 27:

```kotlin
        // How lineage node bar colors are derived: "resource" | "schema".
```

with:

```kotlin
        // How lineage node bar colors are derived: "resource" | "schema" | "status".
```

- [ ] **Step 2: Add the "Status" combo entry with 3-way binding**

In `DbtHelperConfigurable.kt`, replace the `row("Node color:") { ... }` block (lines 84-92):

```kotlin
            row("Node color:") {
                val nodeColorModes = listOf("Resource type", "Schema name")
                comboBox(nodeColorModes)
                    .bindItem(
                        { if (settings.state.nodeColorMode == "schema") "Schema name" else "Resource type" },
                        { settings.state.nodeColorMode = if (it == "Schema name") "schema" else "resource" }
                    )
                    .comment("How lineage node colors are derived")
            }
```

with:

```kotlin
            row("Node color:") {
                val nodeColorModes = listOf("Resource type", "Schema name", "Status")
                comboBox(nodeColorModes)
                    .bindItem(
                        {
                            when (settings.state.nodeColorMode) {
                                "schema" -> "Schema name"
                                "status" -> "Status"
                                else -> "Resource type"
                            }
                        },
                        {
                            settings.state.nodeColorMode = when (it) {
                                "Schema name" -> "schema"
                                "Status" -> "status"
                                else -> "resource"
                            }
                        }
                    )
                    .comment("How lineage node colors are derived. \"Status\" colors nodes by their last dbt run result.")
            }
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/dbthelper/settings/DbtHelperSettings.kt src/main/kotlin/com/dbthelper/settings/DbtHelperConfigurable.kt
git commit -m "settings: add 'Status' as third node-color mode"
```

---

## Task 4: `DbtRunStatusParser`

**Files:**
- Create: `src/main/kotlin/com/dbthelper/actions/DbtRunStatusParser.kt`

- [ ] **Step 1: Create the parser**

Create `src/main/kotlin/com/dbthelper/actions/DbtRunStatusParser.kt`:

```kotlin
package com.dbthelper.actions

/**
 * Stateless parser for dbt's human-readable progress lines emitted during
 * `run` / `build` / `test`. Maps a line to a (relationKey, status) pair, where
 * relationKey is the "schema.identifier" (or "database.schema.identifier") that
 * dbt prints, and status is one of the shared status strings.
 *
 * Examples it recognizes (after ANSI stripping):
 *   "12:00:01  1 of 3 START sql table model analytics.dim_customers ... [RUN]"   -> running
 *   "12:00:02  1 of 3 OK created sql table model analytics.dim_customers [SUCCESS]" -> success
 *   "12:00:03  2 of 3 ERROR creating sql model analytics.fct_orders ... [ERROR]"  -> error
 *   "12:00:03  3 of 3 SKIP relation analytics.dim_dates ............... [SKIP]"   -> skipped
 *
 * Test lines (PASS/WARN/FAIL named by test, not by relation) generally do not
 * yield a resolvable relationKey and are left to the run_results.json reconcile.
 */
object DbtRunStatusParser {

    data class NodeStatusUpdate(val relationKey: String, val status: String)

    private val ansiRegex = Regex("\\[[0-9;]*m")

    // "<n> of <m> <PHASE>" then the rest of the line.
    private val phaseRegex = Regex("""\b\d+ of \d+ ([A-Z]+)\b(.*)""")

    // First "word.word" token (schema.identifier). Allows a 3-part db.schema.id too.
    private val relationRegex = Regex("""([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+){1,2})""")

    fun parseLine(rawLine: String): NodeStatusUpdate? {
        val line = ansiRegex.replace(rawLine, "")
        val phaseMatch = phaseRegex.find(line) ?: return null
        val status = when (phaseMatch.groupValues[1]) {
            "START" -> "running"
            "OK", "SUCCESS", "PASS" -> "success"
            "WARN" -> "warn"
            "ERROR", "FAIL" -> "error"
            "SKIP" -> "skipped"
            else -> return null
        }
        val rest = phaseMatch.groupValues[2]
        val relation = relationRegex.find(rest)?.groupValues?.get(1) ?: return null
        return NodeStatusUpdate(relation.lowercase(), status)
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/dbthelper/actions/DbtRunStatusParser.kt
git commit -m "Add DbtRunStatusParser for dbt progress lines"
```

---

## Task 5: `RunResultsReconciler`

**Files:**
- Create: `src/main/kotlin/com/dbthelper/actions/RunResultsReconciler.kt`

- [ ] **Step 1: Create the reconciler**

Create `src/main/kotlin/com/dbthelper/actions/RunResultsReconciler.kt`:

```kotlin
package com.dbthelper.actions

import com.dbthelper.core.model.ManifestIndex
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File

/**
 * Reads dbt's `target/run_results.json` and produces an authoritative
 * { uniqueId -> status } map for the lineage cards.
 *
 * - Model / seed / snapshot results color their own node directly.
 * - Test results are rolled up onto the model(s) they depend on.
 * - When a node gets several contributions (e.g. its own build result plus a
 *   failed test on `build`), the worst status wins: error > warn > success > skipped.
 *
 * Statuses returned use the shared vocabulary: success | warn | error | skipped.
 */
object RunResultsReconciler {

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    private val rank = mapOf("skipped" to 0, "success" to 1, "warn" to 2, "error" to 3)

    /** Map a raw dbt result status to our vocabulary, or null if unrecognized. */
    private fun mapStatus(raw: String): String? = when (raw.lowercase().trim()) {
        "success", "pass" -> "success"
        "warn" -> "warn"
        "error", "fail", "runtime error" -> "error"
        "skipped" -> "skipped"
        else -> null
    }

    /**
     * @param dbtRoot the dbt project root containing target/run_results.json
     * @return uniqueId -> status, or empty map if the file is missing/unparseable.
     */
    fun reconcile(dbtRoot: File, index: ManifestIndex): Map<String, String> {
        val file = File(dbtRoot, "target/run_results.json")
        if (!file.isFile) return emptyMap()

        val root = try {
            file.inputStream().use { mapper.readTree(it) }
        } catch (_: Exception) {
            return emptyMap()
        }
        val results = root.get("results") ?: return emptyMap()

        // Accumulate the worst status per node.
        val acc = mutableMapOf<String, String>()
        fun contribute(uniqueId: String, status: String) {
            val existing = acc[uniqueId]
            if (existing == null || (rank[status] ?: -1) > (rank[existing] ?: -1)) {
                acc[uniqueId] = status
            }
        }

        for (r in results) {
            val uniqueId = r.path("unique_id").asText(null) ?: continue
            val status = mapStatus(r.path("status").asText("")) ?: continue

            if (uniqueId.startsWith("test.")) {
                // Roll up onto the buildable models this test depends on.
                val parents = index.parentMap[uniqueId] ?: emptyList()
                for (parentId in parents) {
                    if (index.nodes[parentId]?.resourceType in BUILDABLE_TYPES) {
                        contribute(parentId, status)
                    }
                }
            } else {
                contribute(uniqueId, status)
            }
        }
        return acc
    }

    val BUILDABLE_TYPES = setOf("model", "seed", "snapshot")
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/dbthelper/actions/RunResultsReconciler.kt
git commit -m "Add RunResultsReconciler with test-result rollup and worst-status-wins"
```

---

## Task 6: Wire status into `LineageTab` and `DbtMainPanel`

**Files:**
- Modify: `src/main/kotlin/com/dbthelper/toolwindow/LineageTab.kt`
- Modify: `src/main/kotlin/com/dbthelper/toolwindow/DbtMainPanel.kt`

- [ ] **Step 1: Track buildable node ids of the last-built graph in `LineageTab`**

In `LineageTab.kt`, add a field next to `expandedBoundaryNodes` (after line 61):

```kotlin
    // Buildable node ids (model/seed/snapshot) of the most recently rendered graph;
    // used to seed "queued" at GO. Updated on every refreshGraph().
    @Volatile
    private var lastBuildableNodeIds: List<String> = emptyList()
```

- [ ] **Step 2: Populate `lastBuildableNodeIds` when the graph is built**

In `LineageTab.refreshGraph()`, inside the pooled-thread block, right after `val graph = builder.build(...).copy(...)` finishes (after line 285, before `val graphJson = ...`), add:

```kotlin
                lastBuildableNodeIds = graph.nodes
                    .filter { it.resourceType in RunResultsReconciler.BUILDABLE_TYPES }
                    .map { it.id }
```

Add the import at the top of the file:

```kotlin
import com.dbthelper.actions.RunResultsReconciler
import com.dbthelper.actions.DbtRunStatusParser
```

- [ ] **Step 3: Add a status-matching index built from the manifest**

In `LineageTab.kt`, add this private helper method (place it after `pushDocsToSidebar`, around line 359):

```kotlin
    /**
     * Build { "schema.identifier" / "database.schema.identifier" -> uniqueId }
     * for all buildable nodes, for resolving dbt log relations to unique ids.
     */
    private fun buildRelationKeyIndex(index: ManifestIndex): Map<String, String> {
        val map = HashMap<String, String>()
        for ((id, node) in index.nodes) {
            if (node.resourceType !in RunResultsReconciler.BUILDABLE_TYPES) continue
            val schema = node.schema ?: continue
            val identifier = node.alias ?: node.name
            map["$schema.$identifier".lowercase()] = id
            val db = node.database
            if (db != null) map["$db.$schema.$identifier".lowercase()] = id
        }
        return map
    }
```

Add the import:

```kotlin
import com.dbthelper.core.model.ManifestIndex
```

(Note: `ManifestIndex` is already imported in `LineageTab.kt` line 11 — do not duplicate.)

- [ ] **Step 4: Add the public run-status methods to `LineageTab`**

Add these methods after `handleExpandRequest` (around line 411):

```kotlin
    /** Called at GO: clear statuses and mark all buildable graph nodes as queued. */
    fun beginRunStatus() {
        if (isDisposed) return
        val queued = lastBuildableNodeIds.associateWith { "queued" }
        val json = mapper.writeValueAsString(queued)
        val escaped = escapeJsJson(json)
        ApplicationManager.getApplication().invokeLater {
            if (isDisposed) return@invokeLater
            executeJs("clearNodeStatuses()")
            if (queued.isNotEmpty()) executeJs("setNodeStatuses('$escaped')")
        }
    }

    /** Feed one runner output line; live-update the matching node's status. */
    fun onRunnerLine(line: String) {
        if (isDisposed) return
        val update = DbtRunStatusParser.parseLine(line) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed) return@executeOnPooledThread
            val index = ManifestService.getInstance(project).getIndex()
            val uniqueId = buildRelationKeyIndex(index)[update.relationKey] ?: return@executeOnPooledThread
            val json = mapper.writeValueAsString(mapOf(uniqueId to update.status))
            val escaped = escapeJsJson(json)
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed) executeJs("setNodeStatuses('$escaped')")
            }
        }
    }

    /** Called at run end: push authoritative statuses from target/run_results.json. */
    fun applyRunResults() {
        if (isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed) return@executeOnPooledThread
            val service = ManifestService.getInstance(project)
            val dbtRoot = service.getLocator().findProjectRoot() ?: return@executeOnPooledThread
            val statuses = RunResultsReconciler.reconcile(java.io.File(dbtRoot.path), service.getIndex())
            val json = mapper.writeValueAsString(statuses)
            val escaped = escapeJsJson(json)
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed) executeJs("applyRunResults('$escaped')")
            }
        }
    }

    private fun escapeJsJson(json: String): String =
        json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
```

- [ ] **Step 5: Call the new methods from `DbtMainPanel`**

In `DbtMainPanel.kt`, define the set of verbs that drive status near the top of the class (after line 45, the `timestampRegex` field):

```kotlin
    private val statusVerbs = setOf(DbtVerb.RUN, DbtVerb.BUILD, DbtVerb.TEST)
```

In `startCommand`, right after `runnerTab.clear()` (line 106), add:

```kotlin
        if (spec.verb in statusVerbs) lineageTab.beginRunStatus()
```

In the `onLine` override (lines 112-120), feed status verbs to the lineage tab. Replace the whole `override fun onLine(line: String) { ... }` with:

```kotlin
            override fun onLine(line: String) {
                if (spec.verb in statusVerbs) lineageTab.onRunnerLine(line)
                if (spec.verb == DbtVerb.PREVIEW) {
                    if (line.startsWith("$") || line.startsWith("Previewing") ||
                        line.startsWith("ERROR") || line.matches(timestampRegex)
                    ) runnerTab.appendLine(line)
                } else {
                    runnerTab.appendLine(line)
                }
            }
```

In the `onFinished` override, inside the `invokeLater` block after `actionBar.setRunning(false)` (line 127), add:

```kotlin
                    if (spec.verb in statusVerbs) lineageTab.applyRunResults()
```

- [ ] **Step 6: Make `lineageTab` reachable**

`DbtMainPanel` already holds `private val lineageTab = LineageTab(project, this)` (line 37) — no change needed; the new calls compile against the public methods added in Step 4.

- [ ] **Step 7: Compile**

Run: `./gradlew compileKotlin -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/dbthelper/toolwindow/LineageTab.kt src/main/kotlin/com/dbthelper/toolwindow/DbtMainPanel.kt
git commit -m "Wire dbt run status into lineage: queued/live/reconcile"
```

---

## Task 7: Manual verification in sandbox IDE

**Files:** none (manual).

- [ ] **Step 1: Launch the sandbox IDE on a real dbt project**

Run: `./gradlew runIde`
Open a dbt project that has a parsed `target/manifest.json`, open a model `.sql` so the lineage graph shows it with upstream/downstream nodes.

- [ ] **Step 2: Select the Status color mode**

Open Settings → dbt Helper → set "Node color" to **Status** → Apply.
Expected: all node bars turn neutral grey (no statuses yet); graph keeps its focus/viewport (no jump).

- [ ] **Step 3: Run a build and watch live coloring**

In the action bar, set verb to **Build** (or Run) with the current model selected, press **GO**.
Expected:
- On GO, all model/seed/snapshot cards turn **blue** (queued) immediately; the graph does NOT re-layout and the viewport stays put.
- As dbt progresses, cards turn **cyan + pulse** (running), then **green** (success). A model with a failing test/build turns **red**; warnings **yellow**; skipped nodes **light grey**.
- After completion (manifest auto-reparse re-renders the graph), the colors **persist** and reflect `run_results.json` (e.g. a model with a failed test shows red).

- [ ] **Step 4: Verify queued reset**

Run a build whose selector is narrower than the visible graph (e.g. focus a model, then `GO` with selector `mein_modell` only). 
Expected: nodes in the graph that dbt did not evaluate fall back from blue to **neutral grey** at run end (not stuck blue).

- [ ] **Step 5: Verify mode isolation**

Switch "Node color" back to **Resource type** during/after a run.
Expected: cards show resource colors again; switching back to **Status** restores the run colors (statuses were recorded regardless of active mode).

- [ ] **Step 6: Final full build**

Run: `./gradlew buildPlugin -q`
Expected: `BUILD SUCCESSFUL`.

---

## Self-Review notes (for the implementer)

- **Spec coverage:** palette (T1/T2), `pickBarColor` status branch (T1), `setNodeStatuses`/`clearNodeStatuses`/`applyRunResults` (T1), running pulse (T2), settings third value + combo (T3), `DbtRunStatusParser` (T4), `RunResultsReconciler` with test rollup + worst-status-wins + status mapping (T5), `beginRunStatus`/`onRunnerLine`/`applyRunResults`/`lastBuildableNodeIds` + matching index (T6), GO/onLine/onFinished wiring (T6), queued→neutral reset (handled by `applyRunResults` replacing the store wholesale — absent ids render neutral, T1 Step 6 + T6). All covered.
- **Type consistency:** status strings are the exact set `queued|running|success|warn|error|skipped` everywhere; `RunResultsReconciler.BUILDABLE_TYPES` is the single source for "buildable" in both T5 and T6; `escapeJsJson` matches the existing escape pattern used by `refreshGraph`.
- **Post-reparse persistence:** `nodeStatus` is JS module state read by `pickBarColor`, so the post-run `renderGraph` rebuild keeps colors without extra wiring.
