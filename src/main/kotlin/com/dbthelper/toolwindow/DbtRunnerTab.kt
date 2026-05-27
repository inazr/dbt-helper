package com.dbthelper.toolwindow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.text.DefaultCaret

/**
 * Log surface for dbt command output. All controls live in DbtActionBar;
 * DbtMainPanel drives this tab via appendLine/clear and owns run-state.
 */
class DbtRunnerTab(
    project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    private val logArea = JTextArea().apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, 12).takeIf { it.family == "JetBrains Mono" }
            ?: Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.ALWAYS_UPDATE
    }

    init {
        Disposer.register(parentDisposable, this)
        add(JBScrollPane(logArea), BorderLayout.CENTER)
    }

    /** Append a line to the log (safe to call from any thread). */
    fun appendLine(line: String) {
        ApplicationManager.getApplication().invokeLater { logArea.append(line + "\n") }
    }

    /** Clear the log (safe to call from any thread). */
    fun clear() {
        ApplicationManager.getApplication().invokeLater { logArea.text = "" }
    }

    /**
     * Format `dbt show --output json` output as an ASCII table, or null if the
     * output doesn't contain a parseable JSON result.
     */
    fun formatPreviewTable(output: String): String? {
        try {
            val clean = output.replace(Regex("\\[[0-9;]*[A-Za-z]"), "")
            val jsonStart = clean.indexOfFirst { it == '{' || it == '[' }
            if (jsonStart < 0) return null
            val jsonStr = clean.substring(jsonStart)
            val rootNode = mapper.readTree(jsonStr)

            val rowsNode: JsonNode = when {
                rootNode.isObject && rootNode.has("show") -> rootNode.get("show")
                rootNode.isArray -> rootNode
                else -> return null
            }
            if (!rowsNode.isArray || rowsNode.size() == 0) return "(0 rows)"

            val firstRow = rowsNode[0]
            val columns = firstRow.fieldNames().asSequence().toList()
            val widths = columns.map { col ->
                val dataMax = (0 until rowsNode.size()).maxOf { i ->
                    nodeToString(rowsNode[i].get(col)).length
                }
                maxOf(col.length, dataMax).coerceAtMost(60)
            }

            val sb = StringBuilder()
            val separator = widths.joinToString("-+-", "+-", "-+") { "-".repeat(it) }
            sb.appendLine(separator)
            sb.appendLine(columns.mapIndexed { i, col -> col.padEnd(widths[i]) }.joinToString(" | ", "| ", " |"))
            sb.appendLine(separator)
            for (i in 0 until rowsNode.size()) {
                val row = rowsNode[i]
                val line = columns.mapIndexed { j, col ->
                    val value = nodeToString(row.get(col))
                    if (value.length > widths[j]) value.take(widths[j] - 3) + "..." else value.padEnd(widths[j])
                }.joinToString(" | ", "| ", " |")
                sb.appendLine(line)
            }
            sb.appendLine(separator)
            sb.appendLine("(${rowsNode.size()} rows)")
            return sb.toString()
        } catch (_: Exception) {
            return null
        }
    }

    private fun nodeToString(node: JsonNode?): String {
        if (node == null || node.isNull) return "null"
        if (node.isTextual) return node.asText()
        if (node.isNumber) return node.numberValue().toString()
        if (node.isBoolean) return node.asBoolean().toString()
        return node.toString()
    }

    override fun dispose() {}
}
