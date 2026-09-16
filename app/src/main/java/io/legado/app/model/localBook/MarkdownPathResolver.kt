package io.legado.app.model.localBook

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Markdown 本地相对路径的纯路径解析器。
 *
 * 这里只处理路径段，不构造 `content://` URI。调用方必须使用 DocumentsContract 根据返回的
 * documentId 构造 URI，这样 provider authority、tree grant 和文档 ID 不会被字符串拼接破坏。
 */
internal object MarkdownPathResolver {

    fun decodeRelativePath(reference: String): String? {
        val path = reference.substringBefore('#').substringBefore('?').trim()
        if (path.isEmpty() || path.indexOf('\u0000') >= 0 || hasScheme(path)) return null
        return runCatching {
            // URLDecoder 把 '+' 当表单空格；Markdown URL 路径里的 '+' 应保持原义。
            URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8.name())
                .replace('\\', '/')
                .takeIf { '\u0000' !in it }
        }.getOrNull()
    }

    fun resolveFilePath(rootPath: String, documentPath: String, reference: String): String? {
        val decoded = decodeRelativePath(reference) ?: return null
        return runCatching {
            val root = File(rootPath).canonicalFile
            val document = File(documentPath).canonicalFile
            if (!document.isInside(root)) return null
            val target = if (decoded.startsWith('/')) {
                File(root, decoded.trimStart('/'))
            } else {
                File(document.parentFile ?: return null, decoded)
            }.canonicalFile
            target.takeIf { it.isInside(root) }?.path
        }.getOrNull()
    }

    fun resolveDocumentId(
        rootDocumentId: String,
        documentId: String,
        reference: String
    ): String? {
        val decoded = decodeRelativePath(reference) ?: return null
        val root = splitDocumentId(rootDocumentId)
        val document = splitDocumentId(documentId)
        if (!document.startsWithSegments(root) || document.size <= root.size) return null
        val target = if (decoded.startsWith('/')) root.toMutableList() else document.dropLast(1).toMutableList()
        decoded.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (target.size > root.size) {
                    target.removeAt(target.lastIndex)
                } else {
                    return null
                }

                else -> target.add(segment)
            }
        }
        return target.joinToString("/")
    }

    private fun splitDocumentId(documentId: String): List<String> {
        return documentId.replace('\\', '/').split('/').filter(String::isNotEmpty)
    }

    private fun List<String>.startsWithSegments(prefix: List<String>): Boolean {
        return size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }

    private fun File.isInside(root: File): Boolean {
        return path == root.path || path.startsWith(root.path + File.separator)
    }

    private fun hasScheme(path: String): Boolean {
        val colon = path.indexOf(':')
        if (colon <= 0) return false
        return path.substring(0, colon).allIndexed { index, character ->
            if (index == 0) character.isLetter() else character.isLetterOrDigit() || character in "+-."
        }
    }

    private inline fun CharSequence.allIndexed(predicate: (Int, Char) -> Boolean): Boolean {
        for (index in indices) if (!predicate(index, this[index])) return false
        return true
    }
}
