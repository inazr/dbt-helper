package com.dbthelper.listeners

import com.dbthelper.actions.DbtCommandRunner
import com.dbthelper.actions.DbtEngine
import com.dbthelper.core.DbtProjectLocator
import com.dbthelper.core.DbtRunState
import com.dbthelper.core.toUnixPath
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer

/**
 * Runs `dbt parse` in the background shortly after a project-owned .sql/.yml
 * file is saved, so target/manifest.json regenerates and lineage/code-intel
 * stay current. The resulting manifest write is picked up by
 * ManifestFileWatcher, which reparses the in-memory index.
 *
 * Registered as a <projectListeners> BulkFileListener (same as
 * ManifestFileWatcher). Debounced; never runs while a foreground Runner command
 * is active; single-flight; silent on failure (no notification spam).
 */
class AutoParseOnSaveListener(private val project: Project) : BulkFileListener {

    private val logger = Logger.getInstance(AutoParseOnSaveListener::class.java)
    private val parsing = AtomicBoolean(false)
    @Volatile private var rearm = false

    private val debounce = Timer(1500) { _ -> maybeParse() }.apply { isRepeats = false }

    override fun after(events: List<VFileEvent>) {
        if (!DbtHelperSettings.getInstance(project).state.autoParseOnSave) return
        val root = DbtProjectLocator(project).findProjectRoot()?.path ?: return
        val relevant = events.any { event ->
            event is VFileContentChangeEvent && isRelevant(event.path, root)
        }
        if (relevant) debounce.restart()
    }

    private fun isRelevant(path: String, root: String): Boolean {
        val norm = path.toUnixPath()
        if (!norm.startsWith(root.toUnixPath())) return false
        if ("/target/" in norm) return false // dbt's own outputs — never trigger on these
        return norm.endsWith(".sql") || norm.endsWith(".yml")
    }

    private fun maybeParse() {
        val settings = DbtHelperSettings.getInstance(project)
        if (!settings.state.autoParseOnSave) return

        // detectEngine() shells out to `dbt --version` — for the dbt Cloud CLI that
        // is a network round-trip. The Timer's ActionListener fires on the EDT, so
        // hop to a pooled thread before doing any blocking I/O.
        ApplicationManager.getApplication().executeOnPooledThread {
            val runner = DbtCommandRunner(project)
            if (runner.detectEngine() == DbtEngine.CLOUD_CLI && !settings.state.autoParseOnCloudCli) return@executeOnPooledThread

            if (DbtRunState.getInstance(project).isRunning()) return@executeOnPooledThread // don't fight a manual run

            if (!parsing.compareAndSet(false, true)) { rearm = true; return@executeOnPooledThread } // single-flight

            val root = DbtProjectLocator(project).findProjectRoot()?.path
            if (root == null) { parsing.set(false); return@executeOnPooledThread }
            val exe = runner.findDbtExecutable()

            runner.runCommand(listOf(exe, "parse"), File(root), object : DbtCommandRunner.OutputListener {
                override fun onLine(line: String) {} // silent — do not touch the Runner log
                override fun onFinished(result: DbtCommandRunner.RunResult) {
                    if (!result.success) {
                        logger.debug("auto dbt parse failed (exit ${result.exitCode}); keeping last good manifest")
                    }
                    parsing.set(false)
                    // Timer.restart() delegates to EventQueue.invokeLater, safe off-EDT.
                    if (rearm) { rearm = false; debounce.restart() }
                }
            })
        }
    }
}
