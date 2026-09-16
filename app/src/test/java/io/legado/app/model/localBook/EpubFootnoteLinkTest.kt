package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubFootnoteLinkTest {

    @Test
    fun `encode and decode preserves unicode content`() {
        val encoded = EpubFootnoteLink.encode("[12]", "基督山与 Monte Cristo")

        assertTrue(EpubFootnoteLink.isFootnote(encoded))
        assertTrue(EpubFootnoteLink.containsFootnote("<a href=\"$encoded\">[12]</a>"))
        assertEquals(
            EpubFootnote(label = "[12]", content = "基督山与 Monte Cristo"),
            EpubFootnoteLink.decode(encoded)
        )
    }

    @Test
    fun `html is transported alongside plain text`() {
        val encoded = EpubFootnoteLink.encode("[1]", "粗体", "<p><strong>粗体</strong></p>")

        val decoded = EpubFootnoteLink.decode(encoded)!!

        assertEquals("[1]", decoded.label)
        assertEquals("粗体", decoded.content)
        assertEquals("<p><strong>粗体</strong></p>", decoded.html)
    }

    @Test
    fun `image sources are extracted from encoded footnote html`() {
        val encoded = EpubFootnoteLink.encode("[1]", "图", "<p><img src=\"OEBPS/images/a.jpg\">图</p>")
        val content = "<p>正文<a href=\"${encoded.replace("&", "&amp;")}\">1</a></p>"

        assertEquals(listOf("OEBPS/images/a.jpg"), EpubFootnoteLink.extractImageSources(content))
        assertTrue(EpubFootnoteLink.extractImageSources("<p>没有注解</p>").isEmpty())
    }

    @Test
    fun `image sources are extracted directly from html`() {
        val html = "<p><img src='a.jpg'><img src=\"b.png\"></p>"

        assertEquals(listOf("a.jpg", "b.png"), EpubFootnoteLink.extractHtmlImageSources(html))
        assertEquals(
            listOf("a&b.jpg"),
            EpubFootnoteLink.extractHtmlImageSources("<p><img src=\"a&amp;b.jpg\"></p>")
        )
        assertTrue(EpubFootnoteLink.extractHtmlImageSources("<p>无图片</p>").isEmpty())
    }

    @Test
    fun `ordinary and malformed links are rejected`() {
        assertNull(EpubFootnoteLink.decode("https://example.com/#note"))
        assertNull(EpubFootnoteLink.decode("legado://epub-note?label=%ZZ"))
    }
}
