package com.ykasorns.ebookreader.importer

import android.net.Uri

/**
 * Resolves a (URL-encoded) href found inside a book against the folder of the document that
 * contains it. Returns a decoded path relative to the book's content root, keeping any
 * "#fragment", or null for external links.
 */
internal fun resolveHref(baseDir: String, href: String): String? {
    val trimmed = href.trim()
    if (trimmed.isEmpty() || trimmed.contains("://") || trimmed.startsWith("mailto:")) return null
    val path = trimmed.substringBefore('#')
    val fragment = trimmed.substringAfter('#', "")
    val resolved = if (path.isEmpty()) "" else resolvePath(baseDir, path)
    return if (fragment.isEmpty()) resolved else "$resolved#${Uri.decode(fragment)}"
}

internal fun resolvePath(baseDir: String, href: String): String {
    val decoded = Uri.decode(href)
    val joined = if (baseDir.isEmpty() || decoded.startsWith("/")) decoded else "$baseDir/$decoded"
    val parts = ArrayDeque<String>()
    for (segment in joined.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(segment)
        }
    }
    return parts.joinToString("/")
}

internal fun parentDir(path: String): String = path.substringBeforeLast('/', "")
