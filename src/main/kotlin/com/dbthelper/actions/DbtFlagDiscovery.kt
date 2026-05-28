package com.dbthelper.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers which toggle flags the installed dbt accepts for a given verb by
 * parsing `dbt <subcommand> --help`. We only test for the presence of names we
 * already know are boolean toggles (the allowlist), so there is no fragile
 * column parsing — and the result is automatically version- and
 * command-accurate (e.g. --full-refresh is absent from `dbt test --help`,
 * --empty is absent on dbt < 1.8).
 */
class DbtFlagDiscovery(project: Project) {

    private val runner = DbtCommandRunner(project)

    /** token = what we emit; helpName = substring searched in --help output. */
    data class FlagOption(val token: String, val helpName: String, val label: String, val tooltip: String)

    // exe::subcommand -> available options
    private val cache = ConcurrentHashMap<String, List<FlagOption>>()

    fun invalidate() = cache.clear()

    /**
     * Resolve toggle flags for [verb] off the EDT, then call [onResult] on the
     * EDT. Cached per (exe, subcommand) after the first call.
     */
    fun flagsForAsync(verb: DbtVerb, onResult: (List<FlagOption>) -> Unit) {
        val sub = SUBCOMMAND[verb]
        if (sub == null) { onResult(emptyList()); return }
        val exe = runner.findDbtExecutable()
        val key = "$exe::$sub"
        cache[key]?.let { onResult(it); return }
        Thread {
            val result = discover(exe, sub, verb)
            cache[key] = result
            ApplicationManager.getApplication().invokeLater { onResult(result) }
        }.apply { name = "dbt-flag-discovery"; isDaemon = true; start() }
    }

    private fun discover(exe: String, sub: String, verb: DbtVerb): List<FlagOption> {
        val help = runHelp(exe, sub)
        if (help == null) {
            val fb = FALLBACK[verb] ?: emptySet()
            return ALLOWLIST.filter { it.helpName in fb }
        }
        return ALLOWLIST.filter { help.contains("--${it.helpName}") }
    }

    private fun runHelp(exe: String, sub: String): String? {
        return try {
            val proc = ProcessBuilder(exe, sub, "--help").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() == 0) out else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        val ALLOWLIST = listOf(
            FlagOption("--full-refresh", "full-refresh", "--full-refresh", "Rebuild incremental models from scratch"),
            FlagOption("--fail-fast", "fail-fast", "--fail-fast", "Stop on the first failure"),
            FlagOption("--empty", "empty", "--empty", "Schema-only dry run (zero-row refs/sources)"),
            FlagOption("--warn-error", "warn-error", "--warn-error", "Treat warnings as errors"),
            FlagOption("--store-failures", "store-failures", "--store-failures", "Store test failures in the warehouse"),
            FlagOption("--no-partial-parse", "partial-parse", "--no-partial-parse", "Disable partial parsing"),
            FlagOption("--defer", "defer", "--defer", "Defer unselected models to a previous state"),
            FlagOption("--favor-state", "favor-state", "--favor-state", "Prefer deferred state over local"),
            FlagOption("--debug", "debug", "--debug", "Verbose debug logging"),
        )

        private val SUBCOMMAND = mapOf(
            DbtVerb.RUN to "run",
            DbtVerb.BUILD to "build",
            DbtVerb.TEST to "test",
            DbtVerb.COMPILE to "compile",
            DbtVerb.PREVIEW to "show",
        )

        // Used only when `dbt <sub> --help` cannot be queried.
        private val FALLBACK = mapOf(
            DbtVerb.RUN to setOf("full-refresh", "fail-fast", "empty", "warn-error", "partial-parse", "defer", "favor-state", "debug"),
            DbtVerb.BUILD to setOf("full-refresh", "fail-fast", "empty", "warn-error", "store-failures", "partial-parse", "defer", "favor-state", "debug"),
            DbtVerb.TEST to setOf("fail-fast", "store-failures", "warn-error", "partial-parse", "defer", "favor-state", "debug"),
            DbtVerb.COMPILE to setOf("fail-fast", "warn-error", "partial-parse", "debug"),
            DbtVerb.PREVIEW to setOf("fail-fast", "warn-error", "partial-parse", "debug"),
        )
    }
}
