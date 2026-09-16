package io.legado.app.model.localBook

import java.io.ByteArrayOutputStream

/**
 * EPUB 内部链接解析结果。
 *
 * [resourcePath] 为 EPUB 压缩包根目录相对路径，大小写与 ZIP 实际条目一致（不做小写化）；
 * [fragmentId] 为百分号解码后的片段 id，无片段时为 null。
 */
data class EpubHrefTarget(
    val resourcePath: String,
    val fragmentId: String?,
)

/**
 * EPUB 2/3 统一的内部链接解析。
 *
 * 处理片段链接、相对路径（. / ..）、根路径、查询串、百分号编码、Unicode/中文路径与片段。
 * 解析过程不抛异常：无法解析时返回 null，由调用方安全降级。
 */
object EpubHrefResolver {

    fun resolveEpubHref(currentResourcePath: String, href: String): EpubHrefTarget? {
        val raw = href.trim()
        if (raw.isEmpty()) return null

        val hashIndex = raw.indexOf('#')
        val rawPath = if (hashIndex >= 0) raw.substring(0, hashIndex) else raw
        val rawFragment = if (hashIndex >= 0) raw.substring(hashIndex + 1) else null
        // 查询串不参与资源定位，EPUB 中它只用于标识文档内锚点之外的参数
        val path = rawPath.substringBefore('?')
        val fragmentId = rawFragment?.let(::percentDecode)?.takeIf { it.isNotEmpty() }

        if (path.isBlank()) {
            return EpubHrefTarget(currentResourcePath, fragmentId)
        }

        val decodedPath = percentDecode(path).replace('\\', '/')
        val baseDir = currentResourcePath.substringBeforeLast('/', "")
        val combined = when {
            decodedPath.startsWith('/') -> decodedPath
            baseDir.isEmpty() -> decodedPath
            else -> "$baseDir/$decodedPath"
        }
        val normalized = normalizePath(combined) ?: return null
        return EpubHrefTarget(normalized, fragmentId)
    }

    /**
     * 只解码 %XX，保留 '+' 字面量。
     * URLDecoder 会把 '+' 变成空格，这在 EPUB 路径/片段中是错误的。
     * 非法或截断的 % 转义按字面保留，避免整章解析因一个坏链接失败。
     */
    fun percentDecode(value: String): String {
        if (value.indexOf('%') < 0) return value
        val out = StringBuilder(value.length)
        val bytes = ByteArrayOutputStream()
        fun flushBytes() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
        }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            flushBytes()
            out.append(c)
            i++
        }
        flushBytes()
        return out.toString()
    }

    private fun normalizePath(path: String): String? {
        val segments = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/").takeIf { it.isNotEmpty() }
    }
}
