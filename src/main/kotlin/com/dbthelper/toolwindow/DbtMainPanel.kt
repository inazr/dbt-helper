package com.dbthelper.toolwindow

import com.dbthelper.actions.DbtCommandRunner
import com.dbthelper.actions.DbtCommandSpec
import com.dbthelper.actions.DbtVerb
import com.dbthelper.core.DbtProjectLocator
import com.dbthelper.core.DbtRunState
import com.dbthelper.core.DbtSelectorParser
import com.dbthelper.core.ManifestService
import com.dbthelper.core.ManifestUpdateListener
import com.dbthelper.core.ProfilesParser
import com.dbthelper.core.model.ManifestIndex
import com.dbthelper.listeners.CurrentModelListener
import com.dbthelper.settings.DbtHelperSettings
import com.dbthelper.settings.SettingsChangeListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JPanel

/**
 * Hosts the shared action bar above the Lineage + Runner tabs and coordinates
 * between them: runs commands, owns run-state, auto-fills the selector from the
 * editor, and drives the lineage graph from the selector (honoring graph operators).
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
    @Volatile private var userStopped = false
    private var runGeneration = 0

    private val timestampRegex = Regex("^\\d{2}:\\d{2}:\\d{2}.*")
    private val statusVerbs = setOf(DbtVerb.RUN, DbtVerb.BUILD, DbtVerb.TEST)

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

        // Kick off the initial manifest parse on open; onManifestUpdated will auto-fill the selector.
        ManifestService.getInstance(project).reparse()
    }

    private fun autoFillFromFile(file: VirtualFile) {
        val service = ManifestService.getInstance(project)
        val modelId = service.findCurrentModelId(file) ?: return
        val node = service.getIndex().nodes[modelId] ?: return
        actionBar.setSelector(node.name)
    }

    private fun driveLineage(selector: String) {
        val focus = DbtSelectorParser.parse(selector) ?: return
        lineageTab.focusModel(focus.modelName, focus.upstreamDepth, focus.downstreamDepth)
    }

    private fun startCommand(spec: DbtCommandSpec) {
        if (isRunning) return
        val generation = ++runGeneration
        userStopped = false
        isRunning = true
        DbtRunState.getInstance(project).setRunning(true)
        actionBar.setRunning(true)
        tabs.selectedComponent = runnerTab
        runnerTab.clear()
        if (spec.verb in statusVerbs) lineageTab.beginRunStatus()

        val runner = DbtCommandRunner(project)
        runner.run(spec, object : DbtCommandRunner.OutputListener {
            override fun onProcessStarted(process: Process) { currentProcess = process }

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

            override fun onFinished(result: DbtCommandRunner.RunResult) {
                ApplicationManager.getApplication().invokeLater {
                    if (generation != runGeneration) return@invokeLater
                    isRunning = false
                    DbtRunState.getInstance(project).setRunning(false)
                    currentProcess = null
                    actionBar.setRunning(false)
                    if (spec.verb in statusVerbs) lineageTab.applyRunResults()

                    if (spec.verb == DbtVerb.PREVIEW && result.success) {
                        val table = runnerTab.formatPreviewTable(result.output)
                        runnerTab.appendLine("\n" + (table ?: "(no data returned)"))
                    }

                    if (spec.verb == DbtVerb.COMPILE && result.success) {
                        copyCompiledToClipboard(spec)
                    }

                    if (!userStopped) {
                        val label = "dbt ${spec.verb.display.lowercase()}"
                        if (result.success) {
                            // COMPILE's notification is owned by copyCompiledToClipboard above
                            // (it reports the specific clipboard outcome). Suppress the generic
                            // "completed" toast so the two notifications don't pile up — or worse,
                            // contradict each other when no compiled file was found.
                            if (spec.verb != DbtVerb.COMPILE) {
                                notify("$label completed", NotificationType.INFORMATION)
                            }
                        } else if (result.exitCode != -1) {
                            notify("$label failed (exit code ${result.exitCode})", NotificationType.ERROR)
                        }
                    }
                }
            }
        })
    }

    private fun stopCommand() {
        runGeneration++
        userStopped = true
        currentProcess?.destroyForcibly()
        currentProcess = null
        runnerTab.appendLine("\n--- Process terminated ---")
        isRunning = false
        DbtRunState.getInstance(project).setRunning(false)
        actionBar.setRunning(false)
    }

    /**
     * After a successful `dbt compile`, read the freshly written compiled SQL
     * from target/compiled/<project>/… and put it on the clipboard. For a
     * selector that resolves to a single model, copy that model; otherwise
     * concatenate every model that has a compiled file, each under a
     * `-- <name>` header.
     */
    private fun copyCompiledToClipboard(spec: DbtCommandSpec) {
        val root = DbtProjectLocator(project).findProjectRoot()
        val projectName = ProfilesParser.getInstance(project).getProjectName()
        if (root == null || projectName == null) {
            notify("Compile succeeded but the compiled SQL location could not be resolved", NotificationType.WARNING)
            return
        }
        val compiledDir = File(root.path, "target/compiled/$projectName")
        val index = ManifestService.getInstance(project).getIndex()
        val baseName = DbtSelectorParser.parse(spec.selector)?.modelName
        val singleNode = baseName?.let { name ->
            index.nodes.values
                .filter { it.resourceType == "model" && it.name == name }
                .singleOrNull()
        }

        val sql: String?
        val message: String
        if (singleNode != null) {
            val file = File(compiledDir, singleNode.originalFilePath)
            sql = if (file.isFile) file.readText() else null
            message = "Copied compiled SQL for ${singleNode.name} to clipboard"
        } else {
            val blocks = StringBuilder()
            var count = 0
            index.nodes.values
                .filter { it.resourceType == "model" }
                .forEach { node ->
                    val file = File(compiledDir, node.originalFilePath)
                    if (file.isFile) {
                        blocks.append("-- ${node.name}\n").append(file.readText().trim()).append("\n\n")
                        count++
                    }
                }
            sql = if (count > 0) blocks.toString() else null
            message = "Copied compiled SQL for $count models to clipboard"
        }

        if (sql == null) {
            notify("Compile succeeded but no compiled SQL file was found", NotificationType.WARNING)
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(sql))
        notify(message, NotificationType.INFORMATION)
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("YADT")
            .createNotification(content, type)
            .notify(project)
        if (DbtHelperSettings.getInstance(project).state.enableSystemNotifications) {
            val title = if (type == NotificationType.ERROR) "dbt Error" else "YADT"
            com.intellij.ui.SystemNotifications.getInstance().notify("yadt", title, content)
        }
    }

    override fun dispose() {}
}
