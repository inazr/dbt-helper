package com.dbthelper.actions.context

import com.dbthelper.toolwindow.DbtActionBar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class CustomSelectorAction(
    private val actionBar: DbtActionBar,
    private val prefill: String
) : AnAction("Custom selector…") {
    override fun actionPerformed(e: AnActionEvent) {
        val parent = e.inputEvent?.component ?: return
        val sel = Messages.showInputDialog(
            parent,
            "dbt selector (e.g. +my_model+ or tag:pii):",
            "Run with custom selector",
            Messages.getQuestionIcon(),
            prefill,
            null
        ) ?: return
        if (sel.isBlank()) return
        actionBar.promptToCancelAndRetry(parent, sel)
    }
}
