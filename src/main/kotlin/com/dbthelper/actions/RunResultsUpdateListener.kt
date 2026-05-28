package com.dbthelper.actions

import com.intellij.util.messages.Topic

fun interface RunResultsUpdateListener {
    fun onRunResultsUpdated(results: Map<String, RunResult>)

    companion object {
        val TOPIC: Topic<RunResultsUpdateListener> =
            Topic.create("YADT Run Results Updated", RunResultsUpdateListener::class.java)
    }
}
