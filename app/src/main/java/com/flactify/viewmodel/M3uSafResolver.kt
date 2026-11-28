package com.flactify.viewmodel

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer

/** A path/URI that was enumerated from the selected SAF tree, never from M3U text. */
data class M3uGrantFile(val relativePath: String, val contentUri: String)

data class M3uGrantResolution(
    val resolvedUris: List<String>,
    val unresolved: List<String>,
    val ambiguous: List<String>
)

/**
 * Resolves M3U entries only against files already enumerated under a selected SAF tree.
 *
 * This utility performs no filesystem or network I/O. Relative entries are normalized for
 * Unicode, percent encoding, slash direction, and `.` segments; absolute paths and traversal are
 * rejected. A path with multiple tree candidates remains unresolved instead of guessing.
 */
object M3uSafResolver {
    fun resolve(entries: List<M3uEntry>, grantedFiles: List<M3uGrantFile>): M3uGrantResolution {
        val byPath = grantedFiles.groupBy { normalizeCandidate(it.relativePath) }
        val byName = grantedFiles.groupBy {
            normalizeCandidate(it.relativePath).substringAfterLast('/')
        }
        val byUri = grantedFiles.groupBy { it.contentUri }
        val resolved = ArrayList<String>()
        val unresolved = ArrayList<String>()
        val ambiguous = ArrayList<String>()
        entries.forEach { entry ->
            val direct = if (entry.location.startsWith("content://", ignoreCase = true)) {
                byUri[entry.location].orEmpty()
            } else {
                emptyList()
            }
            val candidates = if (direct.isNotEmpty()) direct else {
                val normalized = normalizeRelative(entry.location)
                if (normalized == null) emptyList()
                else byPath[normalized].orEmpty().ifEmpty {
                    if (!normalized.contains('/')) byName[normalized].orEmpty() else emptyList()
                }
            }
            when {
                candidates.size == 1 -> resolved += candidates.single().contentUri
                candidates.size > 1 -> ambiguous += entry.location
                else -> unresolved += entry.location
            }
        }
        return M3uGrantResolution(resolved.distinct(), unresolved, ambiguous)
    }

    private fun normalizeRelative(value: String): String? {
        val decoded = decode(value).replace('\\', '/')
        if (decoded.startsWith('/') || URI_SCHEME.containsMatchIn(decoded)) return null
        val parts = decoded.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/").takeIf { it.isNotEmpty() }
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
    }

    private fun normalizeCandidate(value: String): String =
        normalizeRelative(value) ?: "\u0000invalid"

    private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}
