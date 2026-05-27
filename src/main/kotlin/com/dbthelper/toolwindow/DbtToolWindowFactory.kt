package com.dbthelper.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.content.ContentFactory

class DbtToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Hide the bold "YADT" id label the platform renders before the content.
        // DbtMainPanel provides its own action bar at the top.
        toolWindow.component.putClientProperty(ToolWindowContentUi.HIDE_ID_LABEL, "true")

        val mainPanel = DbtMainPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
