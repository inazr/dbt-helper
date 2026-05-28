package com.dbthelper.actions.context

import com.dbthelper.actions.DbtCommandSpec
import com.dbthelper.actions.DbtVerb
import com.dbthelper.settings.DbtHelperSettings
import com.dbthelper.toolwindow.DbtActionBar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

class ShowPreviewRowsAction(
    private val project: Project,
    private val actionBar: DbtActionBar,
    private val nodeName: String
) : AnAction("Show preview rows") {
    override fun actionPerformed(e: AnActionEvent) {
        val limit = DbtHelperSettings.getInstance(project).state.previewRowLimit
        val spec = DbtCommandSpec(
            verb = DbtVerb.PREVIEW,
            selector = nodeName,
            target = actionBar.currentTarget(),
            toggleFlags = emptyList(),
            extraArgs = "",
            previewLimit = limit
        )
        actionBar.runSpec(spec)
    }
}
