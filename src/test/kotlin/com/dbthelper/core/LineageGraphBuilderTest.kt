package com.dbthelper.core

import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.ManifestIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LineageGraphBuilderTest {

    // a -> b -> c -> d   (linear chain)
    private fun index(): ManifestIndex {
        fun node(id: String, name: String) = DbtNode(
            uniqueId = id, name = name, resourceType = "model",
            packageName = "proj", originalFilePath = "models/$name.sql"
        )
        val nodes = listOf("a", "b", "c", "d").associate { "model.proj.$it" to node("model.proj.$it", it) }
        return ManifestIndex(
            nodes = nodes,
            parentMap = mapOf(
                "model.proj.b" to listOf("model.proj.a"),
                "model.proj.c" to listOf("model.proj.b"),
                "model.proj.d" to listOf("model.proj.c")
            ),
            childMap = mapOf(
                "model.proj.a" to listOf("model.proj.b"),
                "model.proj.b" to listOf("model.proj.c"),
                "model.proj.c" to listOf("model.proj.d")
            )
        )
    }

    private val builder get() = LineageGraphBuilder(index())

    @Test
    fun `matched nodes render as full cards, all marked isCurrent`() {
        val g = builder.buildForSelection(setOf("model.proj.b", "model.proj.c"))
        val real = g.nodes.filter { it.resourceType != "stub" }
        assertEquals(setOf("model.proj.b", "model.proj.c"), real.map { it.id }.toSet())
        assertTrue(real.all { it.isCurrent })
    }

    @Test
    fun `edges between matched nodes are drawn`() {
        val g = builder.buildForSelection(setOf("model.proj.b", "model.proj.c"))
        assertTrue(g.edges.any { it.fromNodeId == "model.proj.b" && it.toNodeId == "model.proj.c" })
    }

    @Test
    fun `connections to non-matched nodes collapse into plus-N-more stubs`() {
        // b's upstream (a) and c's downstream (d) are NOT in the set -> one stub each.
        val g = builder.buildForSelection(setOf("model.proj.b", "model.proj.c"))
        val stubs = g.nodes.filter { it.resourceType == "stub" }
        assertEquals(2, stubs.size)
        assertEquals(
            setOf("upstream" to "model.proj.b", "downstream" to "model.proj.c"),
            stubs.map { it.stubDirection to it.boundaryNodeId }.toSet()
        )
        assertTrue(stubs.all { it.name == "+ 1 more" })
    }

    @Test
    fun `empty selection yields an empty graph`() {
        val g = builder.buildForSelection(emptySet())
        assertTrue(g.nodes.isEmpty())
        assertTrue(g.edges.isEmpty())
    }

    @Test
    fun `selection of only nonexistent ids yields an empty graph`() {
        val g = builder.buildForSelection(setOf("model.proj.ghost", "model.proj.missing"))
        assertTrue(g.nodes.isEmpty())
        assertTrue(g.edges.isEmpty())
        assertEquals("", g.currentNodeId)
    }

    @Test
    fun `expanding a boundary reveals its hidden neighbor`() {
        // Expand around c -> its downstream d becomes visible (no more downstream stub on c).
        val g = builder.buildForSelection(setOf("model.proj.b", "model.proj.c"), setOf("model.proj.c"))
        val real = g.nodes.filter { it.resourceType != "stub" }.map { it.id }.toSet()
        assertTrue(real.contains("model.proj.d"))
        assertTrue(g.nodes.none { it.resourceType == "stub" && it.boundaryNodeId == "model.proj.c" && it.stubDirection == "downstream" })
    }
}
