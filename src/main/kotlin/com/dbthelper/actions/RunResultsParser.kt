package com.dbthelper.actions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class RunResultsParser {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    fun parseFile(path: Path): Map<String, RunResult> {
        if (!Files.exists(path)) return emptyMap()
        return try {
            parseString(Files.readString(path))
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun parseString(json: String): Map<String, RunResult> = try {
        val root: JsonNode = mapper.readTree(json)
        val results = root.path("results")
        if (!results.isArray) emptyMap()
        else results.mapNotNull { node -> toResult(node) }.associateBy { it.uniqueId }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun toResult(node: JsonNode): RunResult? {
        val uniqueId = node.path("unique_id").asText(null) ?: return null
        val statusRaw = node.path("status").asText("error")
        val message = node.path("message").let { if (it.isNull || it.isMissingNode) null else it.asText() }
        val executionTime = node.path("execution_time").asDouble(0.0)
        val failures = node.path("failures").let { if (it.isNull || it.isMissingNode) null else it.asInt() }
        val startedAt = node.path("timing")
            .firstOrNull { it.path("name").asText() == "execute" }
            ?.path("started_at")
            ?.asText(null)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return RunResult(
            uniqueId = uniqueId,
            status = RunStatus.fromDbtStatus(statusRaw),
            message = message,
            executionTime = executionTime,
            startedAt = startedAt,
            failures = failures
        )
    }
}
