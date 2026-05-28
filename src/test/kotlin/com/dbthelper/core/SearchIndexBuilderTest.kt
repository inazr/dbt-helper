package com.dbthelper.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchIndexBuilderTest {

    @Test
    fun `builds hints with lowercased fields`() {
        val hints = SearchIndexBuilder.buildHints(
            uniqueId = "model.proj.Customers",
            name = "Customers",
            schema = "DWH",
            materialization = "Table",
            resourceType = "model",
            packageName = "PROJ",
            columnNames = listOf("Customer_Id", "Email"),
            tags = listOf("PII", "Finance")
        )
        assertEquals("customers", hints.lowerName)
        assertEquals("model.proj.customers", hints.lowerId)
        assertEquals("dwh", hints.schemaLower)
        assertEquals("table", hints.materialization)
        assertEquals("proj", hints.packageLower)
        assertEquals(setOf("customer_id", "email"), hints.columnNamesLower)
        assertEquals(setOf("pii", "finance"), hints.tagsLower)
        assertEquals("model", hints.resourceType)
    }

    @Test
    fun `handles null and missing fields by emitting empty values`() {
        val hints = SearchIndexBuilder.buildHints(
            uniqueId = "source.proj.raw.tbl",
            name = "tbl",
            schema = null,
            materialization = null,
            resourceType = "source",
            packageName = null,
            columnNames = emptyList(),
            tags = emptyList()
        )
        assertEquals("", hints.schemaLower)
        assertEquals("", hints.materialization)
        assertEquals("", hints.packageLower)
        assertTrue(hints.columnNamesLower.isEmpty())
        assertTrue(hints.tagsLower.isEmpty())
    }
}
