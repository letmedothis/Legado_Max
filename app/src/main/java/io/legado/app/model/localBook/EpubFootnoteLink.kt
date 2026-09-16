package io.legado.app.model.localBook

import java.net.URLDecoder
import java.net.URLEncoder

data class EpubFootnote(
    val label: String,
    val content: String,
    /** 已在解析期做过必要清理的注解 HTML，用于弹窗保留段落/粗斜体/列表/图片；旧格式可能为空。 */
    val html: String = "",
)

/**
 * EPUB 注释在正文缓存和排版层之间的稳定传输格式。
 *
 * 注释内容随内部链接保存，避免依赖当前打开的 EPUB 单例；这样章节从磁盘缓存恢复后仍能弹出注释。
 */
object EpubFootnoteLink {

    private const val PREFIX = "legado://epub-note?"
    private const val MAX_CONTENT_LENGTH = 32 * 1024
    private val noteUrlRegex = Regex("legado://epub-note\\?[^\"'<>\\s]+")
    private val imageSrcRegex =
        Regex("<img[^>]*\\ssrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    fun encode(label: String, content: String, html: String = ""): String {
        return buildString {
            append(PREFIX)
            append("label=")
            append(encodeComponent(label))
            append("&content=")
            append(encodeComponent(content.take(MAX_CONTENT_LENGTH)))
            if (html.isNotBlank()) {
                append("&html=")
                append(encodeComponent(html.take(MAX_CONTENT_LENGTH)))
            }
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
            EpubFootnote(values["label"].orEmpty(), content, values["html"].orEmpty())
        }.getOrNull()
    }

    /**
     * 从章节正文中提取所有注解弹窗内图片的资源路径，供排版前统一预取。
     * 注解正文以百分号编码保存在内部链接里，普通 img 标签扫描无法命中，需要先解码。
     */
    fun extractImageSources(text: String): List<String> {
        if (!text.contains(PREFIX)) return emptyList()
        val result = LinkedHashSet<String>()
        noteUrlRegex.findAll(text).forEach { match ->
            val footnote = decode(match.value.replace("&amp;", "&")) ?: return@forEach
            result.addAll(extractHtmlImageSources(footnote.html))
        }
        return result.toList()
    }

    /** 从注解 HTML 中提取图片资源路径；弹窗显示前据此预取图片。 */
    fun extractHtmlImageSources(html: String): List<String> {
        if (html.isBlank() || !html.contains("<img", ignoreCase = true)) return emptyList()
        return imageSrcRegex.findAll(html)
            // 注解 HTML 由 Jsoup 序列化，& 会写成 &amp;；弹窗解析时会还原，
            // 这里同样还原一层，保证预取缓存 key 与 ImageGetter 拿到的 src 一致。
            .map { it.groupValues[1].replace("&amp;", "&") }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decodeComponent(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
