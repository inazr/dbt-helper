package com.dbthelper.core

sealed class SearchToken {
    data class Bare(val value: String) : SearchToken()
    data class Prefixed(val key: String, val value: String) : SearchToken()
}

object SearchQueryParser {
    private val KNOWN_PREFIXES = setOf("col", "tag", "mat", "schema", "type", "pkg")

    fun parse(query: String): List<SearchToken> {
        if (query.isBlank()) return emptyList()
        return query.trim().split(Regex("\\s+")).mapNotNull { raw ->
            val colon = raw.indexOf(':')
            if (colon <= 0 || colon == raw.lastIndex) {
                SearchToken.Bare(raw)
            } else {
                val key = raw.substring(0, colon).lowercase()
                val value = raw.substring(colon + 1)
                if (key in KNOWN_PREFIXES) SearchToken.Prefixed(key, value)
                else SearchToken.Bare(raw)
            }
        }
    }
}
