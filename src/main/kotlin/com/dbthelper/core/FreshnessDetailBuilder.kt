package com.dbthelper.core

import com.dbthelper.core.model.ManifestIndex

object FreshnessDetailBuilder {

    fun build(
        nodeId: String,
        index: ManifestIndex,
        freshness: Map<String, SourceFreshness>,
        sourcesJsonAvailable: Boolean
    ): Map<String, Any?>? {
        val source = index.sources[nodeId] ?: return null
        val result = freshness[nodeId]
        val status = result?.status ?: if (sourcesJsonAvailable) "no_result" else "no_data"

        return mapOf(
            "id" to source.uniqueId,
            "name" to "${source.sourceName}.${source.name}",
            "sourceName" to source.sourceName,
            "relation" to listOfNotNull(source.database, source.schema, source.identifier ?: source.name)
                .joinToString("."),
            "status" to status,
            "message" to result?.message,
            "loadedAtField" to source.loadedAtField,
            "warnAfter" to source.freshnessWarnAfter,
            "errorAfter" to source.freshnessErrorAfter,
            "filePath" to source.originalFilePath,
            "sourcesJsonAvailable" to sourcesJsonAvailable
        )
    }
}
