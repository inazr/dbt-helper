package com.dbthelper.core

data class SearchHints(
    val lowerName: String,
    val lowerId: String,
    val schemaLower: String,
    val materialization: String,
    val resourceType: String,
    val packageLower: String,
    val columnNamesLower: Set<String>,
    val tagsLower: Set<String>
)

object SearchIndexBuilder {
    fun buildHints(
        uniqueId: String,
        name: String,
        schema: String?,
        materialization: String?,
        resourceType: String,
        packageName: String?,
        columnNames: List<String>,
        tags: List<String>
    ): SearchHints = SearchHints(
        lowerName = name.lowercase(),
        lowerId = uniqueId.lowercase(),
        schemaLower = schema?.lowercase().orEmpty(),
        materialization = materialization?.lowercase().orEmpty(),
        resourceType = resourceType.lowercase(),
        packageLower = packageName?.lowercase().orEmpty(),
        columnNamesLower = columnNames.map { it.lowercase() }.toSet(),
        tagsLower = tags.map { it.lowercase() }.toSet()
    )
}
