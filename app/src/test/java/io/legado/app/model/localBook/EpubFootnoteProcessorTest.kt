package io.legado.app.model.localBook

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubFootnoteProcessorTest {

    @Test
    fun `duokan footnote becomes internal popup link and inline note is removed`() {
        val body = body(
            """
            <p>皮阿诺萨岛<sup><a class="duokan-footnote" id="fnref1"
                href="chapter.xhtml#fn1"><span>[1]</span></a></sup>二十五海里。</p>
            <ol class="duokan-footnote-content">
              <li class="duokan-footnote-item" id="fn1">
                <p><a href="chapter.xhtml#fnref1">[1]</a>&nbsp;意大利的一座小岛。</p>
              </li>
            </ol>
            """
        )

        val converted = EpubFootnoteProcessor.process(body, "OEBPS/chapter.xhtml")

        assertEquals(1, converted)
        val link = body.selectFirst("a.duokan-footnote")!!
        assertEquals(
            EpubFootnote("[1]", "意大利的一座小岛。"),
            EpubFootnoteLink.decode(link.attr("href"))
        )
        assertTrue(link.parents().any { it.tagName() == "usehtml" })
        assertNull(body.getElementById("fn1"))
        assertTrue(body.select("ol.duokan-footnote-content").isEmpty())
    }

    @Test
    fun `epub semantic footnote and dpub aria role are supported`() {
        val body = body(
            """
            <p>正文<a epub:type="noteref" role="doc-noteref" href="#note-a">a</a></p>
            <aside id="note-a" epub:type="footnote" role="doc-footnote">
              <p><a epub:type="backlink" href="#ref-a">返回</a>标准脚注内容</p>
            </aside>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "EPUB/chapter.xhtml"))
        assertEquals(
            EpubFootnote("a", "标准脚注内容"),
            EpubFootnoteLink.decode(body.selectFirst("a[role=doc-noteref]")!!.attr("href"))
        )
        assertNull(body.getElementById("note-a"))
    }

    @Test
    fun `cross resource footnote resolves relative path`() {
        val body = body("<p>正文<a epub:type=\"noteref\" href=\"../notes/end.xhtml#n1\">*</a></p>")
        var requestedHref: String? = null

        val converted = EpubFootnoteProcessor.process(
            body = body,
            sourceHref = "EPUB/text/chapter.xhtml",
            resourceLoader = { href ->
                requestedHref = href
                body("<aside id=\"n1\" epub:type=\"footnote\">跨文件脚注</aside>")
            }
        )

        assertEquals(1, converted)
        assertEquals("EPUB/notes/end.xhtml", requestedHref)
        assertEquals(
            "跨文件脚注",
            EpubFootnoteLink.decode(body.selectFirst("a")!!.attr("href"))?.content
        )
    }

    @Test
    fun `missing target degrades without rewriting or removing content`() {
        val body = body(
            """
            <p>正文<a class="duokan-footnote" href="#missing">[1]</a></p>
            <p id="other">仍需保留</p>
            """
        )

        assertEquals(0, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        assertEquals("#missing", body.selectFirst("a")!!.attr("href"))
        assertEquals("仍需保留", body.getElementById("other")!!.text())
        assertTrue(body.select("usehtml").isEmpty())
    }

    @Test
    fun `ordinary fragment link remains unchanged`() {
        val body = body("<p><a href=\"#section-2\">跳转章节</a></p><h2 id=\"section-2\">第二节</h2>")

        assertEquals(0, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        assertEquals("#section-2", body.selectFirst("a")!!.attr("href"))
        assertFalse(EpubFootnoteLink.isFootnote(body.selectFirst("a")!!.attr("href")))
    }

    @Test
    fun `formatter keeps protected popup link but removes unrelated html`() {
        val html = """
            <p>普通段落</p>
            <usehtml><p>带<a href="legado://epub-note?label=x&amp;content=y">注</a>释</p></usehtml>
        """.trimIndent()

        val formatted = EpubFootnoteProcessor.formatProcessedHtml(html)

        assertTrue(formatted.contains("<usehtml>"))
        assertTrue(formatted.contains("legado://epub-note"))
        assertFalse(formatted.contains("<p>普通段落</p>"))
        assertTrue(formatted.contains("普通段落"))
    }

    private fun body(html: String): Element = Jsoup.parseBodyFragment(html).body()
}
