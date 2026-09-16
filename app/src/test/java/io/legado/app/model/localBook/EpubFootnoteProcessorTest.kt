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
        val decoded = EpubFootnoteLink.decode(link.attr("href"))!!
        assertEquals("[1]", decoded.label)
        assertEquals("意大利的一座小岛。", decoded.content)
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
        val semantic = EpubFootnoteLink.decode(body.selectFirst("a[role=doc-noteref]")!!.attr("href"))!!
        assertEquals("a", semantic.label)
        assertEquals("标准脚注内容", semantic.content)
        assertNull(body.getElementById("note-a"))
    }

    @Test
    fun `静火 custom zy reference and hl note are supported`() {
        val body = body(
            """
            <p>正文<a class="zy" href="#id1a" id="id1">〔1〕</a>结束。</p>
            <p class="zs"><a class="hl" href="#id1" id="id1a">〔1〕</a>静火版注解内容。</p>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "Text/chapter.xhtml"))
        val jinhuo = EpubFootnoteLink.decode(body.selectFirst("a.zy")!!.attr("href"))!!
        assertEquals("〔1〕", jinhuo.label)
        assertEquals("静火版注解内容。", jinhuo.content)
        assertNull(body.getElementById("id1a"))
    }

    @Test
    fun `common epub2 footnote rel and container are supported`() {
        val body = body(
            """
            <p>正文<sup><a rel="footnote" href="#fn1">1</a></sup></p>
            <div class="footnotes"><ol><li id="fn1">通用 EPUB2 注解</li></ol></div>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        val epub2 = EpubFootnoteLink.decode(body.selectFirst("a[rel=footnote]")!!.attr("href"))!!
        assertEquals("1", epub2.label)
        assertEquals("通用 EPUB2 注解", epub2.content)
        assertNull(body.getElementById("fn1"))
    }

    @Test
    fun `dpub endnotes group supports entries without individual roles`() {
        val body = body(
            """
            <p>正文<a role="doc-noteref" href="#endnote-1">1</a></p>
            <section role="doc-endnotes">
              <ol><li id="endnote-1">DPUB 1.1 尾注</li></ol>
            </section>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        assertEquals(
            "DPUB 1.1 尾注",
            EpubFootnoteLink.decode(body.selectFirst("a[role=doc-noteref]")!!.attr("href"))?.content
        )
        assertTrue(body.select("section[role=doc-endnotes]").isEmpty())
    }

    @Test
    fun `legacy epub annotation and rearnote semantics are supported`() {
        val body = body(
            """
            <p>正文<a epub:type="annoref" href="#annotation-1">甲</a></p>
            <aside id="annotation-1" epub:type="annotation">旧版批注内容</aside>
            <p>正文<a epub:type="noteref" href="#rear-1">乙</a></p>
            <section epub:type="rearnotes"><ol>
              <li id="rear-1" epub:type="rearnote">旧版后置注释</li>
            </ol></section>
            """
        )

        assertEquals(2, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        val links = body.select("a[href]").filter { EpubFootnoteLink.isFootnote(it.attr("href")) }
        assertEquals("旧版批注内容", EpubFootnoteLink.decode(links[0].attr("href"))?.content)
        assertEquals("旧版后置注释", EpubFootnoteLink.decode(links[1].attr("href"))?.content)
    }

    @Test
    fun `semantic target supports unknown reference class and descendant id`() {
        val body = body(
            """
            <h1>标题<a class="apnb" href="#note-1">*</a></h1>
            <aside epub:type="footnote">
              <p id="note-1"><a class="footnote-back" href="#title">*</a>目标语义确认的注释</p>
            </aside>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        assertEquals(
            "目标语义确认的注释",
            EpubFootnoteLink.decode(body.selectFirst("a.apnb")!!.attr("href"))?.content
        )
        assertTrue(body.selectFirst("a.apnb")!!.parents().any { it.tagName() == "h1" })
        assertTrue(body.selectFirst("a.apnb")!!.parents().any { it.tagName() == "usehtml" })
    }

    @Test
    fun `asciidoctor footnote class is supported`() {
        val body = body(
            """
            <p>正文<sup class="footnote">[<a class="footnote" id="_footnoteref_1"
              href="#_footnotedef_1">1</a>]</sup></p>
            <div id="footnotes"><div class="footnote" id="_footnotedef_1">
              <a href="#_footnoteref_1">1. </a>Asciidoctor 注释
            </div></div>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        assertEquals(
            "Asciidoctor 注释",
            EpubFootnoteLink.decode(body.selectFirst("a.footnote")!!.attr("href"))?.content
        )
    }

    @Test
    fun `epub2 child anchor target resolves to enclosing footnote`() {
        val body = body(
            """
            <p>正文<a class="fnanchor" href="#fn-1">[1]</a></p>
            <p class="fnote"><a id="fn-1" href="#ref-1">[1]</a> EPUB 2 子锚点注释</p>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        assertEquals(
            "[1] EPUB 2 子锚点注释",
            EpubFootnoteLink.decode(body.selectFirst("a.fnanchor")!!.attr("href"))?.content
        )
        assertTrue(body.select("p.fnote").isEmpty())
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

    @Test
    fun `name anchor target without id is supported`() {
        val body = body(
            """
            <p>正文<a epub:type="noteref" href="#fn1">1</a></p>
            <p><a name="fn1"></a>name 锚点注释内容</p>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        assertEquals(
            "name 锚点注释内容",
            EpubFootnoteLink.decode(body.select("a[href]").first { EpubFootnoteLink.isFootnote(it.attr("href")) }!!.attr("href"))?.content
        )
    }

    @Test
    fun `semantic footnote nested below the id wrapper is supported`() {
        val body = body(
            """
            <p>正文<a epub:type="noteref" href="#fn1">1</a></p>
            <div id="fn1"><aside epub:type="footnote">后代语义注解</aside></div>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        assertEquals(
            "后代语义注解",
            EpubFootnoteLink.decode(body.select("a[href]").first { EpubFootnoteLink.isFootnote(it.attr("href")) }!!.attr("href"))?.content
        )
    }

    @Test
    fun `untagged reference detected by reciprocal backlink`() {
        val body = body(
            """
            <p>正文<a href="#fn1" id="ref1">[1]</a></p>
            <p id="fn1"><a href="#ref1">[1]</a> 无样式但互链的注解</p>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        assertEquals(
            "无样式但互链的注解",
            EpubFootnoteLink.decode(body.selectFirst("a#ref1")!!.attr("href"))?.content
        )
        assertNull(body.getElementById("fn1"))
    }

    @Test
    fun `noteref pointing at bare aside is supported`() {
        val body = body(
            """
            <p>正文<a epub:type="noteref" href="#note1">[1]</a></p>
            <aside id="note1">裸 aside 注解</aside>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        assertEquals(
            "裸 aside 注解",
            EpubFootnoteLink.decode(body.select("a[href]").first { EpubFootnoteLink.isFootnote(it.attr("href")) }!!.attr("href"))?.content
        )
    }

    @Test
    fun `cross resource path with plus sign is not decoded to space`() {
        val body = body("<p>正文<a epub:type=\"noteref\" href=\"ch+1.xhtml#n1\">*</a></p>")
        var requestedHref: String? = null

        val converted = EpubFootnoteProcessor.process(
            body = body,
            sourceHref = "EPUB/text/chapter.xhtml",
            resourceLoader = { href ->
                requestedHref = href
                body("<aside id=\"n1\" epub:type=\"footnote\">加号路径注解</aside>")
            }
        )

        assertEquals(1, converted)
        assertEquals("EPUB/text/ch+1.xhtml", requestedHref)
    }

    @Test
    fun `query suffix is stripped before resource lookup`() {
        val body = body("<p>正文<a epub:type=\"noteref\" href=\"notes.xhtml?v=2#n1\">*</a></p>")
        var requestedHref: String? = null

        val converted = EpubFootnoteProcessor.process(
            body = body,
            sourceHref = "EPUB/chapter.xhtml",
            resourceLoader = { href ->
                requestedHref = href
                body("<aside id=\"n1\" epub:type=\"footnote\">带查询串的注解</aside>")
            }
        )

        assertEquals(1, converted)
        assertEquals("EPUB/notes.xhtml", requestedHref)
    }

    @Test
    fun `malformed fragment does not throw`() {
        val body = body("<p>正文<a epub:type=\"noteref\" href=\"#100%\">*</a></p><p id=\"other\">保留</p>")

        val converted = EpubFootnoteProcessor.process(body, "chapter.xhtml")

        assertEquals(0, converted)
        assertEquals("保留", body.getElementById("other")!!.text())
    }

    @Test
    fun `note formatting is preserved as html`() {
        val body = body(
            """
            <p>正文<a epub:type="noteref" href="#n1">1</a></p>
            <aside id="n1" epub:type="footnote">
              <p>第一段<strong>粗体</strong>与<em>斜体</em></p>
              <ol><li>列表项</li></ol>
            </aside>
            """
        )

        assertEquals(1, EpubFootnoteProcessor.process(body, "chapter.xhtml"))
        val footnote = EpubFootnoteLink.decode(body.select("a[href]").first { EpubFootnoteLink.isFootnote(it.attr("href")) }!!.attr("href"))!!
        assertTrue(footnote.html.contains("<strong>粗体</strong>"))
        assertTrue(footnote.html.contains("<em>斜体</em>"))
        assertTrue(footnote.html.contains("<li>列表项</li>"))
    }

    private fun body(html: String): Element = Jsoup.parseBodyFragment(html).body()
}
