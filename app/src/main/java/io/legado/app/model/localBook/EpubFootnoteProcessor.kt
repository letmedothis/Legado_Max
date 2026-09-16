package io.legado.app.model.localBook

import io.legado.app.utils.HtmlFormatter
import org.jsoup.nodes.Element

/**
 * 将 EPUB 2/3 常见脚注结构转换为阅读器内部弹出式注释链接。
 *
 * 仅在引用和目标都能确认时改写正文；损坏或不认识的结构保持原样，避免误删书籍内容。
 * 目标定位统一走 [EpubHrefResolver]，因此相对路径、百分号编码、Unicode/中文路径与片段
 * 以及 name 锚点都能正确解析；任何异常链接都只跳过自身，不影响整章正文。
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
     * @param sourceResourceBody 当前 XHTML 未被章节片段裁剪前的完整 body。
     *        章节按 fragment 裁剪后，同文件、位于裁剪范围之外的注释目标仍能在这里找到。
     * @return 成功转换的注释引用数量。
     */
    fun process(
        body: Element,
        sourceHref: String,
        resourceLoader: (String) -> Element? = { null },
        sourceResourceBody: Element? = null,
    ): Int {
        val sourcePath = EpubHrefResolver.resolveEpubHref("", sourceHref)?.resourcePath ?: sourceHref
        val externalBodies = mutableMapOf<String, Element?>()
        val resolved = body.select("a[href]").mapNotNull { anchor ->
            runCatching {
                resolveReference(anchor, body, sourcePath, sourceResourceBody, resourceLoader, externalBodies)
            }.getOrNull()
        }

        resolved.forEach { item ->
            item.anchor.attr(
                "href",
                EpubFootnoteLink.encode(
                    item.anchor.text().trim(),
                    item.footnote.plain,
                    item.footnote.html,
                )
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

    private fun resolveReference(
        anchor: Element,
        body: Element,
        sourcePath: String,
        sourceResourceBody: Element?,
        resourceLoader: (String) -> Element?,
        externalBodies: MutableMap<String, Element?>,
    ): ResolvedReference? {
        if (isBacklink(anchor)) return null
        val hrefTarget = EpubHrefResolver.resolveEpubHref(sourcePath, anchor.attr("href").trim())
            ?: return null
        val fragmentId = hrefTarget.fragmentId ?: return null
        val targetPath = hrefTarget.resourcePath

        val currentTarget = if (targetPath == sourcePath) {
            findFragmentElement(body, fragmentId)
        } else {
            null
        }
        val target = currentTarget
            ?: if (targetPath == sourcePath) {
                sourceResourceBody?.let { findFragmentElement(it, fragmentId) }
            } else {
                externalBodies.getOrPut(targetPath) {
                    runCatching { resourceLoader(targetPath) }.getOrNull()
                }?.let { findFragmentElement(it, fragmentId) }
            }
            ?: return null

        val noteTarget = resolveNoteTarget(target, anchor) ?: return null
        val content = extractContent(noteTarget, anchor)
        if (content.plain.isBlank()) return null
        // 目标在当前 XHTML 内才需要内联移除；若解析出的容器包含引用本身（或就是 body），
        // 移除会连引用标记一起删掉，必须保留。
        val shouldRemove = currentTarget != null &&
            noteTarget !== body &&
            anchor.parents().none { it === noteTarget }
        return ResolvedReference(
            anchor = anchor,
            target = noteTarget,
            targetIsInCurrentBody = shouldRemove,
            footnote = content,
        )
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
        // 目标自身、祖先或后代带有脚注/尾注语义
        findSemanticNoteTarget(target)?.let { return it }

        // 引用与目标互相回链：普通引用也能确认为注释（EPUB2 常见）。
        // 这里不向父节点回退，否则反向链接自身也会被当成新的注解引用。
        if (reference.id().isNotBlank() && hasBacklinkTo(target, reference.id())) {
            return target.takeIf { it.tagName() in noteEntryTags }
        }

        // 引用显式声明 noteref/annoref，目标只是普通容器（如裸 aside）时按注释处理
        if (isNoteReference(reference)) {
            return noteEntryFor(target)
        }

        val noteGroup = target.parents().firstOrNull(::isNoteGroup) ?: return null
        return target.takeIf { it.tagName() in noteEntryTags } ?: target.parents()
            .firstOrNull { it !== noteGroup && it.tagName() in noteEntryTags }
    }

    private fun findSemanticNoteTarget(target: Element): Element? {
        if (isIndividualNoteTarget(target)) return target
        target.parents().firstOrNull(::isIndividualNoteTarget)?.let { return it }
        return target.getAllElements().firstOrNull { it !== target && isIndividualNoteTarget(it) }
    }

    private fun noteEntryFor(target: Element): Element {
        if (target.tagName() in noteEntryTags) return target
        return target.parents().firstOrNull { it.tagName() in noteEntryTags } ?: target
    }

    private fun findFragmentElement(root: Element, fragmentId: String): Element? {
        // 兼容 EPUB2 只用 name 作为锚点的写法，例如 <a name="fn1"></a>
        return root.getElementById(fragmentId)
            ?: root.getElementsByAttributeValue("name", fragmentId).firstOrNull()
    }

    private fun hasBacklinkTo(target: Element, referenceId: String): Boolean {
        return target.getAllElements().any { element ->
            element.tagName() == "a" && element.hasAttr("href") &&
                EpubHrefResolver.resolveEpubHref("", element.attr("href"))?.fragmentId == referenceId
        }
    }

    private fun extractContent(target: Element, reference: Element): NoteContent {
        val clone = target.clone()
        clone.select("script, style").remove()
        clone.select("a[href]").filter { isBacklink(it, reference.id()) }.forEach(Element::remove)
        // 弹窗需要保证内容可见：去掉内联样式/隐藏属性，避免 CSS 把注解隐藏掉
        clone.getAllElements().forEach { element ->
            element.removeAttr("style")
            element.removeAttr("hidden")
            element.attributes().asList()
                .filter { it.key.startsWith("on", ignoreCase = true) }
                .forEach { element.removeAttr(it.key) }
        }
        val plain = clone.text()
            .replace('\u00A0', ' ')
            .replace(invisibleTextRegex, "")
            .replace(whitespaceRegex, " ")
            .trim()
        return NoteContent(plain, clone.html().trim())
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
        if (link.attributeTokens("epub:type").contains("backlink") ||
            link.attributeTokens("role").contains("doc-backlink") ||
            link.attributeTokens("rel").contains("backlink") ||
            link.attributeTokens("rev").any { it in setOf("footnote", "endnote", "note") } ||
            link.hasCommonClass(
                setOf("backlink", "footnoteback", "footnotebackref", "reversefootnote")
            )
        ) {
            return true
        }
        if (referenceId.isNotBlank()) {
            val fragment = EpubHrefResolver.resolveEpubHref("", link.attr("href"))?.fragmentId
            if (fragment == referenceId) return true
        }
        return false
    }

    private fun Element.attributeTokens(name: String): Set<String> =
        attr(name).trim().split(whitespaceRegex).filter { it.isNotBlank() }
            .map { it.lowercase() }.toSet()

    private fun Element.hasCommonClass(classes: Set<String>): Boolean =
        classNames().any { it.normalizedClassName() in classes }

    private fun String.normalizedClassName(): String =
        lowercase().replace("-", "").replace("_", "")

    private data class NoteContent(
        val plain: String,
        val html: String,
    )

    private data class ResolvedReference(
        val anchor: Element,
        val target: Element,
        val targetIsInCurrentBody: Boolean,
        val footnote: NoteContent,
    )
}
