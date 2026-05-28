package com.dbthelper.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchQueryParserTest {

    @Test
    fun `bare words become bare tokens`() {
        val tokens = SearchQueryParser.parse("orders customer")
        assertEquals(2, tokens.size)
        assertEquals(SearchToken.Bare("orders"), tokens[0])
        assertEquals(SearchToken.Bare("customer"), tokens[1])
    }

    @Test
    fun `prefixed tokens become typed tokens`() {
        val tokens = SearchQueryParser.parse("col:customer_id tag:pii")
        assertEquals(SearchToken.Prefixed("col", "customer_id"), tokens[0])
        assertEquals(SearchToken.Prefixed("tag", "pii"), tokens[1])
    }

    @Test
    fun `multiple col tokens are kept as separate prefixed tokens`() {
        val tokens = SearchQueryParser.parse("col:a col:b")
        assertEquals(2, tokens.size)
        assertTrue(tokens.all { it is SearchToken.Prefixed && (it as SearchToken.Prefixed).key == "col" })
    }

    @Test
    fun `empty value after colon is treated as bare`() {
        val tokens = SearchQueryParser.parse("col:")
        assertEquals(SearchToken.Bare("col:"), tokens[0])
    }

    @Test
    fun `case is normalized on prefix only, not on value`() {
        val tokens = SearchQueryParser.parse("COL:Customer_ID")
        assertEquals(SearchToken.Prefixed("col", "Customer_ID"), tokens[0])
    }

    @Test
    fun `unknown prefixes are treated as bare`() {
        val tokens = SearchQueryParser.parse("unknown:value")
        assertEquals(SearchToken.Bare("unknown:value"), tokens[0])
    }

    @Test
    fun `extra whitespace between tokens is ignored`() {
        val tokens = SearchQueryParser.parse("   col:a    tag:b  ")
        assertEquals(2, tokens.size)
    }
}
