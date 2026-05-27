package com.dbthelper.actions

import com.dbthelper.core.DbtProjectLocator
import com.dbthelper.core.ManifestService
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

class DbtCommandRunner(private val project: Project) {

    private val logger = Logger.getInstance(DbtCommandRunner::class.java)

    data class RunResult(val exitCode: Int, val output: String, val success: Boolean)

    interface OutputListener {
        fun onLine(line: String)
        fun onFinished(result: RunResult)
        fun onProcessStarted(process: Process) {}
    }

    fun findDbtExecutable(): String {
        val settings = DbtHelperSettings.getInstance(project)
        if (settings.state.dbtExecutablePath.isNotBlank() && settings.state.dbtExecutablePath != "dbt") {
            return settings.state.dbtExecutablePath
        }

        val locator = DbtProjectLocator(project)
        val projectRoot = locator.findProjectRoot()?.path

        // Auto-detection order
        val candidates = mutableListOf<String>()

        // Check project-local venvs first
        if (projectRoot != null) {
            candidates.add("$projectRoot/.venv/bin/dbt")
            candidates.add("$projectRoot/venv/bin/dbt")
            candidates.add("$projectRoot/.env/bin/dbt")
        }

        // Common global locations
        val home = System.getProperty("user.home")
        candidates.add("$home/.local/bin/dbt")
        candidates.add("/usr/local/bin/dbt")
        candidates.add("/opt/homebrew/bin/dbt")

        for (candidate in candidates) {
            if (File(candidate).canExecute()) return candidate
        }

        // Try `which dbt`
        try {
            val proc = ProcessBuilder("which", "dbt")
                .redirectErrorStream(true)
                .start()
            val path = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() == 0 && path.isNotBlank()) return path
        } catch (_: Exception) {}

        return "dbt"
    }

    fun getVersion(): String? {
        return try {
            val dbt = findDbtExecutable()
            val process = ProcessBuilder(dbt, "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) {
                // Parse "Core:\n  - installed: 1.10.0-b2" or "dbt version: 1.x.x"
                val match = Regex("installed:\\s*([\\d.]+\\S*)").find(output)
                    ?: Regex("dbt version:\\s*([\\d.]+\\S*)").find(output)
                match?.groupValues?.get(1) ?: output.lines().firstOrNull()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun run(spec: DbtCommandSpec, listener: OutputListener) {
        val dbt = findDbtExecutable()
        val locator = DbtProjectLocator(project)
        val projectRoot = locator.findProjectRoot()?.path

        if (projectRoot == null) {
            listener.onLine("ERROR: No dbt project found")
            listener.onFinished(RunResult(-1, "", false))
            return
        }

        val command = DbtCommandBuilder.buildArgs(spec, dbt)

        runCommand(command, File(projectRoot), listener) { result ->
            // Auto-reload manifest after commands that can change it.
            if (result.success && spec.verb in setOf(
                    DbtVerb.RUN, DbtVerb.BUILD, DbtVerb.GENERATE_DOCS
                )
            ) {
                ManifestService.getInstance(project).reparse()
            }
        }
    }


    fun runCommand(
        command: List<String>,
        workingDir: File,
        listener: OutputListener,
        onComplete: ((RunResult) -> Unit)? = null
    ) {
        Thread {
            val output = StringBuilder()
            try {
                listener.onLine("$ ${command.joinToString(" ")}")
                listener.onLine("")

                val processBuilder = ProcessBuilder(command)
                    .directory(workingDir)
                    .redirectErrorStream(true)

                // Inherit PATH from system + set wide terminal for dbt show output
                val env = processBuilder.environment()
                System.getenv("PATH")?.let { env["PATH"] = it }
                System.getenv("HOME")?.let { env["HOME"] = it }
                env["COLUMNS"] = "500"
                env["NO_COLOR"] = "1"

                val process = processBuilder.start()
                listener.onProcessStarted(process)

                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        output.appendLine(line)
                        listener.onLine(line)
                    }
                }

                val exitCode = process.waitFor()
                val result = RunResult(exitCode, output.toString(), exitCode == 0)

                if (exitCode == 0) {
                    listener.onLine("")
                    listener.onLine("Process finished with exit code 0")
                } else {
                    listener.onLine("")
                    listener.onLine("Process finished with exit code $exitCode")
                }

                listener.onFinished(result)
                onComplete?.invoke(result)

            } catch (e: Exception) {
                logger.warn("Failed to run command: ${command.joinToString(" ")}", e)
                val errorMsg = e.message ?: "Unknown error"
                listener.onLine("ERROR: $errorMsg")
                val result = RunResult(-1, output.toString(), false)
                listener.onFinished(result)
                onComplete?.invoke(result)
            }
        }.apply {
            name = "dbt-command-runner"
            isDaemon = true
            start()
        }
    }
}
