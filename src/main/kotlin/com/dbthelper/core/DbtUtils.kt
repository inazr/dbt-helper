package com.dbthelper.core

import com.dbthelper.core.model.ManifestIndex
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * Normalise a path to use forward slashes.
 * dbt on Windows writes backslashes into `original_file_path`; all manifest-derived
 * path keys must be normalised before they're used as map keys or compared.
 */
fun String.toUnixPath(): String = replace('\\', '/')

object DbtUtils {

    fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun friendlyName(uniqueId: String, index: ManifestIndex): String? {
        index.nodes[uniqueId]?.let { return it.name }
        index.sources[uniqueId]?.let { return "${it.sourceName}.${it.name}" }
        index.exposures[uniqueId]?.let { return it.name }
        return null
    }

    fun resolveFile(project: Project, rootPath: String, relativePath: String): PsiElement? {
        val fullPath = "$rootPath/$relativePath"
        val vf = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: return null
        return PsiManager.getInstance(project).findFile(vf)
    }
}
