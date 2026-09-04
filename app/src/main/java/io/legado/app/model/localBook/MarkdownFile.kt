package io.legado.app.model.localBook

import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isLocalModified
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.inputStream
import io.legado.app.utils.isContentScheme
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** 本地 Markdown 文档解析器，负责目录、正文渲染与同目录图片访问。 */
class MarkdownFile private constructor(private var book: Book) {

    companion object : BaseLocalBookParse {
        private var markdownFile: MarkdownFile? = null

        @Synchronized
        private fun getMarkdownFile(book: Book): MarkdownFile {
            if (markdownFile == null ||
                markdownFile?.book?.bookUrl != book.bookUrl ||
                book.isLocalModified()
            ) {
                markdownFile = MarkdownFile(book)
            } else {
                markdownFile?.book = book
            }
            return markdownFile!!
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            getMarkdownFile(book).upBookInfo()
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getMarkdownFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getMarkdownFile(book).getContent(chapter)
        }

        @Synchronized
        override fun getImage(book: Book, href: String): InputStream? {
            return getMarkdownFile(book).getImage(href)
        }

        fun clear() {
            markdownFile = null
        }
    }

    private var document: MarkdownDocument? = null

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
                url = MD5Utils.md5Encode16(book.originName + index + section.title),
                title = section.title,
                isVolume = section.isVolume,
                baseUrl = book.bookUrl,
                bookUrl = book.bookUrl,
                index = index,
                wordCount = StringUtils.wordCountFormat(section.markdown.length),
                start = index.toLong(),
                end = (index + 1).toLong()
            )
        })
    }

    private fun getContent(chapter: BookChapter): String? {
        val section = getDocument().sections.getOrNull(chapter.index) ?: return null
        if (section.isVolume) return ""
        return MarkdownDocumentParser.render(section.markdown)
    }

    private fun getImage(href: String): InputStream? {
        val cleanHref = href.substringBefore(",{").trim()
        val directUri = cleanHref.toUri()
        if (directUri.isContentScheme()) {
            return directUri.inputStream(appCtx).getOrNull()
        }
        if (directUri.scheme.equals("file", ignoreCase = true)) {
            return directUri.path?.let(::File)?.takeIf(File::isFile)?.let(::FileInputStream)
        }
        if (!directUri.scheme.isNullOrBlank()) return null

        val bookUri = book.getLocalUri()
        if (!bookUri.isContentScheme()) {
            val parent = File(bookUri.path ?: return null).parentFile ?: return null
            val target = File(parent, Uri.decode(cleanHref.substringBefore('#').substringBefore('?')))
                .canonicalFile
            return target.takeIf(File::isFile)?.let(::FileInputStream)
        }
        return resolveRelativeDocumentUri(bookUri, cleanHref)
            ?.inputStream(appCtx)
            ?.getOrNull()
    }

    /**
     * SAF 不提供父节点 API；树文档 URI 的 documentId 保留相对层级，可据此定位同目录资源。
     * 对不暴露层级 documentId 的云盘提供方返回 null，随后由通用图片下载链路处理。
     */
    private fun resolveRelativeDocumentUri(bookUri: Uri, href: String): Uri? {
        return runCatching {
            if (!DocumentsContract.isDocumentUri(appCtx, bookUri)) return null
            val documentId = DocumentsContract.getDocumentId(bookUri)
            if (!documentId.contains('/')) return null
            val baseParts = documentId.substringBeforeLast('/').split('/').toMutableList()
            val rootId = if (bookUri.pathSegments.contains("tree")) {
                DocumentsContract.getTreeDocumentId(bookUri)
            } else {
                baseParts.first()
            }
            val rootParts = rootId.split('/')
            Uri.decode(href.substringBefore('#').substringBefore('?'))
                .replace('\\', '/')
                .split('/')
                .forEach { part ->
                    when (part) {
                        "", "." -> Unit
                        ".." -> if (baseParts.size > rootParts.size) baseParts.removeAt(baseParts.lastIndex)
                        else -> baseParts.add(part)
                    }
                }
            val targetId = baseParts.joinToString("/")
            if (bookUri.pathSegments.contains("tree")) {
                DocumentsContract.buildDocumentUriUsingTree(bookUri, targetId)
            } else {
                DocumentsContract.buildDocumentUri(bookUri.authority, targetId)
            }
        }.getOrNull()
    }
}
