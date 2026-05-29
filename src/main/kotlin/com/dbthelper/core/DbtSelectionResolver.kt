package com.dbthelper.core

import com.dbthelper.core.model.ManifestIndex
import com.intellij.openapi.project.Project

/**
 * Resolves a dbt selector to a set of node unique-ids in two ways:
 *  - [resolveLive]: in-process against the in-memory manifest, for the common
 *    manifest-derivable methods. Updates as the user types.
 *  - [resolveViaCli]: authoritative `dbt ls`, for any selector dbt understands.
 *
 * Graph operators (`+`, `N+`, `+N`) are applied here during resolution, so the
 * renderer only ever receives a flat, fully-expanded id set.
 *
 * [project] may be null in unit tests that only exercise [resolveLive].
 */
class DbtSelectionResolver(private val project: Project?) {

    /**
     * Resolve [selector] against [index] without touching the CLI.
     * Returns the matched ids (possibly empty = "understood, nothing matched"),
     * or null when the selector uses syntax this resolver does not implement
     * (the caller then leaves the graph untouched until the dbt ls path runs).
     */
    fun resolveLive(index: ManifestIndex, selector: String): Set<String>? {
        val tokens = selector.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val result = LinkedHashSet<String>()
        for (token in tokens) {
            if (token.contains(',')) return null // intersections: CLI only
            val ids = resolveToken(index, token) ?: return null
            result += ids
        }
        return result
    }

    private val upstreamOpRegex = Regex("^(\\d*)\\+")
    private val downstreamOpRegex = Regex("\\+(\\d*)$")

    /** Strip +/N+ operators, resolve the inner method/name, then expand by operators. */
    private fun resolveToken(index: ManifestIndex, token: String): Set<String>? {
        var s = token
        var up = 0
        var down = 0
        var hasOp = false

        upstreamOpRegex.find(s)?.let { m ->
            up = m.groupValues[1].toIntOrNull() ?: DbtSelectorParser.UNLIMITED
            s = s.substring(m.value.length)
            hasOp = true
        }
        downstreamOpRegex.find(s)?.let { m ->
            down = m.groupValues[1].toIntOrNull() ?: DbtSelectorParser.UNLIMITED
            s = s.substring(0, s.length - m.value.length)
            hasOp = true
        }
        if (s.isEmpty()) return null // bare "+" / empty token (e.g. user mid-type) — let caller fall through

        val base = resolveMethod(index, s) ?: return null
        if (!hasOp) return base

        val out = LinkedHashSet(base)
        for (id in base) {
            if (up > 0) collectReachable(index, id, up, upstream = true, into = out)
            if (down > 0) collectReachable(index, id, down, upstream = false, into = out)
        }
        return out
    }

    /** Resolve a single method:value (or bare name / glob). Null = method unknown. */
    private fun resolveMethod(index: ManifestIndex, s: String): Set<String>? {
        val colon = s.indexOf(':')
        if (colon >= 0) {
            val method = s.substring(0, colon)
            val value = s.substring(colon + 1)
            if (value.isEmpty()) return null
            // Note: a "+" inside a method value is treated as part of the value, not an operator.
            return when (method) {
                "tag" -> byTag(index, value)
                "source" -> bySource(index, value)
                "path" -> byPath(index, value)
                "fqn" -> byFqn(index, value)
                else -> null
            }
        }
        if (s.startsWith('@')) return null // @ ancestors-of-descendants: CLI only
        return byName(index, s)
    }

    private fun byTag(index: ManifestIndex, tag: String): Set<String> {
        val out = LinkedHashSet<String>()
        index.nodes.values.filter { tag in it.tags }.forEach { out += it.uniqueId }
        index.sources.values.filter { tag in it.tags }.forEach { out += it.uniqueId }
        index.exposures.values.filter { tag in it.tags }.forEach { out += it.uniqueId }
        return out
    }

    private fun bySource(index: ManifestIndex, value: String): Set<String> {
        val parts = value.split('.', limit = 2)
        val srcName = parts[0]
        val table = parts.getOrNull(1)
        return index.sources.values
            .filter { it.sourceName == srcName && (table == null || it.name == table) }
            .map { it.uniqueId }
            .toSet()
    }

    private fun byPath(index: ManifestIndex, value: String): Set<String> {
        val prefix = value.replace('\\', '/').trimEnd('/')
        val out = LinkedHashSet<String>()
        fun match(id: String, path: String) {
            val p = path.replace('\\', '/')
            if (p == prefix || p.startsWith("$prefix/")) out += id
        }
        index.nodes.values.forEach { match(it.uniqueId, it.originalFilePath) }
        index.sources.values.forEach { match(it.uniqueId, it.originalFilePath) }
        index.exposures.values.forEach { match(it.uniqueId, it.originalFilePath) }
        return out
    }

    private fun byFqn(index: ManifestIndex, value: String): Set<String> {
        val rx = globToRegex(value)
        return index.nodes.values
            .filter { n -> n.fqn.isNotEmpty() && (rx.matches(n.fqn.joinToString(".")) || rx.matches(n.fqn.last())) }
            .map { it.uniqueId }
            .toSet()
    }

    private fun byName(index: ManifestIndex, value: String): Set<String> {
        val rx = globToRegex(value)
        val out = LinkedHashSet<String>()
        index.nodes.values.filter { rx.matches(it.name) }.forEach { out += it.uniqueId }
        index.sources.values
            .filter { rx.matches(it.name) || rx.matches("${it.sourceName}.${it.name}") }
            .forEach { out += it.uniqueId }
        index.exposures.values.filter { rx.matches(it.name) }.forEach { out += it.uniqueId }
        return out
    }

    /** Translate a dbt-style glob (`*` any, `?` one char) to an anchored Regex. */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        return Regex("^$sb$")
    }

    /** BFS from [startId] up to [depth] hops along parents or children, into [into]. */
    private fun collectReachable(
        index: ManifestIndex,
        startId: String,
        depth: Int,
        upstream: Boolean,
        into: MutableSet<String>
    ) {
        if (depth <= 0) return
        val visited = hashSetOf(startId)
        var frontier = listOf(startId)
        var remaining = depth
        while (remaining > 0 && frontier.isNotEmpty()) {
            val next = ArrayList<String>()
            for (id in frontier) {
                val neighbours = if (upstream) index.getUpstream(id) else index.getDownstream(id)
                for (n in neighbours) if (visited.add(n)) { into.add(n); next += n }
            }
            frontier = next
            remaining--
        }
    }
}
