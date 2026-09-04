package io.legado.app.model.localBook

import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal data class MarkdownDocument(
    val title: String,
    val author: String?,
    val sections: List<MarkdownSection>
)

internal data class MarkdownSection(
    val title: String,
    val markdown: String,
    val level: Int,
    val isVolume: Boolean = markdown.isBlank()
)

/**
 * 将 Markdown 文档拆成阅读器章节，并转换为阅读排版器可消费的 HTML。
 *
 * 章节扫描独立于 CommonMark AST，是为了保留围栏代码块中的标题文本，同时兼容 ATX 与
 * Setext 两种标题。渲染器实例是不可变且线程安全的，可以供目录与正文加载线程复用。
 */
internal object MarkdownDocumentParser {

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

    private val atxHeadingRegex = Regex("^ {0,3}(#{1,6})(?:[\\t ]+|$)(.*)$")
    private val setextHeadingRegex = Regex("^ {0,3}(=+|-+)[\\t ]*$")
    private val fenceRegex = Regex("^ {0,3}(`{3,}|~{3,}).*$")
    private val yamlTitleRegex = Regex("(?m)^title\\s*:\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val unsafeSchemeRegex = Regex("^(?:javascript|vbscript):", RegexOption.IGNORE_CASE)

    fun parse(source: String, fallbackTitle: String): MarkdownDocument {
        val normalized = source.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val frontMatter = removeFrontMatter(normalized)
        val markdown = frontMatter.markdown
        val headings = findHeadings(markdown)
        val documentTitle = frontMatter.title
            ?: headings.firstOrNull { it.level == 1 }?.title
            ?: fallbackTitle
        val sections = ArrayList<MarkdownSection>()

        val preambleEnd = headings.firstOrNull()?.startLine ?: markdown.lines().size
        markdown.lines().subList(0, preambleEnd).joinToString("\n").trim()
            .takeIf { it.isNotEmpty() }
            ?.let {
                sections.add(MarkdownSection(frontMatter.title ?: fallbackTitle, it, 0))
            }

        val lines = markdown.lines()
        headings.forEachIndexed { index, heading ->
            val endLine = headings.getOrNull(index + 1)?.startLine ?: lines.size
            val content = lines.subList(heading.endLineExclusive, endLine)
                .joinToString("\n")
                .trim()
            sections.add(
                MarkdownSection(
                    title = heading.title.ifBlank { fallbackTitle },
                    markdown = content,
                    level = heading.level
                )
            )
        }

        if (sections.isEmpty()) {
            sections.add(MarkdownSection(documentTitle, markdown.trim(), 0))
        }
        return MarkdownDocument(documentTitle, frontMatter.author, sections)
    }

    /**
     * 输出必须保持为一行，否则 [io.legado.app.help.book.ContentProcessor] 会把一个 HTML
     * 片段拆成多个普通段落。换行编码为实体后，代码块仍能在 HtmlCompat 中恢复原布局。
     */
    fun render(markdown: String): String {
        val html = renderer.render(parser.parse(markdown))
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        makeTablesReadable(document.body())
        makeTaskItemsReadable(document.body())
        sanitizeUrls(document.body())
        val compactHtml = document.body().html()
            .replace("\r", "")
            .replace("\n", "&#10;")
        return "<usehtml>$compactHtml</usehtml>"
    }

    private fun removeFrontMatter(markdown: String): FrontMatter {
        val lines = markdown.lines()
        if (lines.firstOrNull()?.trim() != "---") return FrontMatter(null, null, markdown)
        val closeIndex = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: return FrontMatter(null, null, markdown)
        val yaml = lines.subList(1, closeIndex).joinToString("\n")
        val title = yamlTitleRegex.find(yaml)?.groupValues?.get(1)
            ?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            ?.takeIf { it.isNotBlank() }
        val author = Regex("(?m)^author\\s*:\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
            .find(yaml)?.groupValues?.get(1)
            ?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
            ?.takeIf { it.isNotBlank() }
        return FrontMatter(title, author, lines.drop(closeIndex + 1).joinToString("\n").trimStart())
    }

    private fun findHeadings(markdown: String): List<Heading> {
        val lines = markdown.lines()
        val headings = ArrayList<Heading>()
        var fenceChar: Char? = null
        var fenceLength = 0
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val fence = fenceRegex.matchEntire(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                if (fenceChar == null) {
                    fenceChar = marker.first()
                    fenceLength = marker.length
                } else if (marker.first() == fenceChar && marker.length >= fenceLength) {
                    fenceChar = null
                    fenceLength = 0
                }
                index++
                continue
            }
            if (fenceChar != null) {
                index++
                continue
            }

            val atx = atxHeadingRegex.matchEntire(line)
            if (atx != null) {
                headings.add(
                    Heading(
                        startLine = index,
                        endLineExclusive = index + 1,
                        level = atx.groupValues[1].length,
                        title = cleanHeading(atx.groupValues[2].replace(Regex("[\\t ]+#+[\\t ]*$"), ""))
                    )
                )
                index++
                continue
            }

            val underline = lines.getOrNull(index + 1)?.let(setextHeadingRegex::matchEntire)
            if (line.isNotBlank() && underline != null && !line.startsWith("    ")) {
                headings.add(
                    Heading(
                        startLine = index,
                        endLineExclusive = index + 2,
                        level = if (underline.groupValues[1].first() == '=') 1 else 2,
                        title = cleanHeading(line.trim())
                    )
                )
                index += 2
                continue
            }
            index++
        }
        return headings
    }

    private fun cleanHeading(value: String): String {
        val linksWithoutDestination = value
            .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("[`*_~]+"), "")
        return Jsoup.parse(linksWithoutDestination).text().trim()
    }

    /** Android 的 HtmlCompat 不支持表格布局，转换为等宽、逐行可读的文本表格。 */
    private fun makeTablesReadable(body: Element) {
        body.select("table").forEach { table ->
            val replacement = Element("p")
            val code = replacement.appendElement("code")
            val rows = table.select("tr")
            rows.forEachIndexed { index, row ->
                code.appendText(row.select("th, td").joinToString(" | ") { it.text() })
                if (index < rows.lastIndex) code.appendElement("br")
            }
            table.replaceWith(replacement)
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

    private data class Heading(
        val startLine: Int,
        val endLineExclusive: Int,
        val level: Int,
        val title: String
    )

    private data class FrontMatter(
        val title: String?,
        val author: String?,
        val markdown: String
    )
}
