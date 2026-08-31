package io.legado.app.model.localBook

/** EPUB 正文缓存格式版本，旧缓存会在首次读取时重新从原书解析。 */
object EpubContentCache {

    const val VERSION_LINE = "LEGADO_EPUB_CONTENT_V2"
    private const val HEADER = "$VERSION_LINE\n"

    fun encode(content: String): String = HEADER + content

    fun decode(storedContent: String): String? {
        if (!storedContent.startsWith(HEADER)) return null
        return storedContent.substring(HEADER.length)
    }
}
