package io.legado.app.model.localBook

import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.util.Locale

internal data class MarkdownDocument(
    val title: String,
    val author: String?,
    /** 原始内容只保留一份；章节按偏移量引用它，避免大型文档的按行副本。 */
    val source: String,
    val sections: List<MarkdownSection>
) {
    fun contentOf(section: MarkdownSection): String {
        val sourceContent = source.substring(section.sourceStart, section.sourceEnd)
        if (section.prefix.isEmpty() && section.suffix.isEmpty()) return sourceContent
        return buildString(section.prefix.length + sourceContent.length + section.suffix.length) {
            append(section.prefix)
            append(sourceContent)
            append(section.suffix)
        }
    }
}

internal data class MarkdownSection(
    val title: String,
    val level: Int,
    val anchor: String?,
    val sourceStart: Int,
    val sourceEnd: Int,
    val headingInContent: Boolean,
    val prefix: String = "",
    val suffix: String = "",
    val isVolume: Boolean = false
)

/**
 * 将 Markdown 文档拆成阅读器章节，并转换为阅读排版器可消费的 HTML。
 *
 * 章节扫描独立于 CommonMark AST，是为了保留围栏代码块中的标题文本，同时兼容 ATX 与
 * Setext 两种标题。渲染器实例是不可变且线程安全的，可以供目录与正文加载线程复用。
 */
internal object MarkdownDocumentParser {

    /** 单次 CommonMark/HtmlCompat 排版上限，避免单个无标题文档产生几十 MB Spannable。 */
    const val MAX_SECTION_CHARS = 512 * 1024
    private const val MAX_SECTION_OVERFLOW_CHARS = 8 * 1024
    private const val MAX_HEADING_TEXT_CHARS = 8 * 1024
    private const val MAX_FRONT_MATTER_CHARS = 64 * 1024

    private val extensions: List<Extension> = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        AutolinkExtension.create()
    )
    private val parser = Parser.builder()
        .extensions(extensions)
        .build()
    private val renderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(false)
        .percentEncodeUrls(true)
        .build()

    private val yamlTitleRegex = Regex("(?m)^title\\s*:\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val yamlAuthorRegex = Regex("(?m)^author\\s*:\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val trailingAtxClosingRegex = Regex("[\\t ]+#+[\\t ]*$")
    private val imageLinkRegex = Regex("!\\[([^]]*)]\\([^)]*\\)")
    private val textLinkRegex = Regex("\\[([^]]+)]\\([^)]*\\)")
    private val markdownDecorationsRegex = Regex("[`*_~]+")
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val unsafeSchemeRegex = Regex("^(?:javascript|vbscript):", RegexOption.IGNORE_CASE)

    fun parse(source: String, fallbackTitle: String): MarkdownDocument {
        val contentStart = source.indexOfFirst { it != '\uFEFF' }.coerceAtLeast(0)
        val frontMatter = findFrontMatter(source, contentStart)
        val headings = findHeadings(source, frontMatter.contentStart)
        val anchors = uniqueAnchors(headings)
        val documentTitle = frontMatter.title
            ?: headings.firstOrNull { it.level == 1 }?.title
            ?: fallbackTitle
        val logicalSections = ArrayList<LogicalSection>()
        val firstHeadingStart = headings.firstOrNull()?.start ?: source.length
        if (hasContent(source, frontMatter.contentStart, firstHeadingStart)) {
            logicalSections.add(
                LogicalSection(
                    title = frontMatter.title ?: fallbackTitle,
                    level = 0,
                    anchor = null,
                    start = frontMatter.contentStart,
                    end = firstHeadingStart,
                    headingInContent = false
                )
            )
        }
        headings.forEachIndexed { index, heading ->
            logicalSections.add(
                LogicalSection(
                    title = heading.title.ifBlank { fallbackTitle },
                    level = heading.level,
                    anchor = anchors[index],
                    start = heading.start,
                    end = headings.getOrNull(index + 1)?.start ?: source.length,
                    headingInContent = true
                )
            )
        }
        if (logicalSections.isEmpty()) {
            logicalSections.add(
                LogicalSection(documentTitle, 0, null, frontMatter.contentStart, source.length, false)
            )
        }
        val sections = logicalSections.flatMap { splitLargeSection(source, it) }
        return MarkdownDocument(documentTitle, frontMatter.author, source, sections)
    }

    /**
     * 输出必须保持为一行，否则 [io.legado.app.help.book.ContentProcessor] 会把一个 HTML
     * 片段拆成多个普通段落。换行编码为实体后，代码块仍能在 HtmlCompat 中恢复原布局。
     */
    fun render(markdown: String): String {
        val html = renderer.render(parser.parse(markdown))
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        makeTaskItemsReadable(document.body())
        makeOrderedListsReadable(document.body())
        makeTablesReadable(document.body())
        sanitizeUrls(document.body())
        val compactHtml = document.body().html()
            .replace("\r", "")
            .replace("\n", "&#10;")
        return "<usehtml>$compactHtml</usehtml>"
    }

    private fun findFrontMatter(source: String, start: Int): FrontMatter {
        val firstLine = lineAt(source, start)
        if (!firstLine.isTrimmedEquals(source, "---")) return FrontMatter(null, null, start)
        var cursor = firstLine.nextStart
        val limit = minOf(source.length, cursor + MAX_FRONT_MATTER_CHARS)
        var close: Line? = null
        while (cursor < limit) {
            val line = lineAt(source, cursor)
            if (line.isTrimmedEquals(source, "---")) {
                close = line
                break
            }
            cursor = line.nextStart
        }
        close ?: return FrontMatter(null, null, start)
        val yaml = source.substring(firstLine.nextStart, close.start)
        val title = yamlTitleRegex.find(yaml)?.groupValues?.get(1)
            ?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            ?.takeIf { it.isNotBlank() }
        val author = yamlAuthorRegex.find(yaml)?.groupValues?.get(1)
            ?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
            ?.takeIf { it.isNotBlank() }
        return FrontMatter(title, author, close.nextStart)
    }

    private fun findHeadings(source: String, start: Int): List<Heading> {
        val headings = ArrayList<Heading>()
        var cursor = start
        var activeFence: Fence? = null
        var previous: Line? = null
        var previousCanBeSetext = false
        while (cursor < source.length) {
            val line = lineAt(source, cursor)
            if (activeFence != null) {
                if (line.fenceMarker(source, activeFence) != null) activeFence = null
                previousCanBeSetext = false
            } else {
                val openingFence = line.fenceMarker(source, null)
                val atx = if (openingFence == null) line.atxHeading(source) else null
                when {
                    openingFence != null -> {
                        activeFence = openingFence
                        previousCanBeSetext = false
                    }

                    previous != null && previousCanBeSetext && line.setextLevel(source) != null -> {
                        headings.add(
                            Heading(
                                start = previous.start,
                                level = line.setextLevel(source)!!,
                                title = cleanHeading(previous.text(source, MAX_HEADING_TEXT_CHARS).trim())
                            )
                        )
                        previousCanBeSetext = false
                    }

                    atx != null -> {
                        headings.add(atx)
                        previousCanBeSetext = false
                    }

                    else -> previousCanBeSetext = line.canBeSetext(source)
                }
            }
            previous = line
            cursor = line.nextStart
        }
        return headings
    }

    private fun cleanHeading(value: String): String {
        val linksWithoutDestination = value
            .replace(imageLinkRegex, "$1")
            .replace(textLinkRegex, "$1")
            .replace(markdownDecorationsRegex, "")
        return if ('<' in linksWithoutDestination) {
            Jsoup.parse(linksWithoutDestination).text().trim()
        } else {
            linksWithoutDestination.trim()
        }
    }

    private fun uniqueAnchors(headings: List<Heading>): List<String> {
        val counts = HashMap<String, Int>()
        return headings.map { heading ->
            val base = slugify(heading.title)
            val count = (counts[base] ?: 0) + 1
            counts[base] = count
            if (count == 1) base else "$base-$count"
        }
    }

    /** GitHub 风格的可读锚点；保留 Unicode 字母数字，重复标题由 [uniqueAnchors] 加序号。 */
    internal fun slugify(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val result = StringBuilder(normalized.length)
        var pendingSeparator = false
        normalized.forEach { character ->
            when {
                character.isLetterOrDigit() || character == '_' -> {
                    if (pendingSeparator && result.isNotEmpty() && result.last() != '-') {
                        result.append('-')
                    }
                    result.append(character)
                    pendingSeparator = false
                }

                character.isWhitespace() || character == '-' -> pendingSeparator = result.isNotEmpty()
            }
        }
        return result.toString().trim('-').ifEmpty { "section" }
    }

    private fun splitLargeSection(source: String, logical: LogicalSection): List<MarkdownSection> {
        if (logical.end - logical.start <= MAX_SECTION_CHARS) return listOf(logical.toSection())
        val sections = ArrayList<MarkdownSection>()
        var start = logical.start
        var activeFence: Fence? = null
        var part = 1
        while (start < logical.end) {
            val split = findSplit(source, start, logical.end, activeFence)
            val isVirtual = part > 1
            val suffixFence = split.activeFence?.marker.orEmpty().takeIf { split.end < logical.end }
            sections.add(
                MarkdownSection(
                    title = if (isVirtual) "${logical.title}（$part）" else logical.title,
                    level = logical.level,
                    anchor = if (isVirtual) "${logical.anchor ?: "section"}-part-$part" else logical.anchor,
                    sourceStart = start,
                    sourceEnd = split.end,
                    headingInContent = logical.headingInContent && !isVirtual,
                    prefix = activeFence?.marker?.plus("\n").orEmpty(),
                    suffix = suffixFence?.let { "\n$it\n" }.orEmpty()
                )
            )
            start = split.end
            activeFence = split.activeFence
            part++
        }
        return sections
    }

    private fun findSplit(source: String, start: Int, end: Int, initialFence: Fence?): Split {
        val target = start + MAX_SECTION_CHARS
        var cursor = start
        var activeFence = initialFence
        var lastSafeBreak = -1
        while (cursor < end) {
            val line = lineAt(source, cursor, end)
            if (activeFence != null) {
                if (line.fenceMarker(source, activeFence) != null) activeFence = null
            } else {
                line.fenceMarker(source, null)?.let { activeFence = it }
                if (activeFence == null && line.isBlank(source)) lastSafeBreak = line.nextStart
            }
            if (line.nextStart >= target) {
                val splitEnd = when {
                    lastSafeBreak >= target - MAX_SECTION_OVERFLOW_CHARS -> lastSafeBreak
                    line.nextStart - start <= MAX_SECTION_CHARS + MAX_SECTION_OVERFLOW_CHARS -> line.nextStart
                    else -> safeHardBreak(source, target, start)
                }
                if (splitEnd == line.nextStart) return Split(splitEnd, activeFence)
                return Split(splitEnd, fenceAt(source, start, splitEnd, initialFence))
            }
            cursor = line.nextStart
        }
        return Split(end, activeFence)
    }

    private fun fenceAt(source: String, start: Int, end: Int, initialFence: Fence?): Fence? {
        var cursor = start
        var activeFence = initialFence
        while (cursor < end) {
            val line = lineAt(source, cursor, end)
            if (activeFence != null) {
                if (line.fenceMarker(source, activeFence) != null) activeFence = null
            } else {
                line.fenceMarker(source, null)?.let { activeFence = it }
            }
            cursor = line.nextStart
        }
        return activeFence
    }

    private fun safeHardBreak(source: String, target: Int, start: Int): Int {
        val candidate = target.coerceAtMost(source.length)
        return if (candidate > start && source[candidate - 1].isHighSurrogate() &&
            candidate < source.length && source[candidate].isLowSurrogate()
        ) candidate - 1 else candidate
    }

    private fun hasContent(source: String, start: Int, end: Int): Boolean {
        for (index in start until end) if (!source[index].isWhitespace()) return true
        return false
    }

    /** Android 的 HtmlCompat 不支持表格布局，转换为保留单元格格式的逐行内容。 */
    private fun makeTablesReadable(body: Element) {
        body.select("table").forEach { table ->
            val replacement = Element("div")
            val rows = table.select("tr")
            rows.forEachIndexed { index, row ->
                val cells = row.select("th, td")
                if (cells.isEmpty()) return@forEachIndexed
                val isHeader = row.select("th").isNotEmpty()
                val paragraph = replacement.appendElement("p")
                cells.forEachIndexed { cellIndex, cell ->
                    if (cellIndex > 0) paragraph.appendText(" | ")
                    val content = cell.html().trim()
                    if (isHeader) {
                        paragraph.appendElement("b").html(content)
                    } else {
                        paragraph.append(content)
                    }
                }
                if (index < rows.lastIndex && isHeader) {
                    replacement.appendElement("p").text("---")
                }
            }
            table.replaceWith(replacement)
        }
    }

    /** HtmlCompat 会保留列表项文本，但不可靠地绘制 [ol] 的编号，直接写入编号保证可见。 */
    private fun makeOrderedListsReadable(body: Element) {
        body.select("ol").forEach { list ->
            val depth = list.parents().count { it.tagName() == "ol" }
            val indent = "　".repeat(depth)
            list.children().filter { it.tagName() == "li" }.forEachIndexed { index, item ->
                item.prependText("$indent${index + 1}. ")
            }
        }
    }

    private fun makeTaskItemsReadable(body: Element) {
        body.select("li").forEach { item ->
            val firstText = item.textNodes().firstOrNull() ?: return@forEach
            firstText.text(
                firstText.text()
                    .replace(Regex("^\\s*\\[[xX]]\\s*"), "☑ ")
                    .replace(Regex("^\\s*\\[ ]\\s*"), "☐ ")
            )
        }
    }

    private fun sanitizeUrls(body: Element) {
        body.select("[href], [src]").forEach { element ->
            listOf("href", "src").forEach { attribute ->
                if (!element.hasAttr(attribute)) return@forEach
                val value = element.attr(attribute).trim()
                if (unsafeSchemeRegex.containsMatchIn(value) ||
                    (schemeRegex.containsMatchIn(value) && !isAllowedScheme(value, attribute))
                ) {
                    element.removeAttr(attribute)
                }
            }
        }
    }

    private fun isAllowedScheme(value: String, attribute: String): Boolean {
        val scheme = value.substringBefore(':').lowercase()
        return scheme in setOf("http", "https", "mailto", "tel", "file", "content") ||
            (attribute == "src" && scheme == "data" && value.startsWith("data:image/", true))
    }

    private fun lineAt(source: String, start: Int, limit: Int = source.length): Line {
        var end = start
        while (end < limit && source[end] != '\n') end++
        val textEnd = if (end > start && source[end - 1] == '\r') end - 1 else end
        return Line(start, textEnd, if (end < limit) end + 1 else end)
    }

    private data class Line(val start: Int, val textEnd: Int, val nextStart: Int) {
        fun isBlank(source: String): Boolean = start == textEnd || (start until textEnd).all { source[it].isWhitespace() }

        fun isTrimmedEquals(source: String, expected: String): Boolean {
            var first = start
            var last = textEnd
            while (first < last && source[first].isWhitespace()) first++
            while (last > first && source[last - 1].isWhitespace()) last--
            return last - first == expected.length && source.regionMatches(first, expected, 0, expected.length)
        }

        fun text(source: String, maxLength: Int): String {
            val end = minOf(textEnd, start + maxLength)
            return source.substring(start, end)
        }

        fun atxHeading(source: String): Heading? {
            var index = start
            repeat(3) {
                if (index < textEnd && source[index] == ' ') index++ else return@repeat
            }
            val markerStart = index
            while (index < textEnd && source[index] == '#') index++
            val level = index - markerStart
            if (level !in 1..6 || (index < textEnd && !source[index].isWhitespace())) return null
            while (index < textEnd && source[index].isWhitespace()) index++
            val titleEnd = textEnd
            val rawTitle = source.substring(index, minOf(titleEnd, index + MAX_HEADING_TEXT_CHARS))
                .replace(trailingAtxClosingRegex, "")
            return Heading(start, level, cleanHeading(rawTitle))
        }

        fun setextLevel(source: String): Int? {
            var index = start
            repeat(3) {
                if (index < textEnd && source[index] == ' ') index++ else return@repeat
            }
            if (index >= textEnd || (source[index] != '=' && source[index] != '-')) return null
            val marker = source[index]
            var count = 0
            while (index < textEnd && source[index] == marker) {
                index++
                count++
            }
            while (index < textEnd && source[index].isWhitespace()) index++
            return if (count > 0 && index == textEnd) if (marker == '=') 1 else 2 else null
        }

        fun canBeSetext(source: String): Boolean {
            if (isBlank(source)) return false
            return !(textEnd - start >= 4 && source[start] == ' ' && source[start + 1] == ' ' &&
                source[start + 2] == ' ' && source[start + 3] == ' ')
        }

        fun fenceMarker(source: String, activeFence: Fence?): Fence? {
            var index = start
            repeat(3) {
                if (index < textEnd && source[index] == ' ') index++ else return@repeat
            }
            if (index >= textEnd || (source[index] != '`' && source[index] != '~')) return null
            val markerChar = source[index]
            val markerStart = index
            while (index < textEnd && source[index] == markerChar) index++
            val count = index - markerStart
            if (count < 3) return null
            if (activeFence != null && (markerChar != activeFence.char || count < activeFence.length)) {
                return null
            }
            return Fence(markerChar, count)
        }
    }

    private data class Fence(val char: Char, val length: Int) {
        val marker get() = char.toString().repeat(length)
    }

    private data class Split(val end: Int, val activeFence: Fence?)

    private data class LogicalSection(
        val title: String,
        val level: Int,
        val anchor: String?,
        val start: Int,
        val end: Int,
        val headingInContent: Boolean
    ) {
        fun toSection() = MarkdownSection(
            title = title,
            level = level,
            anchor = anchor,
            sourceStart = start,
            sourceEnd = end,
            headingInContent = headingInContent
        )
    }

    private data class Heading(
        val start: Int,
        val level: Int,
        val title: String
    )

    private data class FrontMatter(
        val title: String?,
        val author: String?,
        val contentStart: Int
    )
}
