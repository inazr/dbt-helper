package com.dbthelper.actions

import java.time.Instant

enum class RunStatus(val wire: String) {
    SUCCESS("success"),
    ERROR("error"),
    WARN("warn"),
    SKIPPED("skipped"),
    QUEUED("queued"),
    RUNNING("running");

    companion object {
        fun fromDbtStatus(raw: String): RunStatus = when (raw.lowercase()) {
            "success", "pass" -> SUCCESS
            "error", "fail", "runtime error" -> ERROR
            "warn" -> WARN
            "skipped" -> SKIPPED
            else -> ERROR
        }
    }
}

data class RunResult(
    val uniqueId: String,
    val status: RunStatus,
    val message: String?,
    val executionTime: Double,
    val startedAt: Instant?,
    val failures: Int?
)
