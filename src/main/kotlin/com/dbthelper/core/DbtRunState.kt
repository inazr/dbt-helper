package com.dbthelper.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether a foreground dbt command (Runner tab / regenerate-docs) is
 * running, so background helpers like auto-parse can avoid launching a
 * competing dbt process against the same project.
 */
@Service(Service.Level.PROJECT)
class DbtRunState {
    private val running = AtomicBoolean(false)

    fun setRunning(value: Boolean) = running.set(value)
    fun isRunning(): Boolean = running.get()

    companion object {
        fun getInstance(project: Project): DbtRunState = project.service()
    }
}
