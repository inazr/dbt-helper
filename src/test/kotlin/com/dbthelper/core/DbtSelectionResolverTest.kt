package com.dbthelper.core

import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.DbtSource
import com.dbthelper.core.model.ManifestIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DbtSelectionResolverTest {

    // a -> b -> c   (a is upstream of b, b upstream of c)
    private fun index(): ManifestIndex {
        fun node(id: String, name: String, tags: List<String> = emptyList(),
                 path: String = "models/$name.sql", fqn: List<String> = listOf("proj", name)) =
            DbtNode(
                uniqueId = id, name = name, resourceType = "model",
                packageName = "proj", originalFilePath = path, tags = tags, fqn = fqn
            )
        val nodes = mapOf(
            "model.proj.a" to node("model.proj.a", "a", tags = listOf("daily"), path = "models/staging/a.sql"),
            "model.proj.b" to node("model.proj.b", "b", tags = listOf("daily", "pii"), path = "models/marts/b.sql"),
            "model.proj.c" to node("model.proj.c", "c", path = "models/marts/c.sql")
        )
        val sources = mapOf(
            "source.proj.raw.orders" to DbtSource(
                uniqueId = "source.proj.raw.orders", name = "orders", sourceName = "raw",
                packageName = "proj", originalFilePath = "models/sources.yml", tags = listOf("pii")
            )
        )
        return ManifestIndex(
            nodes = nodes,
            sources = sources,
            parentMap = mapOf("model.proj.b" to listOf("model.proj.a"), "model.proj.c" to listOf("model.proj.b")),
            childMap = mapOf("model.proj.a" to listOf("model.proj.b"), "model.proj.b" to listOf("model.proj.c"))
        )
    }

    private val resolver get() = DbtSelectionResolver(null)

    @Test
    fun `tag selects all nodes carrying the tag, across resource types`() {
        assertEquals(
            setOf("model.proj.a", "model.proj.b"),
            resolver.resolveLive(index(), "tag:daily")
        )
        assertEquals(
            setOf("model.proj.b", "source.proj.raw.orders"),
            resolver.resolveLive(index(), "tag:pii")
        )
    }

    @Test
    fun `path selects nodes under the directory prefix`() {
        assertEquals(
            setOf("model.proj.b", "model.proj.c"),
            resolver.resolveLive(index(), "path:models/marts")
        )
    }

    @Test
    fun `source selects by source name and optional table`() {
        assertEquals(setOf("source.proj.raw.orders"), resolver.resolveLive(index(), "source:raw"))
        assertEquals(setOf("source.proj.raw.orders"), resolver.resolveLive(index(), "source:raw.orders"))
        assertEquals(emptySet<String>(), resolver.resolveLive(index(), "source:raw.missing"))
    }

    @Test
    fun `name wildcard globs over node names`() {
        assertEquals(setOf("model.proj.a"), resolver.resolveLive(index(), "a*"))
    }

    @Test
    fun `downstream operator expands the matched set`() {
        // tag:daily = {a, b}; "+1" downstream adds each one's immediate children -> {a,b,c}
        assertEquals(
            setOf("model.proj.a", "model.proj.b", "model.proj.c"),
            resolver.resolveLive(index(), "tag:daily+1")
        )
    }

    @Test
    fun `upstream operator expands the matched set`() {
        // "2+c" = c plus 2 upstream hops -> {c, b, a}
        assertEquals(
            setOf("model.proj.a", "model.proj.b", "model.proj.c"),
            resolver.resolveLive(index(), "2+c")
        )
    }

    @Test
    fun `space separated tokens union`() {
        assertEquals(
            setOf("model.proj.a", "model.proj.c"),
            resolver.resolveLive(index(), "a c")
        )
    }

    @Test
    fun `understood selector with no matches returns empty set, not null`() {
        assertEquals(emptySet<String>(), resolver.resolveLive(index(), "tag:nonexistent"))
    }

    @Test
    fun `unknown method returns null so caller can fall through to dbt ls`() {
        assertNull(resolver.resolveLive(index(), "config.materialized:view"))
        assertNull(resolver.resolveLive(index(), "state:modified"))
        assertNull(resolver.resolveLive(index(), "@a"))
        assertNull(resolver.resolveLive(index(), "a,b")) // intersection not supported live
    }
}
