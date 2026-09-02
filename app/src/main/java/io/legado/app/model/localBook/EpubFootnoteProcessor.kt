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
    private val blockTags = setOf(
        "p", "div", "li", "section", "article", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6"
    )
    private val noteEntryTags = setOf("li", "p", "div", "aside", "section", "article")
    private val commonReferenceClasses = setOf(
        "footnote", "footnoteref", "footnotereference", "footnotelink", "footnoteanchor",
        "fnref", "fnanchor", "fnlink", "noteref", "notelink", "endnoteref",
        "endnotereference", "endnotelink", "enref", "sdfootnoteanc"
    )
    private val commonTargetClasses = setOf(
        "footnote", "footnote1", "footnoteitem", "footnotetext", "endnote", "endnoteitem",
        "endnotetext", "fn", "fncontent", "fnote", "fnote1", "fntext", "note", "note1",
        "note2", "note3", "sdfootnote"
    )
    private val noteGroupClasses = setOf(
        "footnotes", "endnotes", "rearnotes", "footnotelist", "endnotelist", "rearnotelist"
    )
    private val noteReferenceTypes = setOf("noteref", "annoref")
    private val noteTargetTypes = setOf("footnote", "endnote", "note", "rearnote", "annotation")
    private val noteGroupTypes = setOf("footnotes", "endnotes", "rearnotes")

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
            if (isBacklink(anchor)) return@mapNotNull null
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
            val noteTarget = resolveNoteTarget(target, anchor) ?: return@mapNotNull null
            val footnoteContent = extractContent(noteTarget, anchor).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            ResolvedReference(
                anchor = anchor,
                target = noteTarget,
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
            anchor.hasClass("zy") ||
            anchor.hasCommonClass(commonReferenceClasses) ||
            anchor.attributeTokens("rel").any { it in setOf("footnote", "endnote", "note") } ||
            anchor.attributeTokens("type").any { it in setOf("footnote", "endnote", "note") } ||
            anchor.attributeTokens("epub:type").any { it in noteReferenceTypes } ||
            anchor.attributeTokens("role").contains("doc-noteref")
    }

    private fun isIndividualNoteTarget(target: Element): Boolean {
        return target.hasClass("duokan-footnote-item") ||
            // Asciidoctor 等工具也会给引用外层 sup 加 footnote 类，类名仅在条目容器上作证据。
            (target.tagName() in noteEntryTags && target.hasCommonClass(commonTargetClasses)) ||
            target.attributeTokens("epub:type").any { it in noteTargetTypes } ||
            target.attributeTokens("role").any {
                it == "doc-footnote" || it == "doc-endnote"
            }
    }

    private fun isNoteGroup(element: Element): Boolean {
        return element.hasClass("duokan-footnote-content") ||
            element.hasCommonClass(noteGroupClasses) ||
            element.attributeTokens("epub:type").any { it in noteGroupTypes } ||
            element.attributeTokens("role").any {
                it == "doc-footnotes" || it == "doc-endnotes"
            }
    }

    private fun resolveNoteTarget(target: Element, reference: Element): Element? {
        if (reference.hasClass("zy") && target.hasClass("hl")) {
            return target.parents().firstOrNull { it.hasClass("zs") }
        }
        if (isIndividualNoteTarget(target)) return target
        target.parents().firstOrNull(::isIndividualNoteTarget)?.let { return it }

        if (isNoteReference(reference) && reference.id().isNotBlank()) {
            val hasReciprocalBacklink = target.select("a[href]").any {
                it.attr("href").substringAfterLast('#') == reference.id()
            }
            if (hasReciprocalBacklink) {
                return target.takeIf { it.tagName() in noteEntryTags } ?: target.parents()
                    .firstOrNull { it.tagName() in noteEntryTags }
            }
        }

        val noteGroup = target.parents().firstOrNull(::isNoteGroup) ?: return null
        return target.takeIf { it.tagName() in noteEntryTags } ?: target.parents()
            .firstOrNull { it !== noteGroup && it.tagName() in noteEntryTags }
    }

    private fun extractContent(target: Element, reference: Element): String {
        val clone = target.clone()
        clone.select("script, style").remove()
        clone.select("a[href]").filter { isBacklink(it, reference.id()) }.forEach(Element::remove)
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
        body.getAllElements().filter { isNoteGroup(it) && it.text().isBlank() }
            .forEach(Element::remove)
    }

    private fun isBacklink(link: Element, referenceId: String = ""): Boolean {
        return link.attributeTokens("epub:type").contains("backlink") ||
            link.attributeTokens("role").contains("doc-backlink") ||
            link.attributeTokens("rel").contains("backlink") ||
            link.attributeTokens("rev").any { it in setOf("footnote", "endnote", "note") } ||
            link.hasCommonClass(
                setOf("backlink", "footnoteback", "footnotebackref", "reversefootnote")
            ) ||
            referenceId.isNotBlank() &&
            link.attr("href").substringAfterLast('#') == referenceId
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
        attr(name).trim().split(whitespaceRegex).filter { it.isNotBlank() }
            .map { it.lowercase() }.toSet()

    private fun Element.hasCommonClass(classes: Set<String>): Boolean =
        classNames().any { it.normalizedClassName() in classes }

    private fun String.normalizedClassName(): String =
        lowercase().replace("-", "").replace("_", "")

    private data class ResolvedReference(
        val anchor: Element,
        val target: Element,
        val targetIsInCurrentBody: Boolean,
        val footnote: EpubFootnote,
    )
}
