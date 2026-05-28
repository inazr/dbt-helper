package com.dbthelper.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path

data class SourceFreshness(
    val status: String, // "pass" | "warn" | "error"
    val message: String?
)

class SourcesFreshnessParser {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    fun parseFile(path: Path): Map<String, SourceFreshness> {
        if (!Files.exists(path)) return emptyMap()
        return try {
            parseString(Files.readString(path))
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun parseString(json: String): Map<String, SourceFreshness> = try {
        val root: JsonNode = mapper.readTree(json)
        val results = root.path("results")
        if (!results.isArray) emptyMap()
        else results.mapNotNull { node ->
            val uniqueId = node.path("unique_id").asText(null) ?: return@mapNotNull null
            val status = node.path("status").asText("pass").lowercase()
            val message: String? = run {
                val criteria = node.path("criteria")
                val warnAfter = criteria.path("warn_after").let { c ->
                    val cnt = c.path("count").asInt(0)
                    val period = c.path("period").asText("")
                    if (cnt > 0 && period.isNotEmpty()) "warn after $cnt $period" else null
                }
                warnAfter
            }
            uniqueId to SourceFreshness(status, message)
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }
}
