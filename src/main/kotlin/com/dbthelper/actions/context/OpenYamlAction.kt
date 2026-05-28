package com.dbthelper.actions.context

import com.dbthelper.toolwindow.LineageTab
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

class OpenYamlAction(
    private val project: Project,
    private val lineageTab: LineageTab,
    private val nodeId: String
) : AnAction("Open YAML") {
    override fun actionPerformed(e: AnActionEvent) {
        lineageTab.openFileForNode(nodeId, preferYaml = true)
    }
}
