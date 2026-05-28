package com.dbthelper.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class RunResultsWatcherStarter : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(RunResultsWatcher::class.java).start()
    }
}
