package com.dbthelper.core

/**
 * Parses the subset of dbt graph-operator selector syntax we use to drive the
 * lineage graph: an optional upstream operator (`+` or `N+`), a single model
 * name, and an optional downstream operator (`+` or `+N`).
 *
 * Examples:
 *  - `my_model`      → Focus("my_model", null, null)        — plain name, caller uses default depth
 *  - `+my_model`     → Focus("my_model", UNLIMITED, 0)      — all ancestors, no descendants
 *  - `my_model+`     → Focus("my_model", 0, UNLIMITED)
 *  - `+my_model+`    → Focus("my_model", UNLIMITED, UNLIMITED)
 *  - `2+my_model`    → Focus("my_model", 2, 0)
 *  - `my_model+3`    → Focus("my_model", 0, 3)
 *  - `2+my_model+3`  → Focus("my_model", 2, 3)
 *
 * Anything outside this grammar (wildcards, unions/intersections, `tag:`,
 * `path:`, `@`, dotted names, …) returns null — the caller leaves the graph
 * untouched rather than guessing.
 */
object DbtSelectorParser {

    /**
     * Depth used for a bare `+` operator ("no limit"). Deliberately a large but
     * finite value — well beyond any real dbt DAG height — so downstream depth
     * arithmetic in the graph builder can't overflow.
     */
    const val UNLIMITED: Int = 1000

    /** A resolved selector focus. A null depth means "use the caller's default". */
    data class Focus(val modelName: String, val upstreamDepth: Int?, val downstreamDepth: Int?)

    // (optional "N+" prefix)(model name)(optional "+N" suffix)
    private val GRAPH = Regex("^(?:(\\d*)\\+)?([A-Za-z0-9_]+)(?:\\+(\\d*))?$")

    fun parse(selector: String): Focus? {
        val s = selector.trim()
        if (s.isEmpty()) return null
        val match = GRAPH.matchEntire(s) ?: return null

        val upGroup = match.groups[1]    // present iff a leading "+" operator matched
        val name = match.groupValues[2]
        val downGroup = match.groups[3]  // present iff a trailing "+" operator matched

        val hasUp = upGroup != null
        val hasDown = downGroup != null

        // Plain name with no operators: let the caller decide depth (settings default).
        if (!hasUp && !hasDown) return Focus(name, null, null)

        // With operators, the side that has no operator is excluded (depth 0), and a
        // bare "+" (empty digits) means unlimited.
        val up = if (hasUp) upGroup!!.value.toIntOrNull() ?: UNLIMITED else 0
        val down = if (hasDown) downGroup!!.value.toIntOrNull() ?: UNLIMITED else 0
        return Focus(name, up, down)
    }
}
