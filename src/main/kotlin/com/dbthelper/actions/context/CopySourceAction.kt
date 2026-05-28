package com.dbthelper.actions.context

import com.dbthelper.core.ManifestService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

class CopySourceAction(private val project: Project, private val uniqueId: String) : AnAction("Copy source(...)") {
    override fun actionPerformed(e: AnActionEvent) {
        val src = ManifestService.getInstance(project).cachedIndex.sources[uniqueId] ?: return
        val text = "source('${src.sourceName}', '${src.name}')"
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
}
