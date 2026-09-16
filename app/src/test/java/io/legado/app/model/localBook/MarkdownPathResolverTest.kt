package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownPathResolverTest {

    @Test
    fun `resolves dot parent encoded and unicode paths inside root`() {
        assertEquals(
            "/library/book/images/cover.jpg",
            MarkdownPathResolver.resolveFilePath(
                rootPath = "/library",
                documentPath = "/library/book/README.md",
                reference = "./images/cover.jpg"
            )
        )
        assertEquals(
            "/library/images/封面 图.jpg",
            MarkdownPathResolver.resolveFilePath(
                rootPath = "/library",
                documentPath = "/library/book/README.md",
                reference = "../images/%E5%B0%81%E9%9D%A2%20%E5%9B%BE.jpg"
            )
        )
        assertEquals(
            "/library/book/章节/第二章.md",
            MarkdownPathResolver.resolveFilePath(
                rootPath = "/library",
                documentPath = "/library/book/README.md",
                reference = "%E7%AB%A0%E8%8A%82/%E7%AC%AC%E4%BA%8C%E7%AB%A0.md#标题"
            )
        )
    }

    @Test
    fun `rejects traversal outside granted root`() {
        assertNull(
            MarkdownPathResolver.resolveFilePath(
                rootPath = "/library",
                documentPath = "/library/book/README.md",
                reference = "../../../../private/secret.jpg"
            )
        )
        assertNull(
            MarkdownPathResolver.resolveDocumentId(
                rootDocumentId = "primary:library",
                documentId = "primary:library/book/README.md",
                reference = "../../../secret.jpg"
            )
        )
    }

    @Test
    fun `resolves SAF document ids without string concatenating uris`() {
        assertEquals(
            "primary:library/shared/封面.jpg",
            MarkdownPathResolver.resolveDocumentId(
                rootDocumentId = "primary:library",
                documentId = "primary:library/book/README.md",
                reference = "../shared/%E5%B0%81%E9%9D%A2.jpg"
            )
        )
    }

    @Test
    fun `malformed encoding and absolute relative references are rejected`() {
        assertNull(MarkdownPathResolver.decodeRelativePath("bad%2"))
        assertNull(MarkdownPathResolver.decodeRelativePath("bad%00path"))
        assertNull(
            MarkdownPathResolver.resolveDocumentId(
                rootDocumentId = "primary:library",
                documentId = "primary:library/book/README.md",
                reference = "content://other/document/image"
            )
        )
    }
}
