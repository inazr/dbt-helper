# Shared Action Bar — Design

**Date:** 2026-05-27
**Status:** Approved (design)

## Problem

The plugin's bottom tool window has two tabs — **Lineage** (JCEF webview) and
**Runner** (toolbar + streaming log). Today the Runner acts only on the model in
the active editor, and its verb buttons each run a fixed command. There is no way
to type an arbitrary dbt selector, and the two tabs share no controls.

We want a single **settings/action bar above both tabs** that:

- lets the user type any dbt selector (e.g. `dbt_model`, `1+dbt_model+2`),
- shows the exact dbt command that will run in a read-only field,
- chooses the verb via a single-select group of option buttons,
- runs that command with one **GO** button,
- and shares this bar across both the Lineage and Runner tabs.

## Agreed behavior

- **Selector**: auto-fills from the active editor's model on file change, and is
  freely editable. Commands always use whatever text is in the field.
- **Selector → Lineage**: when the selector is a *plain single model name*, it
  also re-focuses the Lineage graph. Graph-operator selectors (containing
  `+`, `@`, `,`, spaces, `*`, `:`) drive commands only and do not move the graph.
- **Verb options**: `Run`, `Build`, `Test`, `Compile`, `Preview`,
  `Generate Docs` form a single-select toggle group. Selecting one sets the verb
  used by the preview and by GO. Default on startup: `Run`.
- **Generate Docs**: disables/greys the selector field (the command takes no
  `--select`).
- **Command preview**: a read-only field showing the exact command for the
  current verb, updating live as the selector, target, verb, or full-refresh
  changes. Display is prefixed with `dbt` (not the resolved absolute path) for
  readability.
- **GO**: executes the previewed command, auto-switches the inner tab to the
  Runner, and turns into **Stop** while a command is running. There is no
  separate Stop button.
- **full-refresh checkbox**: lives in the bar, visible only when the verb is
  `Run` or `Build`; affects the preview and execution.
- **Clear**: lives in the bar; clears the Runner log.

## Architecture (Approach A)

Collapse the two `Content`s into a single `Content` whose panel hosts the shared
bar above an inner tabbed pane.

```
DbtToolWindowFactory
└─ Content "dbt Helper" → DbtMainPanel (JPanel, BorderLayout)
   ├─ NORTH:  DbtActionBar          (shared 2-row settings bar)
   └─ CENTER: JBTabbedPane
              ├─ "Lineage" → LineageTab   (webview; +focusModel)
              └─ "Runner"  → DbtRunnerTab (reduced to log only)
```

Rationale: the platform tool-window header only allows small toolbar *actions*,
not a two-row selector+preview+buttons widget, so a header-based bar is not
viable. Duplicating the bar inside each tab would require keeping two stateful
copies in sync. A single `Content` with a `JBTabbedPane` gives full layout
control with one source of state. We use no per-`Content` platform features
today, so nothing is lost. The tool window stays discoverable because the stripe
button name comes from the `<toolWindow id=...>` registration in `plugin.xml`,
independent of the inner tabbing.

## Components

### `DbtCommandBuilder` (new, pure, `actions/`)

Single source of truth for both the preview text and the executed args.

```kotlin
enum class DbtVerb(val display: String) {
    RUN("Run"), BUILD("Build"), TEST("Test"),
    COMPILE("Compile"), PREVIEW("Preview"), GENERATE_DOCS("Generate Docs")
}

data class DbtCommandSpec(
    val verb: DbtVerb,
    val selector: String,
    val target: String,        // blank = no --target
    val fullRefresh: Boolean,
    val previewLimit: Int
)

object DbtCommandBuilder {
    fun buildArgs(spec: DbtCommandSpec, dbtExe: String): List<String>
    fun buildDisplay(spec: DbtCommandSpec): String   // prefixed "dbt ..."
}
```

Verb → command mapping (both methods share one `when(verb)`):

| Verb | Command |
|------|---------|
| Run | `dbt run --select <sel> [--full-refresh] [--target <t>]` |
| Build | `dbt build --select <sel> [--full-refresh] [--target <t>]` |
| Test | `dbt test --select <sel> [--target <t>]` |
| Compile | `dbt compile --select <sel> [--target <t>]` |
| Preview | `dbt show --select <sel> --limit <N> --output json [--target <t>]` |
| Generate Docs | `dbt docs generate [--target <t>]` (selector omitted) |

`--target` is appended only when `target` is non-blank. `--full-refresh` is
appended only for `RUN`/`BUILD` when `fullRefresh` is true.

### `DbtCommandRunner` — refactor

- Add `fun run(spec: DbtCommandSpec, listener: OutputListener)` that: resolves the
  executable (`findDbtExecutable`), resolves the project root, builds args via
  `DbtCommandBuilder.buildArgs`, runs in the root, and on success of `RUN`,
  `BUILD`, or `GENERATE_DOCS` triggers `ManifestService.getInstance(project).reparse()`
  (preserving today's auto-reload behavior).
- Retain the JSON-table formatting (`tryFormatJsonTable`, `nodeToString`) used for
  `dbt show`; the coordinator selects a table-formatting listener when
  `verb == PREVIEW`.
- Remove the old per-verb methods (`runModel`, `runTest`, `runCompile`,
  `runShow`, `runDocsGenerate`) once `DbtMainPanel` is the only caller. Keep
  `findDbtExecutable` and `getVersion`.

### `DbtActionBar` (new, `toolwindow/DbtActionBar.kt`)

A `JPanel` with two stacked rows:

- **Row 1**: `selectorField` (`JBTextField`, left) and `commandPreview`
  (read-only `JBTextField` that grows to fill, right).
- **Row 2**: `Target:` label + combo · verb toggle buttons (single-select via a
  `ButtonGroup` of `JToggleButton`s) · `full-refresh` checkbox (hidden unless verb
  is Run/Build) · `Clear` button · `GO` button.

Responsibilities:

- Recompute `commandPreview` whenever selector / verb / target / full-refresh
  changes, via `DbtCommandBuilder.buildDisplay`.
- Expose callbacks set by `DbtMainPanel`: `onGo(spec)`, `onStop()`, `onClear()`,
  `onSelectorChanged(text)`, `onTargetChanged(target)`.
- `setRunning(Boolean)`: toggle GO↔Stop label/behavior; disable verb buttons and
  selector while running.
- `setSelector(text)`: programmatic auto-fill from the active editor (does not
  re-fire `onSelectorChanged` in a way that loops).
- `setFullRefreshVisible(Boolean)`: driven by current model materialization.
- Disable GO when a selector-requiring verb has a blank selector.
- Generate Docs selected → selector field disabled and greyed.

Target combo logic moves verbatim from `DbtRunnerTab`: populate from
`ProfilesParser.getTargetNames()`, select `settings.activeTarget` or the profile
default, persist selection to `settings.activeTarget`, and publish
`SettingsChangeListener.TOPIC`. A `refreshTargets()` method (currently dead in
`DbtRunnerTab`) is recreated here and invoked on `SettingsChangeListener`.

### `DbtRunnerTab` — reduced

- Remove the entire toolbar (target combo, all buttons, checkbox) and the
  per-verb run methods.
- Keep the log `JTextArea` + `JBScrollPane`.
- Expose: `appendLine(String)`, `clear()`, and listener factories — the plain
  streaming listener and the preview JSON-table listener (the table formatter
  helpers may live here or move with `runShow` logic; keep them adjacent to the
  log since formatting targets the log).
- Process/running state (`currentProcess`, `isRunning`) moves out to
  `DbtMainPanel`.

### `DbtMainPanel` (new, `toolwindow/DbtMainPanel.kt`) — coordinator

Owns construction and wiring so the factory stays thin and tabs don't reach into
each other. Owns run-state.

- Constructs `DbtActionBar`, `JBTabbedPane`, `LineageTab`, `DbtRunnerTab`.
- Subscribes to:
  - `CurrentModelListener.TOPIC` → auto-fill selector with the current model name
    and update full-refresh visibility (incremental check).
  - `SettingsChangeListener.TOPIC` → `actionBar.refreshTargets()`.
  - `ManifestUpdateListener.TOPIC` → re-resolve current model / refresh state.
- `onGo(spec)`: switch tabbed pane to Runner, `runnerTab.clear()`,
  `actionBar.setRunning(true)`, then `DbtCommandRunner(project).run(spec, listener)`
  where `listener` is the table listener if `spec.verb == PREVIEW` else the plain
  listener; on `onFinished`, reset running state and surface notifications as the
  Runner does today.
- `onStop()`: `currentProcess?.destroyForcibly()`; append a termination line.
- `onSelectorChanged(text)`: if `text` is a plain model name (no graph-operator
  characters) → `lineageTab.focusModel(text)`.
- Holds `@Volatile currentProcess: Process?` updated via the listener's
  `onProcessStarted`.

### `LineageTab` — one addition

`fun focusModel(modelName: String)`: resolve the model name to a node id using a
new `ManifestService`/index helper `findModelIdByName(name): String?` (match a
`node` whose `name == modelName` and `resourceType == "model"`); if found and
different from `currentModelId`, set it, clear `expandedBoundaryNodes`, and
`refreshGraph()`. No-op when unresolved. Existing editor-driven focus
(`onFileChanged`) is unchanged.

### `DbtToolWindowFactory` — simplified

Create a single `Content` wrapping `DbtMainPanel`. The `HIDE_ID_LABEL` client
property is no longer needed (no platform tab strip is shown) and is removed.
`shouldBeAvailable` and `DumbAware` stay.

### `plugin.xml`

`<toolWindow id=... factoryClass=DbtToolWindowFactory>` unchanged. No new
extensions, services, or listeners are registered — `DbtMainPanel` subscribes to
existing message-bus topics programmatically via `project.messageBus.connect`.

## Error handling

- Blank selector + selector-requiring verb → GO is disabled, so no command with
  an empty `--select` can run. The preview field may show the partial command for
  feedback, but execution is gated on GO being enabled.
- No dbt project root → existing `DbtCommandRunner` guard logs an error line and
  finishes with exit code -1; coordinator resets running state.
- Unresolved selector for lineage → `focusModel` is a no-op (graph unchanged).
- Command-runner exceptions are already caught in `runCommand`; running state is
  always reset in `onFinished`.

## Out of scope

- Persisting the selector text or last-used verb across sessions.
- Multi-project workspace selection (still uses the first dbt root, per the
  existing `ManifestService` TODO).
- Adding a test source set.

## Verification

No test source set exists in this project; none is added. `DbtCommandBuilder` is
pure and unit-testable should `src/test/kotlin` be introduced later. Manual
verification via `./gradlew runIde`:

1. Bar renders above both tabs; switching tabs keeps the bar.
2. Opening a model file auto-fills the selector; editing it works.
3. Each verb button updates the preview to the correct command; target and
   full-refresh are reflected; full-refresh hidden for non-Run/Build.
4. Generate Docs greys the selector and drops `--select`.
5. GO switches to Runner, streams output, becomes Stop; Stop aborts.
6. Plain selector re-focuses the Lineage graph; `1+model+2` does not.
7. Clear empties the log.
8. Preview verb produces the formatted JSON table in the log.
