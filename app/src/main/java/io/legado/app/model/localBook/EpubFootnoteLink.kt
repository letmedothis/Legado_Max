package io.legado.app.model.localBook

import java.net.URLDecoder
import java.net.URLEncoder

data class EpubFootnote(
    val label: String,
    val content: String,
)

/**
 * EPUB 注释在正文缓存和排版层之间的稳定传输格式。
 *
 * 注释内容随内部链接保存，避免依赖当前打开的 EPUB 单例；这样章节从磁盘缓存恢复后仍能弹出注释。
 */
object EpubFootnoteLink {

    private const val PREFIX = "legado://epub-note?"
    private const val MAX_CONTENT_LENGTH = 32 * 1024

    fun encode(label: String, content: String): String {
        return buildString {
            append(PREFIX)
            append("label=")
            append(encodeComponent(label))
            append("&content=")
            append(encodeComponent(content.take(MAX_CONTENT_LENGTH)))
        }
    }

    fun isFootnote(url: String): Boolean = url.startsWith(PREFIX)

    fun containsFootnote(text: String): Boolean = text.contains(PREFIX)

    fun decode(url: String): EpubFootnote? {
        if (!isFootnote(url)) return null
        return runCatching {
            val values = url.substring(PREFIX.length)
                .split('&')
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    decodeComponent(part.substring(0, separator)) to
                        decodeComponent(part.substring(separator + 1))
                }
                .toMap()
            val content = values["content"]?.takeIf { it.isNotBlank() } ?: return null
            EpubFootnote(values["label"].orEmpty(), content)
        }.getOrNull()
    }

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decodeComponent(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
