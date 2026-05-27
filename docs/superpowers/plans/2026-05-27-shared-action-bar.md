# Shared Action Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the two independent tool-window tabs with a single panel that hosts a shared action bar (dbt selector + live command preview + verb options + GO) above an inner tabbed pane containing the unchanged Lineage view and a log-only Runner.

**Architecture:** One `Content` wraps a new `DbtMainPanel` (BorderLayout): a new `DbtActionBar` is NORTH, a `JBTabbedPane` (Lineage + Runner) is CENTER. A new pure `DbtCommandBuilder` is the single source of truth for both the preview string and the executed args. `DbtMainPanel` is the coordinator that owns run-state, wires the bar to the runner/lineage, and subscribes to existing message-bus topics. The Runner tab is reduced to a log surface; verb/target/flag controls move into the bar.

**Tech Stack:** Kotlin 2.0 / JVM 21, IntelliJ Platform Gradle Plugin 2.x, Swing + JBUI components, Jackson (existing). No test source set (per CLAUDE.md) — verification is `./gradlew buildPlugin` per task plus a manual `runIde` checklist.

**Spec:** `docs/superpowers/specs/2026-05-27-shared-action-bar-design.md`

---

## File structure

| File | Responsibility | Change |
|------|----------------|--------|
| `src/main/kotlin/com/dbthelper/actions/DbtCommandBuilder.kt` | Pure verb→args/display builder; `DbtVerb`, `DbtCommandSpec` | Create |
| `src/main/kotlin/com/dbthelper/actions/DbtCommandRunner.kt` | Add generic `run(spec, listener)`; remove per-verb methods (last task) | Modify |
| `src/main/kotlin/com/dbthelper/core/ManifestService.kt` | Add `findModelIdByName` | Modify |
| `src/main/kotlin/com/dbthelper/toolwindow/LineageTab.kt` | Add `focusModel(name)` | Modify |
| `src/main/kotlin/com/dbthelper/toolwindow/DbtRunnerTab.kt` | Reduce to log surface + `formatPreviewTable` | Modify (heavy) |
| `src/main/kotlin/com/dbthelper/toolwindow/DbtActionBar.kt` | The 2-row shared bar; owns selector/preview/target/verbs/GO | Create |
| `src/main/kotlin/com/dbthelper/toolwindow/DbtMainPanel.kt` | Coordinator: layout, wiring, run-state, listeners, notifications | Create |
| `src/main/kotlin/com/dbthelper/toolwindow/DbtToolWindowFactory.kt` | Build single Content from `DbtMainPanel` | Modify |

**Task order keeps the project compiling after every task.** Per-verb methods on `DbtCommandRunner` stay until Task 8 (after their last caller is gone in Task 4).

---

## Task 1: DbtCommandBuilder (pure)

**Files:**
- Create: `src/main/kotlin/com/dbthelper/actions/DbtCommandBuilder.kt`

- [ ] **Step 1: Create the builder**

```kotlin
package com.dbthelper.actions

enum class DbtVerb(val display: String) {
    RUN("Run"),
    BUILD("Build"),
    TEST("Test"),
    COMPILE("Compile"),
    PREVIEW("Preview"),
    GENERATE_DOCS("Generate Docs");

    /** Verbs that send `--select <selector>`. */
    val usesSelector: Boolean get() = this != GENERATE_DOCS

    /** Verbs that accept `--full-refresh`. */
    val supportsFullRefresh: Boolean get() = this == RUN || this == BUILD
}

data class DbtCommandSpec(
    val verb: DbtVerb,
    val selector: String,
    val target: String,        // blank = no --target
    val fullRefresh: Boolean,
    val previewLimit: Int
)

/**
 * Single source of truth for the dbt command shown in the action bar's preview
 * field and the args handed to ProcessBuilder. Both methods walk the same
 * when(verb) so display and execution can never drift.
 */
object DbtCommandBuilder {

    fun buildArgs(spec: DbtCommandSpec, dbtExe: String): List<String> =
        buildTokens(spec, dbtExe)

    /** Human-readable command, always prefixed with "dbt" (not the exe path). */
    fun buildDisplay(spec: DbtCommandSpec): String =
        buildTokens(spec, "dbt").joinToString(" ")

    private fun buildTokens(spec: DbtCommandSpec, exe: String): List<String> {
        val sel = spec.selector.trim()
        val cmd = mutableListOf(exe)
        when (spec.verb) {
            DbtVerb.RUN -> {
                cmd += listOf("run", "--select", sel)
                if (spec.fullRefresh) cmd += "--full-refresh"
            }
            DbtVerb.BUILD -> {
                cmd += listOf("build", "--select", sel)
                if (spec.fullRefresh) cmd += "--full-refresh"
            }
            DbtVerb.TEST -> cmd += listOf("test", "--select", sel)
            DbtVerb.COMPILE -> cmd += listOf("compile", "--select", sel)
            DbtVerb.PREVIEW -> cmd += listOf(
                "show", "--select", sel,
                "--limit", spec.previewLimit.toString(),
                "--output", "json"
            )
            DbtVerb.GENERATE_DOCS -> cmd += listOf("docs", "generate")
        }
        if (spec.target.isNotBlank()) cmd += listOf("--target", spec.target)
        return cmd
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL, no errors referencing `DbtCommandBuilder`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/dbthelper/actions/DbtCommandBuilder.kt
git commit -m "Add pure DbtCommandBuilder for dbt command construction"
```

---

## Task 2: DbtCommandRunner.run(spec)

Add the generic entry point. Leave the old per-verb methods in place for now (still used by `DbtRunnerTab` until Task 4) so the project keeps compiling.

**Files:**
- Modify: `src/main/kotlin/com/dbthelper/actions/DbtCommandRunner.kt`

- [ ] **Step 1: Add the `run(spec, listener)` method**

Insert this method into the `DbtCommandRunner` class, immediately after the `getVersion()` method (before `runShow`):

```kotlin
fun run(spec: DbtCommandSpec, listener: OutputListener) {
    val dbt = findDbtExecutable()
    val locator = DbtProjectLocator(project)
    val projectRoot = locator.findProjectRoot()?.path

    if (projectRoot == null) {
        listener.onLine("ERROR: No dbt project found")
        listener.onFinished(RunResult(-1, "", false))
        return
    }

    val command = DbtCommandBuilder.buildArgs(spec, dbt)

    runCommand(command, File(projectRoot), listener) { result ->
        // Auto-reload manifest after commands that can change it.
        if (result.success && spec.verb in setOf(
                DbtVerb.RUN, DbtVerb.BUILD, DbtVerb.GENERATE_DOCS
            )
        ) {
            ManifestService.getInstance(project).reparse()
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL. (`ManifestService` and `File` are already imported in this file.)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/dbthelper/actions/DbtCommandRunner.kt
git commit -m "Add generic DbtCommandRunner.run(spec) entry point"
```

---

## Task 3: ManifestService.findModelIdByName + LineageTab.focusModel

Independent of the bar; lets the coordinator drive the graph from a plain selector.

**Files:**
- Modify: `src/main/kotlin/com/dbthelper/core/ManifestService.kt`
- Modify: `src/main/kotlin/com/dbthelper/toolwindow/LineageTab.kt`

- [ ] **Step 1: Add `findModelIdByName` to ManifestService**

Insert immediately after the existing `findCurrentModelId(file)` method:

```kotlin
/** Resolve a plain dbt model name to its node uniqueId, or null if not found. */
fun findModelIdByName(modelName: String): String? {
    val target = modelName.trim()
    if (target.isEmpty()) return null
    return getIndex().nodes.values
        .firstOrNull { it.resourceType == "model" && it.name == target }
        ?.uniqueId
}
```

- [ ] **Step 2: Add `focusModel` to LineageTab**

Insert as a public method in `LineageTab` (e.g. immediately after `onFileChanged`):

```kotlin
/** Focus the graph on a plain model name (no-op if it doesn't resolve). */
fun focusModel(modelName: String) {
    if (isDisposed) return
    val modelId = ManifestService.getInstance(project).findModelIdByName(modelName) ?: return
    if (modelId != currentModelId) {
        currentModelId = modelId
        expandedBoundaryNodes.clear()
        refreshGraph()
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/dbthelper/core/ManifestService.kt src/main/kotlin/com/dbthelper/toolwindow/LineageTab.kt
git commit -m "Add model-name resolution and LineageTab.focusModel"
```

---

## Task 4: Reduce DbtRunnerTab to a log surface

Strip the toolbar, the target combo, every verb/run method, and run-state. Keep only the log and the preview-table formatter. After this task, nothing references the old per-verb `DbtCommandRunner` methods.

**Files:**
- Modify (replace whole file): `src/main/kotlin/com/dbthelper/toolwindow/DbtRunnerTab.kt`

- [ ] **Step 1: Replace the file contents**

```kotlin
package com.dbthelper.toolwindow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.text.DefaultCaret

/**
 * Log surface for dbt command output. All controls live in DbtActionBar;
 * DbtMainPanel drives this tab via appendLine/clear and owns run-state.
 */
class DbtRunnerTab(
    project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    private val logArea = JTextArea().apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, 12).takeIf { it.family == "JetBrains Mono" }
            ?: Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.ALWAYS_UPDATE
    }

    init {
        Disposer.register(parentDisposable, this)
        add(JBScrollPane(logArea), BorderLayout.CENTER)
    }

    /** Append a line to the log (safe to call from any thread). */
    fun appendLine(line: String) {
        ApplicationManager.getApplication().invokeLater { logArea.append(line + "\n") }
    }

    /** Clear the log (safe to call from any thread). */
    fun clear() {
        ApplicationManager.getApplication().invokeLater { logArea.text = "" }
    }

    /**
     * Format `dbt show --output json` output as an ASCII table, or null if the
     * output doesn't contain a parseable JSON result.
     */
    fun formatPreviewTable(output: String): String? {
        try {
            val clean = output.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")
            val jsonStart = clean.indexOfFirst { it == '{' || it == '[' }
            if (jsonStart < 0) return null
            val jsonStr = clean.substring(jsonStart)
            val rootNode = mapper.readTree(jsonStr)

            val rowsNode: JsonNode = when {
                rootNode.isObject && rootNode.has("show") -> rootNode.get("show")
                rootNode.isArray -> rootNode
                else -> return null
            }
            if (!rowsNode.isArray || rowsNode.size() == 0) return "(0 rows)"

            val firstRow = rowsNode[0]
            val columns = firstRow.fieldNames().asSequence().toList()
            val widths = columns.map { col ->
                val dataMax = (0 until rowsNode.size()).maxOf { i ->
                    nodeToString(rowsNode[i].get(col)).length
                }
                maxOf(col.length, dataMax).coerceAtMost(60)
            }

            val sb = StringBuilder()
            val separator = widths.joinToString("-+-", "+-", "-+") { "-".repeat(it) }
            sb.appendLine(separator)
            sb.appendLine(columns.mapIndexed { i, col -> col.padEnd(widths[i]) }.joinToString(" | ", "| ", " |"))
            sb.appendLine(separator)
            for (i in 0 until rowsNode.size()) {
                val row = rowsNode[i]
                val line = columns.mapIndexed { j, col ->
                    val value = nodeToString(row.get(col))
                    if (value.length > widths[j]) value.take(widths[j] - 3) + "..." else value.padEnd(widths[j])
                }.joinToString(" | ", "| ", " |")
                sb.appendLine(line)
            }
            sb.appendLine(separator)
            sb.appendLine("(${rowsNode.size()} rows)")
            return sb.toString()
        } catch (_: Exception) {
            return null
        }
    }

    private fun nodeToString(node: JsonNode?): String {
        if (node == null || node.isNull) return "null"
        if (node.isTextual) return node.asText()
        if (node.isNumber) return node.numberValue().toString()
        if (node.isBoolean) return node.asBoolean().toString()
        return node.toString()
    }

    override fun dispose() {}
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL. `DbtToolWindowFactory` still constructs `DbtRunnerTab(project, disposable)` — the constructor signature is unchanged, so it still compiles.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/dbthelper/toolwindow/DbtRunnerTab.kt
git commit -m "Reduce DbtRunnerTab to a log surface"
```

---

## Task 5: DbtActionBar

The 2-row shared bar. Self-contained: callbacks are nullable lambdas the coordinator sets later.

**Files:**
- Create: `src/main/kotlin/com/dbthelper/toolwindow/DbtActionBar.kt`

- [ ] **Step 1: Create the bar**

```kotlin
package com.dbthelper.toolwindow

import com.dbthelper.actions.DbtCommandSpec
import com.dbthelper.actions.DbtVerb
import com.dbthelper.core.ProfilesParser
import com.dbthelper.settings.DbtHelperSettings
import com.dbthelper.settings.SettingsChangeListener
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * Shared settings/action bar shown above the Lineage and Runner tabs.
 * Row 1: selector field | read-only command preview.
 * Row 2: target combo, verb toggle group, full-refresh, Clear, GO.
 *
 * Holds no execution logic — it exposes callbacks the coordinator wires up.
 */
class DbtActionBar(private val project: Project) : JPanel(BorderLayout()) {

    // --- callbacks set by DbtMainPanel ---
    var onGo: ((DbtCommandSpec) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onSelectorChanged: ((String) -> Unit)? = null

    private val selectorField = JBTextField().apply {
        emptyText.text = "dbt selector (e.g. my_model or 1+my_model+2)"
    }
    private val commandPreview = JBTextField().apply {
        isEditable = false
        toolTipText = "The exact dbt command GO will run"
    }

    private val targetCombo = JComboBox<String>().apply {
        toolTipText = "dbt target"
        preferredSize = Dimension(120, preferredSize.height)
    }

    private val verbButtons: Map<DbtVerb, JToggleButton> = DbtVerb.entries.associateWith {
        JToggleButton(it.display)
    }
    private val verbGroup = ButtonGroup().apply { verbButtons.values.forEach { add(it) } }

    private val fullRefreshCheckBox = JCheckBox("full-refresh").apply {
        toolTipText = "Rebuild incremental models from scratch (--full-refresh)"
    }
    private val clearButton = JButton("Clear").apply { toolTipText = "Clear log output" }
    private val goButton = JButton("GO")

    private var running = false
    private var fullRefreshAllowedForModel = false

    init {
        border = JBUI.Borders.empty(4)

        // Row 1: selector | preview
        val row1 = JPanel(BorderLayout(8, 0)).apply {
            add(selectorField, BorderLayout.WEST)
            add(commandPreview, BorderLayout.CENTER)
        }
        selectorField.preferredSize = Dimension(260, selectorField.preferredSize.height)

        // Row 2: target + verbs + flags + actions
        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JLabel("Target:"))
            add(targetCombo)
            verbButtons.values.forEach { add(it) }
            add(fullRefreshCheckBox)
            add(Box.createHorizontalStrut(8))
            add(clearButton)
            add(goButton)
        }

        val stack = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(row1)
            add(row2)
        }
        add(stack, BorderLayout.CENTER)

        initTargetCombo()
        initListeners()
        verbButtons[DbtVerb.RUN]!!.isSelected = true
        updateForVerb()
    }

    // --- public API used by DbtMainPanel ---

    fun setRunning(value: Boolean) {
        running = value
        goButton.text = if (value) "Stop" else "GO"
        selectorField.isEnabled = !value && selectedVerb().usesSelector
        verbButtons.values.forEach { it.isEnabled = !value }
        clearButton.isEnabled = !value
        if (!value) updateGoEnabled()
    }

    /** Auto-fill the selector from the active editor (does not move the graph). */
    fun setSelector(text: String) {
        if (selectorField.text == text) return
        selectorField.text = text
        updatePreview()
        updateGoEnabled()
    }

    /** Show/hide full-refresh based on whether the current model is incremental. */
    fun setFullRefreshAvailable(incremental: Boolean) {
        fullRefreshAllowedForModel = incremental
        updateFullRefreshVisibility()
    }

    fun refreshTargets() {
        val settings = DbtHelperSettings.getInstance(project)
        val profiles = ProfilesParser.getInstance(project)
        profiles.invalidateCache()
        val targets = profiles.getTargetNames()
        val current = targetCombo.selectedItem as? String
        targetCombo.actionListeners.forEach { targetCombo.removeActionListener(it) }
        targetCombo.removeAllItems()
        targets.forEach { targetCombo.addItem(it) }
        val restore = (current ?: settings.state.activeTarget).takeIf { targets.contains(it) }
        if (restore != null) targetCombo.selectedItem = restore
        addTargetListener(settings)
    }

    // --- internals ---

    private fun initTargetCombo() {
        val settings = DbtHelperSettings.getInstance(project)
        val profiles = ProfilesParser.getInstance(project)
        val targets = profiles.getTargetNames()
        val defaultTarget = profiles.getDefaultTarget()
        targetCombo.removeAllItems()
        targets.forEach { targetCombo.addItem(it) }
        val current = settings.state.activeTarget.ifBlank { defaultTarget ?: "" }
        if (current.isNotBlank() && targets.contains(current)) targetCombo.selectedItem = current
        addTargetListener(settings)
    }

    private fun addTargetListener(settings: DbtHelperSettings) {
        targetCombo.addActionListener {
            val selected = targetCombo.selectedItem as? String ?: return@addActionListener
            settings.state.activeTarget = selected
            updatePreview()
            project.messageBus.syncPublisher(SettingsChangeListener.TOPIC).onSettingsChanged()
        }
    }

    private fun initListeners() {
        selectorField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun changed() {
                updatePreview()
                updateGoEnabled()
                onSelectorChanged?.invoke(selectorField.text.trim())
            }
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = changed()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = changed()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = changed()
        })
        verbButtons.values.forEach { btn ->
            btn.addActionListener { updateForVerb() }
        }
        fullRefreshCheckBox.addActionListener { updatePreview() }
        clearButton.addActionListener { onClear?.invoke() }
        goButton.addActionListener {
            if (running) onStop?.invoke() else onGo?.invoke(currentSpec())
        }
    }

    private fun selectedVerb(): DbtVerb =
        verbButtons.entries.first { it.value.isSelected }.key

    private fun currentSpec(): DbtCommandSpec {
        val settings = DbtHelperSettings.getInstance(project)
        return DbtCommandSpec(
            verb = selectedVerb(),
            selector = selectorField.text.trim(),
            target = (targetCombo.selectedItem as? String).orEmpty(),
            fullRefresh = fullRefreshCheckBox.isSelected && selectedVerb().supportsFullRefresh,
            previewLimit = settings.state.previewRowLimit
        )
    }

    private fun updateForVerb() {
        val verb = selectedVerb()
        selectorField.isEnabled = !running && verb.usesSelector
        updateFullRefreshVisibility()
        updatePreview()
        updateGoEnabled()
    }

    private fun updateFullRefreshVisibility() {
        val verb = selectedVerb()
        val visible = verb.supportsFullRefresh && fullRefreshAllowedForModel
        fullRefreshCheckBox.isVisible = visible
        if (!visible) fullRefreshCheckBox.isSelected = false
        revalidate()
        repaint()
    }

    private fun updatePreview() {
        commandPreview.text = com.dbthelper.actions.DbtCommandBuilder.buildDisplay(currentSpec())
    }

    private fun updateGoEnabled() {
        if (running) { goButton.isEnabled = true; return }
        val verb = selectedVerb()
        goButton.isEnabled = !verb.usesSelector || selectorField.text.isNotBlank()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL. (`ProfilesParser.getDefaultTarget()` and `getTargetNames()` / `invalidateCache()` already exist — they are used by the current `DbtRunnerTab` git history and `ProfilesParser`.)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/dbthelper/toolwindow/DbtActionBar.kt
git commit -m "Add DbtActionBar: shared selector/preview/verb/GO bar"
```

---

## Task 6: DbtMainPanel coordinator

Wires the bar to the runner + lineage, owns run-state and notifications.

**Files:**
- Create: `src/main/kotlin/com/dbthelper/toolwindow/DbtMainPanel.kt`

- [ ] **Step 1: Create the coordinator**

```kotlin
package com.dbthelper.toolwindow

import com.dbthelper.actions.DbtCommandRunner
import com.dbthelper.actions.DbtCommandSpec
import com.dbthelper.actions.DbtVerb
import com.dbthelper.core.ManifestService
import com.dbthelper.core.ManifestUpdateListener
import com.dbthelper.core.model.ManifestIndex
import com.dbthelper.listeners.CurrentModelListener
import com.dbthelper.settings.DbtHelperSettings
import com.dbthelper.settings.SettingsChangeListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Hosts the shared action bar above the Lineage + Runner tabs and coordinates
 * between them: runs commands, owns run-state, auto-fills the selector from the
 * editor, and drives the lineage graph from plain selectors.
 */
class DbtMainPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val actionBar = DbtActionBar(project)
    private val tabs = JBTabbedPane()
    private val lineageTab = LineageTab(project, this)
    private val runnerTab = DbtRunnerTab(project, this)

    @Volatile private var currentProcess: Process? = null
    @Volatile private var isRunning = false

    private val plainNameRegex = Regex("^[A-Za-z0-9_]+$")

    init {
        Disposer.register(parentDisposable, this)

        tabs.addTab("Lineage", lineageTab)
        tabs.addTab("Runner", runnerTab)
        add(actionBar, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        actionBar.onGo = { spec -> startCommand(spec) }
        actionBar.onStop = { stopCommand() }
        actionBar.onClear = { runnerTab.clear() }
        actionBar.onSelectorChanged = { sel -> driveLineage(sel) }

        val connection = project.messageBus.connect(this)
        connection.subscribe(CurrentModelListener.TOPIC, object : CurrentModelListener {
            override fun onCurrentModelChanged(file: VirtualFile) {
                ApplicationManager.getApplication().invokeLater { autoFillFromFile(file) }
            }
        })
        connection.subscribe(SettingsChangeListener.TOPIC, object : SettingsChangeListener {
            override fun onSettingsChanged() {
                ApplicationManager.getApplication().invokeLater { actionBar.refreshTargets() }
            }
        })
        connection.subscribe(ManifestUpdateListener.TOPIC, object : ManifestUpdateListener {
            override fun onManifestUpdated(index: ManifestIndex) {
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                        ?.let { autoFillFromFile(it) }
                }
            }
        })

        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let { autoFillFromFile(it) }
    }

    private fun autoFillFromFile(file: VirtualFile) {
        val service = ManifestService.getInstance(project)
        val modelId = service.findCurrentModelId(file) ?: return
        val node = service.getIndex().nodes[modelId] ?: return
        actionBar.setSelector(node.name)
        actionBar.setFullRefreshAvailable(node.config["materialized"] == "incremental")
    }

    private fun driveLineage(selector: String) {
        if (plainNameRegex.matches(selector)) lineageTab.focusModel(selector)
    }

    private fun startCommand(spec: DbtCommandSpec) {
        if (isRunning) return
        isRunning = true
        actionBar.setRunning(true)
        tabs.selectedComponent = runnerTab
        runnerTab.clear()

        val runner = DbtCommandRunner(project)
        runner.run(spec, object : DbtCommandRunner.OutputListener {
            override fun onProcessStarted(process: Process) { currentProcess = process }

            override fun onLine(line: String) {
                if (spec.verb == DbtVerb.PREVIEW) {
                    // For preview, only echo the command + progress lines; the table
                    // is appended on finish.
                    if (line.startsWith("$") || line.startsWith("Previewing") ||
                        line.matches(Regex("^\\d{2}:\\d{2}:\\d{2}.*"))
                    ) runnerTab.appendLine(line)
                } else {
                    runnerTab.appendLine(line)
                }
            }

            override fun onFinished(result: DbtCommandRunner.RunResult) {
                ApplicationManager.getApplication().invokeLater {
                    isRunning = false
                    currentProcess = null
                    actionBar.setRunning(false)

                    if (spec.verb == DbtVerb.PREVIEW && result.success) {
                        val table = runnerTab.formatPreviewTable(result.output)
                        runnerTab.appendLine("\n" + (table ?: "(no data returned)"))
                    }

                    val label = "dbt ${spec.verb.display.lowercase()}"
                    if (result.success) {
                        notify("$label completed", NotificationType.INFORMATION)
                    } else if (result.exitCode != -1) {
                        notify("$label failed (exit code ${result.exitCode})", NotificationType.ERROR)
                    }
                }
            }
        })
    }

    private fun stopCommand() {
        currentProcess?.destroyForcibly()
        currentProcess = null
        runnerTab.appendLine("\n--- Process terminated ---")
        isRunning = false
        actionBar.setRunning(false)
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("dbt Helper")
            .createNotification(content, type)
            .notify(project)
        if (DbtHelperSettings.getInstance(project).state.enableSystemNotifications) {
            val title = if (type == NotificationType.ERROR) "dbt Error" else "dbt Helper"
            com.intellij.ui.SystemNotifications.getInstance().notify("dbt-helper", title, content)
        }
    }

    override fun dispose() {}
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/dbthelper/toolwindow/DbtMainPanel.kt
git commit -m "Add DbtMainPanel coordinator wiring the shared bar to both tabs"
```

---

## Task 7: DbtToolWindowFactory — single Content

**Files:**
- Modify (replace whole file): `src/main/kotlin/com/dbthelper/toolwindow/DbtToolWindowFactory.kt`

- [ ] **Step 1: Replace the file contents**

```kotlin
package com.dbthelper.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class DbtToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainPanel = DbtMainPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL. The `HIDE_ID_LABEL` workaround and the old `DbtProjectLocator` import are gone (no platform tab strip is shown now).

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/dbthelper/toolwindow/DbtToolWindowFactory.kt
git commit -m "Host shared bar and inner tabs in a single tool-window Content"
```

---

## Task 8: Remove dead per-verb methods from DbtCommandRunner

Now that `DbtMainPanel` is the only caller, delete the superseded methods.

**Files:**
- Modify: `src/main/kotlin/com/dbthelper/actions/DbtCommandRunner.kt`

- [ ] **Step 1: Delete the superseded methods**

Remove these methods entirely from `DbtCommandRunner` (they are no longer referenced): `runShow`, `runModel`, `runTest`, `runCompile`, `runDocsGenerate`. Also remove `tryFormatJsonTable` and `nodeToString` if present in this file (the table formatter now lives in `DbtRunnerTab`), and drop the now-unused `mapper`/`JsonNode`/Jackson imports if they remain.

Keep: `RunResult`, `OutputListener`, `findDbtExecutable`, `getVersion`, the new `run(spec, listener)`, and `runCommand`.

- [ ] **Step 2: Verify it compiles and no references remain**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL.

Run: `grep -rn "runModel\|runTest\|runCompile\|runDocsGenerate\|\.runShow\|runPreview" src/main/kotlin/`
Expected: no matches.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/dbthelper/actions/DbtCommandRunner.kt
git commit -m "Remove per-verb DbtCommandRunner methods superseded by run(spec)"
```

---

## Task 9: Build + manual verification

**Files:** none (verification only)

- [ ] **Step 1: Full plugin build**

Run: `./gradlew buildPlugin -q`
Expected: BUILD SUCCESSFUL; `build/distributions/dbt-helper-<version>.zip` produced.

- [ ] **Step 2: Launch sandbox IDE**

Run: `./gradlew runIde`

- [ ] **Step 3: Walk the manual checklist** (open the dbt tool window in the sandbox, with a dbt project that has a generated `target/manifest.json`)

1. The action bar renders above the tabs; switching Lineage⇄Runner keeps the bar in place.
2. Opening a model `.sql` file auto-fills the selector with the model name; the preview shows `dbt run --select <name> [--target <t>]`.
3. Editing the selector updates the preview live; a plain name re-focuses the Lineage graph; `1+<name>+2` updates the command but not the graph.
4. Clicking each verb toggle updates the preview to the correct command. full-refresh checkbox shows only for Run/Build and only for an incremental model, and adds `--full-refresh` to the preview when checked.
5. Selecting **Generate Docs** greys the selector and the preview shows `dbt docs generate [--target <t>]` (no `--select`).
6. **GO** switches to the Runner tab, clears the log, streams output, and becomes **Stop**; clicking **Stop** terminates the process and logs `--- Process terminated ---`.
7. **Preview** verb produces the formatted JSON table in the log.
8. **Clear** empties the log.
9. Changing the target updates the preview and persists (reopen tool window — target selection sticks).
10. GO is disabled when the selector is empty for a selector-requiring verb.

- [ ] **Step 4: Update README + version (if releasing)**

This is optional and only if cutting a release: bump `pluginVersion` in `gradle.properties` and note the bar in `README.md` / the changelog. Not required for the feature to be complete.

---

## Notes for the implementer

- **No test source set** (per `CLAUDE.md`): there is no `src/test/kotlin`. Do not add unit tests or invent test commands; the compile step + manual checklist are the verification gates. `DbtCommandBuilder` is intentionally pure so a future test set could cover it.
- **Three-language code-intel registration in `plugin.xml`** is unrelated to this change — leave it alone.
- **`untilBuild` stays empty** — do not bump it.
- **Path normalization** (`.replace('\\','/')`) is not touched here.
- If `ProfilesParser.getDefaultTarget()` or `invalidateCache()` signatures differ from what Task 5 assumes, mirror exactly what the pre-change `DbtRunnerTab` did (this code is moved, not invented).
