package com.dbthelper.actions.context

import com.dbthelper.toolwindow.DbtActionBar
import com.dbthelper.toolwindow.LineageTab
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project

object LineageContextActionGroup {

    fun build(
        project: Project,
        lineageTab: LineageTab,
        actionBar: DbtActionBar,
        nodeIds: List<String>,
        names: List<String>,
        resourceTypes: List<String?>
    ): ActionGroup {
        val group = DefaultActionGroup()
        val multi = names.size > 1
        val multiSuffix = if (multi) " (${names.size} selected)" else ""

        val allBuildable = resourceTypes.all { it == "model" || it == "snapshot" || it == "seed" || it == "test" }
        val joined = names.joinToString(" ")

        if (allBuildable) {
            val goGroup = DefaultActionGroup("[RUN]$multiSuffix", true)
            goGroup.add(GoSelectorAction(actionBar, joined, joined))
            goGroup.add(GoSelectorAction(actionBar, plus(joined, up = true, down = false), plus(joined, up = true, down = false)))
            goGroup.add(GoSelectorAction(actionBar, plus(joined, up = false, down = true), plus(joined, up = false, down = true)))
            goGroup.add(GoSelectorAction(actionBar, plus(joined, up = true, down = true), plus(joined, up = true, down = true)))
            goGroup.add(Separator())
            goGroup.add(CustomSelectorAction(actionBar, joined))
            group.add(goGroup)
        }
        if (!multi && resourceTypes.singleOrNull() == "model") {
            group.add(Separator())
            group.add(ShowPreviewRowsAction(project, actionBar, names[0]))
        }
        if (!multi) {
            group.add(RefocusOnNodeAction(lineageTab, nodeIds[0]))
        }
        if (!multi) {
            group.add(Separator())
            val t = resourceTypes.first()
            when (t) {
                "model", "snapshot", "seed" -> {
                    group.add(CopyRefAction(names[0]))
                    group.add(OpenSqlAction(project, lineageTab, nodeIds[0]))
                    group.add(OpenYamlAction(project, lineageTab, nodeIds[0]))
                }
                "source" -> {
                    group.add(CopySourceAction(project, nodeIds[0]))
                    group.add(OpenYamlAction(project, lineageTab, nodeIds[0]))
                }
                "exposure" -> {
                    group.add(OpenYamlAction(project, lineageTab, nodeIds[0]))
                }
            }
        }
        return group
    }

    private fun plus(joined: String, up: Boolean, down: Boolean): String =
        joined.split(' ').joinToString(" ") {
            (if (up) "+" else "") + it + (if (down) "+" else "")
        }
}
