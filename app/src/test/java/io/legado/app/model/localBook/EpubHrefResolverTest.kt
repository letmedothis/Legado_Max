package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubHrefResolverTest {

    private val source = "OEBPS/text/chapter.xhtml"

    @Test
    fun `fragment only keeps current resource`() {
        assertEquals(
            EpubHrefTarget(source, "note1"),
            EpubHrefResolver.resolveEpubHref(source, "#note1")
        )
    }

    @Test
    fun `same directory relative path`() {
        assertEquals(
            EpubHrefTarget("OEBPS/text/chapter2.xhtml", "note1"),
            EpubHrefResolver.resolveEpubHref(source, "chapter2.xhtml#note1")
        )
    }

    @Test
    fun `parent directory relative path`() {
        assertEquals(
            EpubHrefTarget("OEBPS/Text/chapter.xhtml", "note1"),
            EpubHrefResolver.resolveEpubHref(source, "../Text/chapter.xhtml#note1")
        )
    }

    @Test
    fun `dot slash relative path`() {
        assertEquals(
            EpubHrefTarget("OEBPS/text/chapter.xhtml", "note1"),
            EpubHrefResolver.resolveEpubHref(source, "./chapter.xhtml#note1")
        )
    }

    @Test
    fun `leading slash is zip root relative`() {
        assertEquals(
            EpubHrefTarget("OEBPS/notes.xhtml", "n1"),
            EpubHrefResolver.resolveEpubHref(source, "/OEBPS/notes.xhtml#n1")
        )
    }

    @Test
    fun `query string is ignored for resource lookup`() {
        assertEquals(
            EpubHrefTarget("OEBPS/text/chapter2.xhtml", "n1"),
            EpubHrefResolver.resolveEpubHref(source, "chapter2.xhtml?v=2#n1")
        )
    }

    @Test
    fun `percent encoded fragment is decoded`() {
        assertEquals(
            EpubHrefTarget(source, "note 1"),
            EpubHrefResolver.resolveEpubHref(source, "#note%201")
        )
    }

    @Test
    fun `unicode percent encoded fragment is decoded`() {
        assertEquals(
            EpubHrefTarget(source, "注释"),
            EpubHrefResolver.resolveEpubHref(source, "#%E6%B3%A8%E9%87%8A")
        )
    }

    @Test
    fun `raw chinese fragment is preserved`() {
        assertEquals(
            EpubHrefTarget(source, "中文脚注"),
            EpubHrefResolver.resolveEpubHref(source, "#中文脚注")
        )
    }

    @Test
    fun `chinese resource path is preserved without lowercasing`() {
        assertEquals(
            EpubHrefTarget("OEBPS/text/中文章节.xhtml", "注"),
            EpubHrefResolver.resolveEpubHref(source, "中文章节.xhtml#注")
        )
    }

    @Test
    fun `plus sign in resource path is not treated as space`() {
        assertEquals(
            EpubHrefTarget("OEBPS/text/ch+1.xhtml", "n1"),
            EpubHrefResolver.resolveEpubHref(source, "ch+1.xhtml#n1")
        )
    }

    @Test
    fun `encoded plus in fragment is decoded`() {
        assertEquals(
            EpubHrefTarget(source, "a+b"),
            EpubHrefResolver.resolveEpubHref(source, "#a%2Bb")
        )
    }

    @Test
    fun `invalid percent escape is kept literally and never throws`() {
        assertEquals(
            EpubHrefTarget("OEBPS/text/100%.xhtml", "n"),
            EpubHrefResolver.resolveEpubHref(source, "100%.xhtml#n")
        )
        assertEquals(
            EpubHrefTarget(source, "100%"),
            EpubHrefResolver.resolveEpubHref(source, "#100%")
        )
    }

    @Test
    fun `href without fragment yields null fragment`() {
        assertEquals(
            EpubHrefTarget("OEBPS/text/chapter2.xhtml", null),
            EpubHrefResolver.resolveEpubHref(source, "chapter2.xhtml")
        )
    }

    @Test
    fun `blank href is rejected`() {
        assertNull(EpubHrefResolver.resolveEpubHref(source, ""))
        assertNull(EpubHrefResolver.resolveEpubHref(source, "   "))
    }

    @Test
    fun `escaping above zip root is rejected`() {
        assertNull(EpubHrefResolver.resolveEpubHref(source, "../../../outside.xhtml#n"))
    }
}
