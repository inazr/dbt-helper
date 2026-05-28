package com.dbthelper.actions.context

import com.dbthelper.toolwindow.DbtActionBar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class GoSelectorAction(
    private val actionBar: DbtActionBar,
    private val selector: String,
    label: String
) : AnAction(label) {
    override fun actionPerformed(e: AnActionEvent) {
        val parent = e.inputEvent?.component ?: return
        actionBar.promptToCancelAndRetry(parent, selector)
    }
}
