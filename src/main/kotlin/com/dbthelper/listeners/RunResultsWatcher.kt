package com.dbthelper.listeners

import com.dbthelper.actions.RunResult
import com.dbthelper.actions.RunResultsParser
import com.dbthelper.actions.RunResultsUpdateListener
import com.dbthelper.core.DbtProjectLocator
import com.dbthelper.core.toUnixPath
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class RunResultsWatcher(private val project: Project) {

    private val parser = RunResultsParser()

    @Volatile
    private var lastResults: Map<String, RunResult> = emptyMap()

    fun start() {
        val connection = project.messageBus.connect()
        connection.subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (events.any { isRunResultsFile(it.file) }) reload()
            }
        })
        reload() // initial load
    }

    fun current(): Map<String, RunResult> = lastResults

    private fun isRunResultsFile(file: VirtualFile?): Boolean {
        if (file == null || file.name != "run_results.json") return false
        val rootPath = projectRoot() ?: return false
        val expected = rootPath.toUnixPath().trimEnd('/') + "/target/run_results.json"
        return file.path.toUnixPath() == expected
    }

    private fun projectRoot(): String? {
        val locator = DbtProjectLocator(project)
        return locator.findProjectRoot()?.path
    }

    private fun reload() {
        val rootPath = projectRoot() ?: return
        val path = Paths.get(rootPath, "target", "run_results.json")
        val parsed = parser.parseFile(path)
        lastResults = parsed
        ApplicationManager.getApplication().invokeLater {
            project.messageBus.syncPublisher(RunResultsUpdateListener.TOPIC).onRunResultsUpdated(parsed)
        }
        thisLogger().info("RunResultsWatcher: reloaded ${parsed.size} results from $path")
    }
}
