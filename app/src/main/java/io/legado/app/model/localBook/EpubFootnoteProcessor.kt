package io.legado.app.model.localBook

import io.legado.app.utils.HtmlFormatter
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

/**
 * 将 EPUB 2/3 常见脚注结构转换为阅读器内部弹出式注释链接。
 *
 * 仅在引用和目标都能确认时改写正文；损坏或不认识的结构保持原样，避免误删书籍内容。
 */
object EpubFootnoteProcessor {

    private val protectedHtmlRegex =
        Regex("<usehtml>.*?</usehtml>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val invisibleTextRegex = Regex("[\\u200B-\\u200D\\u2060\\uFEFF]")
    private val whitespaceRegex = Regex("\\s+")
    private val blockTags = setOf("p", "div", "li", "section", "article", "blockquote")

    /**
     * @param resourceLoader 按 EPUB 根目录相对路径加载目标文档 body，用于处理跨文件脚注。
     * @return 成功转换的注释引用数量。
     */
    fun process(
        body: Element,
        sourceHref: String,
        resourceLoader: (String) -> Element? = { null },
    ): Int {
        val sourcePath = normalizeResourceHref("", sourceHref) ?: sourceHref
        val externalBodies = mutableMapOf<String, Element?>()
        val resolved = body.select("a[href]").mapNotNull { anchor ->
            if (!isNoteReference(anchor)) return@mapNotNull null
            val href = anchor.attr("href").trim()
            val fragmentId = href.substringAfter('#', "").takeIf { it.isNotBlank() }
                ?.let(::decodeComponent)
                ?: return@mapNotNull null
            val relativeTarget = href.substringBefore('#')
            val targetPath = if (relativeTarget.isBlank()) {
                sourcePath
            } else {
                normalizeResourceHref(sourcePath, relativeTarget)
                    ?: return@mapNotNull null
            }
            val targetBody = if (targetPath == sourcePath) {
                body
            } else {
                externalBodies.getOrPut(targetPath) { resourceLoader(targetPath) }
                    ?: return@mapNotNull null
            }
            val target = targetBody.getElementById(fragmentId) ?: return@mapNotNull null
            if (!isNoteTarget(target) && !anchor.hasClass("duokan-footnote")) {
                return@mapNotNull null
            }
            val footnoteContent = extractContent(target, anchor).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            ResolvedReference(
                anchor = anchor,
                target = target,
                targetIsInCurrentBody = targetBody === body,
                footnote = EpubFootnote(
                    label = anchor.text().trim(),
                    content = footnoteContent,
                ),
            )
        }

        resolved.forEach { item ->
            item.anchor.attr(
                "href",
                EpubFootnoteLink.encode(item.footnote.label, item.footnote.content)
            )
            wrapReferenceBlock(item.anchor)
        }
        resolved.asSequence()
            .filter { it.targetIsInCurrentBody }
            .map { it.target }
            .distinctBy { System.identityHashCode(it) }
            .forEach(Element::remove)
        removeEmptyNoteGroups(body)
        return resolved.size
    }

    /** 在通用 HTML 净化期间保护包含注释链接的段落。 */
    fun formatProcessedHtml(html: String): String {
        val protectedBlocks = mutableListOf<String>()
        val protected = protectedHtmlRegex.replace(html) { match ->
            val placeholder = "LEGADOEPUBNOTEBLOCK${protectedBlocks.size}TOKEN"
            protectedBlocks.add(match.value)
            placeholder
        }
        var formatted = HtmlFormatter.formatKeepImg(protected)
        protectedBlocks.forEachIndexed { index, block ->
            formatted = formatted.replace("LEGADOEPUBNOTEBLOCK${index}TOKEN", block)
        }
        return formatted
    }

    private fun isNoteReference(anchor: Element): Boolean {
        return anchor.hasClass("duokan-footnote") ||
            anchor.attributeTokens("epub:type").contains("noteref") ||
            anchor.attributeTokens("role").contains("doc-noteref")
    }

    private fun isNoteTarget(target: Element): Boolean {
        return target.hasClass("duokan-footnote-item") ||
            target.attributeTokens("epub:type").any { it == "footnote" || it == "endnote" } ||
            target.attributeTokens("role").any {
                it == "doc-footnote" || it == "doc-endnote"
            }
    }

    private fun extractContent(target: Element, reference: Element): String {
        val clone = target.clone()
        clone.select("script, style").remove()
        clone.select("a[href]").filter { link ->
            link.attributeTokens("epub:type").contains("backlink") ||
                link.attributeTokens("role").contains("doc-backlink") ||
                reference.id().isNotBlank() &&
                link.attr("href").substringAfterLast('#') == reference.id()
        }.forEach(Element::remove)
        return clone.text()
            .replace('\u00A0', ' ')
            .replace(invisibleTextRegex, "")
            .replace(whitespaceRegex, " ")
            .trim()
    }

    private fun wrapReferenceBlock(anchor: Element) {
        if (anchor.parents().any { it.tagName() == "usehtml" }) return
        val block = anchor.parents().firstOrNull { it.tagName() in blockTags } ?: anchor
        block.wrap("<usehtml></usehtml>")
    }

    private fun removeEmptyNoteGroups(body: Element) {
        body.select("ol, ul, section, aside, div").filter { element ->
            val isNoteGroup = element.hasClass("duokan-footnote-content") ||
                element.attributeTokens("epub:type").any {
                    it == "footnotes" || it == "endnotes"
                } ||
                element.attributeTokens("role").any {
                    it == "doc-footnotes" || it == "doc-endnotes"
                }
            isNoteGroup && element.text().isBlank()
        }.forEach(Element::remove)
    }

    private fun normalizeResourceHref(sourceHref: String, targetHref: String): String? {
        return runCatching {
            val safeSource = sourceHref.replace(" ", "%20")
            val safeTarget = targetHref.replace(" ", "%20")
            val targetUri = URI(safeTarget)
            if (targetUri.isAbsolute || targetUri.rawAuthority != null) return null
            val resolved = URI(safeSource).resolve(targetUri).normalize().toString()
            decodeComponent(resolved)
        }.getOrNull()
    }

    private fun decodeComponent(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun Element.attributeTokens(name: String): Set<String> =
        attr(name).trim().split(whitespaceRegex).filter { it.isNotBlank() }.toSet()

    private data class ResolvedReference(
        val anchor: Element,
        val target: Element,
        val targetIsInCurrentBody: Boolean,
        val footnote: EpubFootnote,
    )
}
