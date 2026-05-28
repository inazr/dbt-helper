package com.dbthelper.actions

enum class DbtVerb(val display: String) {
    RUN("Run"),
    BUILD("Build"),
    TEST("Test"),
    COMPILE("Compile to clipboard"),
    PREVIEW("Preview"),
    GENERATE_DOCS("Generate Docs");

    /** Verbs that send `--select <selector>`. */
    val usesSelector: Boolean get() = this != GENERATE_DOCS
}

data class DbtCommandSpec(
    val verb: DbtVerb,
    val selector: String,
    val target: String,            // blank = no --target
    val toggleFlags: List<String> = emptyList(), // e.g. ["--full-refresh", "--empty"]
    val extraArgs: String = "",    // raw free-text, e.g. "--threads 8 --vars '{k: v}'"
    val previewLimit: Int
)

/**
 * Single source of truth for the dbt command shown in the action bar's preview
 * field and the args handed to ProcessBuilder. Both methods walk the same
 * when(verb) so display and execution can never drift.
 */
object DbtCommandBuilder {

    fun buildArgs(spec: DbtCommandSpec, dbtExe: String): List<String> =
        buildTokens(spec, dbtExe)

    /** Human-readable command, always prefixed with "dbt" (not the exe path). */
    fun buildDisplay(spec: DbtCommandSpec): String =
        buildTokens(spec, "dbt").joinToString(" ")

    /**
     * Split a raw extra-args string into ProcessBuilder tokens, honoring single
     * and double quotes so `--vars '{k: v}'` becomes ["--vars", "{k: v}"].
     * No shell is involved, so we must split ourselves.
     */
    fun tokenizeArgs(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var hasToken = false
        for (c in raw) {
            when {
                quote != null -> if (c == quote) quote = null else sb.append(c)
                c == '\'' || c == '"' -> { quote = c; hasToken = true }
                c.isWhitespace() -> if (hasToken) { tokens += sb.toString(); sb.clear(); hasToken = false }
                else -> { sb.append(c); hasToken = true }
            }
        }
        if (hasToken) tokens += sb.toString()
        return tokens
    }

    private fun buildTokens(spec: DbtCommandSpec, exe: String): List<String> {
        val sel = spec.selector.trim()
        // Omit --select entirely when the selector is blank.
        val select = if (sel.isNotEmpty()) listOf("--select", sel) else emptyList()
        val cmd = mutableListOf(exe)
        when (spec.verb) {
            DbtVerb.RUN -> cmd += listOf("run") + select
            DbtVerb.BUILD -> cmd += listOf("build") + select
            DbtVerb.TEST -> cmd += listOf("test") + select
            DbtVerb.COMPILE -> cmd += listOf("compile") + select
            DbtVerb.PREVIEW -> cmd += listOf("show") + select +
                listOf("--limit", spec.previewLimit.toString(), "--output", "json")
            DbtVerb.GENERATE_DOCS -> cmd += listOf("docs", "generate")
        }
        cmd += spec.toggleFlags
        cmd += tokenizeArgs(spec.extraArgs)
        if (spec.target.isNotBlank()) cmd += listOf("--target", spec.target)
        return cmd
    }
}
