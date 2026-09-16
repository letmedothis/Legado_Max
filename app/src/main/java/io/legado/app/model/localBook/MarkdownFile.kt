package io.legado.app.model.localBook

import android.net.Uri
import android.provider.DocumentsContract
import androidx.collection.LruCache
import androidx.core.net.toUri
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.inputStream
import io.legado.app.utils.isContentScheme
import splitties.init.appCtx
import java.io.File
import java.io.InputStream

/** 本地 Markdown 文档解析器，负责目录、正文渲染与授权范围内的相对资源、链接访问。 */
class MarkdownFile private constructor(private var book: Book) {

    companion object : BaseLocalBookParse {
        const val MARKDOWN_LEVEL = "markdownLevel"
        const val MARKDOWN_ANCHOR = "markdownAnchor"
        const val MARKDOWN_HEADING_IN_CONTENT = "markdownHeadingInContent"

        private val externalLinkSchemes = setOf("http", "https", "mailto", "tel")
        private val localLinkSchemes = setOf("file", "content")
        private var markdownFile: MarkdownFile? = null

        @Synchronized
        private fun getMarkdownFile(book: Book, checkModified: Boolean): MarkdownFile {
            if (markdownFile == null ||
                markdownFile?.book?.bookUrl != book.bookUrl ||
                (checkModified && book.isLocalModified())
            ) {
                markdownFile = MarkdownFile(book)
            } else {
                markdownFile?.book = book
            }
            return markdownFile!!
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            getMarkdownFile(book, checkModified = true).upBookInfo()
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getMarkdownFile(book, checkModified = true).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getMarkdownFile(book, checkModified = false).getContent(chapter)
        }

        @Synchronized
        override fun getImage(book: Book, href: String): InputStream? {
            return getMarkdownFile(book, checkModified = false).getImage(href)
        }

        @Synchronized
        fun resolveLink(book: Book, href: String): MarkdownLinkTarget {
            return getMarkdownFile(book, checkModified = false).resolveLink(href)
        }

        @Synchronized
        fun findChapterIndex(book: Book, anchor: String): Int? {
            return getMarkdownFile(book, checkModified = false).findChapterIndex(anchor)
        }

        fun clear() {
            markdownFile = null
        }
    }

    private var document: MarkdownDocument? = null
    private val renderedContentCache = object : LruCache<Int, String>(2 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: String): Int = value.length
    }

    private fun getDocument(): MarkdownDocument {
        document?.let { return it }
        val bytes = LocalBook.getBookInputStream(book).use { it.readBytes() }
        if (book.charset.isNullOrBlank()) {
            book.charset = EncodingDetect.getEncode(bytes.copyOf(minOf(bytes.size, 512_000)))
        }
        val source = String(bytes, book.fileCharset()).removePrefix("\uFEFF")
        return MarkdownDocumentParser.parse(source, book.name).also { document = it }
    }

    private fun upBookInfo() {
        val markdown = getDocument()
        if (markdown.title.isNotBlank()) {
            book.name = markdown.title
        }
        markdown.author?.let { book.author = it }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val sections = getDocument().sections
        return ArrayList(sections.mapIndexed { index, section ->
            BookChapter(
                url = MD5Utils.md5Encode16(
                    book.originName + "#" + (section.anchor ?: "preamble")
                ),
                title = section.title,
                // 每个 heading 都是可点击目录位置；层级存 variable，不能借用 isVolume，
                // 否则没有正文的 heading 会被目录 UI 当成不可打开的卷名。
                isVolume = false,
                baseUrl = book.bookUrl,
                bookUrl = book.bookUrl,
                index = index,
                wordCount = StringUtils.wordCountFormat(section.sourceEnd - section.sourceStart),
                start = section.sourceStart.toLong(),
                end = section.sourceEnd.toLong()
            ).apply {
                putVariable(MARKDOWN_LEVEL, section.level.toString())
                putVariable(MARKDOWN_ANCHOR, section.anchor)
                putVariable(MARKDOWN_HEADING_IN_CONTENT, section.headingInContent.toString())
            }
        })
    }

    private fun getContent(chapter: BookChapter): String? {
        val document = getDocument()
        val section = document.sections.getOrNull(chapter.index) ?: return null
        return renderedContentCache[chapter.index] ?: MarkdownDocumentParser
            .render(document.contentOf(section))
            .also { renderedContentCache.put(chapter.index, it) }
    }

    private fun getImage(href: String): InputStream? {
        return runCatching {
            resolveLocalUri(href.substringBefore(",{").trim())
                ?.inputStream(appCtx)
                ?.getOrNull()
        }.getOrNull()
    }

    private fun resolveLink(href: String): MarkdownLinkTarget {
        val raw = href.trim()
        if (raw.isEmpty()) return MarkdownLinkTarget.Invalid
        val uri = raw.toUri()
        val scheme = uri.scheme?.lowercase()
        if (scheme in externalLinkSchemes) return MarkdownLinkTarget.External(raw)
        if (scheme != null && scheme !in localLinkSchemes) return MarkdownLinkTarget.Invalid

        // Uri.fragment 已完成 percent decoding；再次 URLDecode 会错误处理标题中的字面 `%xx`。
        val fragment = uri.fragment
        val path = if (scheme == null) raw.substringBefore('#').substringBefore('?') else uri.path.orEmpty()
        if (path.isEmpty() || (fragment != null && path.hasHtmlExtension())) {
            return headingTarget(fragment)
        }
        if (!path.hasMarkdownExtension()) return MarkdownLinkTarget.Invalid

        val targetUri = resolveLocalUri(raw) ?: return MarkdownLinkTarget.Invalid
        if (sameDocument(book.getLocalUri(), targetUri)) return headingTarget(fragment)
        return MarkdownLinkTarget.LocalDocument(
            targetUri,
            fragment?.let(MarkdownDocumentParser::slugify)
        )
    }

    private fun headingTarget(fragment: String?): MarkdownLinkTarget {
        if (fragment.isNullOrBlank()) return MarkdownLinkTarget.Heading(0)
        val normalized = MarkdownDocumentParser.slugify(fragment)
        val index = getDocument().sections.indexOfFirst {
            it.anchor.equals(fragment, ignoreCase = true) || it.anchor == normalized
        }
        return if (index >= 0) MarkdownLinkTarget.Heading(index) else MarkdownLinkTarget.Invalid
    }

    private fun findChapterIndex(anchor: String): Int? {
        val normalized = MarkdownDocumentParser.slugify(anchor)
        return getDocument().sections.indexOfFirst {
            it.anchor == normalized
        }.takeIf { it >= 0 }
    }

    private fun resolveLocalUri(reference: String): Uri? {
        val bookUri = book.getLocalUri()
        val directUri = reference.toUri()
        return when (directUri.scheme?.lowercase()) {
            null -> resolveRelativeUri(bookUri, reference)
            "file" -> resolveFileUri(bookUri, directUri)
            "content" -> resolveContentUri(bookUri, directUri)
            else -> null
        }
    }

    private fun resolveRelativeUri(bookUri: Uri, reference: String): Uri? {
        if (bookUri.isContentScheme()) return resolveRelativeDocumentUri(bookUri, reference)
        val documentPath = bookUri.path ?: return null
        val targetPath = MarkdownPathResolver.resolveFilePath(
            allowedFileRoot(documentPath),
            documentPath,
            reference
        ) ?: return null
        return Uri.fromFile(File(targetPath))
    }

    private fun resolveFileUri(bookUri: Uri, targetUri: Uri): Uri? {
        if (bookUri.isContentScheme()) return null
        val documentPath = bookUri.path ?: return null
        val targetPath = targetUri.path ?: return null
        val root = File(allowedFileRoot(documentPath)).canonicalFile
        val target = File(targetPath).canonicalFile
        return target.takeIf { it.isInside(root) }?.let(Uri::fromFile)
    }

    private fun resolveContentUri(bookUri: Uri, targetUri: Uri): Uri? {
        if (!bookUri.isContentScheme() || bookUri.authority != targetUri.authority) return null
        return runCatching {
            val documentId = DocumentsContract.getDocumentId(bookUri)
            val rootId = documentRootId(bookUri, documentId)
            val targetId = DocumentsContract.getDocumentId(targetUri)
            if (!targetId.isDocumentIdInside(rootId)) return null
            buildDocumentUri(bookUri, targetId)
        }.getOrNull()
    }

    /**
     * SAF 没有通用父节点 API；树 URI 的 documentId 保留相对层级。路径先被归一化并限制在
     * tree grant 内，再交给 DocumentsContract 构造目标 URI。
     */
    private fun resolveRelativeDocumentUri(bookUri: Uri, href: String): Uri? {
        return runCatching {
            if (!DocumentsContract.isDocumentUri(appCtx, bookUri)) return null
            val documentId = DocumentsContract.getDocumentId(bookUri)
            val rootId = documentRootId(bookUri, documentId)
            val targetId = MarkdownPathResolver.resolveDocumentId(rootId, documentId, href)
                ?: return null
            buildDocumentUri(bookUri, targetId)
        }.getOrNull()
    }

    private fun documentRootId(bookUri: Uri, documentId: String): String {
        return if (bookUri.pathSegments.contains("tree")) {
            DocumentsContract.getTreeDocumentId(bookUri)
        } else {
            documentId.substringBeforeLast('/', documentId)
        }
    }

    private fun buildDocumentUri(bookUri: Uri, documentId: String): Uri {
        return if (bookUri.pathSegments.contains("tree")) {
            DocumentsContract.buildDocumentUriUsingTree(bookUri, documentId)
        } else {
            DocumentsContract.buildDocumentUri(bookUri.authority, documentId)
        }
    }

    private fun allowedFileRoot(documentPath: String): String {
        val document = File(documentPath).canonicalFile
        val configuredRoots = sequenceOf(AppConfig.importBookPath, AppConfig.defaultBookTreeUri)
            .filterNotNull()
            .mapNotNull { configured ->
                runCatching {
                    val uri = configured.toUri()
                    if (uri.isContentScheme()) null else File(uri.path ?: configured).canonicalFile
                }.getOrNull()
            }
            .filter { document.isInside(it) }
            .toList()
        return configuredRoots.maxByOrNull { it.path.length }?.path
            ?: document.parentFile?.path
            ?: document.path
    }

    private fun sameDocument(first: Uri, second: Uri): Boolean {
        if (first.scheme.equals("file", true) && second.scheme.equals("file", true)) {
            return runCatching { File(first.path!!).canonicalFile == File(second.path!!).canonicalFile }
                .getOrDefault(false)
        }
        return first == second
    }

    private fun String.hasMarkdownExtension(): Boolean {
        val path = substringBefore('#').substringBefore('?')
        return path.endsWith(".md", true) || path.endsWith(".markdown", true)
    }

    private fun String.hasHtmlExtension(): Boolean {
        return endsWith(".html", true) || endsWith(".htm", true)
    }

    private fun String.isDocumentIdInside(rootId: String): Boolean {
        val rootSegments = rootId.replace('\\', '/').split('/').filter(String::isNotEmpty)
        val targetSegments = replace('\\', '/').split('/').filter(String::isNotEmpty)
        return targetSegments.none { it == "." || it == ".." } &&
            targetSegments.size >= rootSegments.size &&
            rootSegments.indices.all { targetSegments[it] == rootSegments[it] }
    }

    private fun File.isInside(root: File): Boolean {
        return path == root.path || path.startsWith(root.path + File.separator)
    }
}

sealed interface MarkdownLinkTarget {
    data class Heading(val chapterIndex: Int) : MarkdownLinkTarget
    data class LocalDocument(val uri: Uri, val anchor: String?) : MarkdownLinkTarget
    data class External(val url: String) : MarkdownLinkTarget
    data object Invalid : MarkdownLinkTarget
}
