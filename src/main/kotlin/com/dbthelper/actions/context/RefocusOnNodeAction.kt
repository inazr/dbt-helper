package com.dbthelper.actions.context

import com.dbthelper.toolwindow.LineageTab
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class RefocusOnNodeAction(
    private val lineageTab: LineageTab,
    private val nodeId: String
) : AnAction("Refocus on this node") {
    override fun actionPerformed(e: AnActionEvent) {
        lineageTab.refocusOnNode(nodeId)
    }
}
