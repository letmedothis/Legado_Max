package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentParserTest {

    @Test
    fun `headings create chapters while fenced headings stay in content`() {
        val markdown = """
            introduction

            # Chapter One

            text

            ```markdown
            # not a chapter
            ```

            Chapter Two
            -----------

            more text
        """.trimIndent()

        val document = MarkdownDocumentParser.parse(markdown, "Fallback")

        assertEquals(listOf("Fallback", "Chapter One", "Chapter Two"), document.sections.map { it.title })
        assertEquals(listOf(0, 1, 2), document.sections.map { it.level })
        assertEquals(listOf(null, "chapter-one", "chapter-two"), document.sections.map { it.anchor })
        assertTrue(document.contentOf(document.sections[1]).contains("# not a chapter"))
        assertFalse(document.contentOf(document.sections[1]).contains("Chapter Two\n-----------"))
        assertTrue(document.contentOf(document.sections[1]).startsWith("# Chapter One"))
    }

    @Test
    fun `front matter title names document and is not rendered`() {
        val markdown = """
            ---
            title: A Markdown Book
            author: Example
            ---

            Opening paragraph.
        """.trimIndent()

        val document = MarkdownDocumentParser.parse(markdown, "Fallback")

        assertEquals("A Markdown Book", document.title)
        assertEquals("Example", document.author)
        assertEquals(listOf("A Markdown Book"), document.sections.map { it.title })
        assertEquals("Opening paragraph.", document.contentOf(document.sections.single()).trim())
    }

    @Test
    fun `empty heading sections remain navigable chapters`() {
        val document = MarkdownDocumentParser.parse(
            "# Part One\n\n## Chapter One\n\nBody",
            "Fallback"
        )

        assertFalse(document.sections[0].isVolume)
        assertFalse(document.sections[1].isVolume)
    }

    @Test
    fun `heading levels and duplicate anchors are stable and unique`() {
        val document = MarkdownDocumentParser.parse(
            "# Java\n\n## Introduction\n\n### GC\n\n## Introduction\n\n###### 中文 标题",
            "Fallback"
        )

        assertEquals(listOf(1, 2, 3, 2, 6), document.sections.map { it.level })
        assertEquals(
            listOf("java", "introduction", "gc", "introduction-2", "中文-标题"),
            document.sections.map { it.anchor }
        )
        assertEquals(document.sections.map { it.sourceStart }.sorted(), document.sections.map { it.sourceStart })
        assertTrue(document.sections.zipWithNext().all { (left, right) -> left.sourceEnd <= right.sourceStart })
    }

    @Test
    fun `utf8 bom is ignored`() {
        val document = MarkdownDocumentParser.parse("\uFEFF# 标题\n\n正文", "Fallback")

        assertEquals("标题", document.title)
        assertEquals("标题", document.sections.single().title)
        assertEquals("标题", document.sections.single().anchor)
    }

    @Test
    fun `render emits one usehtml line and common markdown html`() {
        val rendered = MarkdownDocumentParser.render(
            "**bold** and [site](https://example.com)\n\n- item"
        )

        assertFalse(rendered.contains('\n'))
        assertTrue(rendered.startsWith("<usehtml>"))
        assertTrue(rendered.endsWith("</usehtml>"))
        assertTrue(rendered.contains("<strong>bold</strong>"))
        assertTrue(rendered.contains("href=\"https://example.com\""))
        assertTrue(rendered.contains("<li>item</li>"))
    }

    @Test
    fun `render keeps ordered list numbers including nested levels`() {
        val rendered = MarkdownDocumentParser.render(
            "1. first\n2. second\n   1. nested"
        )

        assertTrue(rendered.contains("1. first"))
        assertTrue(rendered.contains("2. second"))
        assertTrue(rendered.contains("1. nested"))
    }

    @Test
    fun `render supports tables and keeps code line breaks`() {
        val rendered = MarkdownDocumentParser.render(
            """
                | Name | Value |
                | --- | --- |
                | one | two |

                ```kotlin
                val one = 1
                val two = 2
                ```
            """.trimIndent()
        )

        assertTrue(rendered.contains("<b>Name</b> | <b>Value</b>"))
        assertTrue(rendered.contains("one | two"))
        assertFalse(rendered.contains("<code>"))
        assertTrue(rendered.contains("val one = 1&#10;val two = 2"))
    }

    @Test
    fun `render supports gfm and removes unsafe urls`() {
        val rendered = MarkdownDocumentParser.render(
            "- [x] done\n- [ ] todo\n\n~~removed~~\n\nhttps://example.com\n\n[bad](javascript:alert(1))"
        )

        assertTrue(rendered.contains("☑ done"))
        assertTrue(rendered.contains("☐ todo"))
        assertTrue(rendered.contains("<del>removed</del>"))
        assertTrue(rendered.contains("href=\"https://example.com\""))
        assertFalse(rendered.contains("javascript:", ignoreCase = true))
    }

    @Test
    fun `render preserves relative images and heading links`() {
        val rendered = MarkdownDocumentParser.render(
            "![封面](./images/%E5%B0%81%E9%9D%A2%20%E5%9B%BE.jpg)\n\n[第二章](chapter/02.md#标题)\n\n[目录](#介绍)"
        )

        assertTrue(rendered.contains("<img"))
        assertTrue(rendered.contains("src=\"./images/"))
        assertTrue(rendered.contains("href=\"chapter/02.md#"))
        assertTrue(rendered.contains("href=\"#"))
    }

    @Test
    fun `large markdown is split into bounded virtual chapters without reparsing source text`() {
        listOf(1, 10, 50).forEach { megabytes ->
            val source = buildLargeMarkdown(megabytes)
            val document = MarkdownDocumentParser.parse(source, "Large")

            assertTrue(document.sections.size > 1)
            assertEquals(source.length, document.source.length)
            assertTrue(document.sections.all { it.sourceEnd > it.sourceStart })
            assertTrue(
                document.sections.all {
                    document.contentOf(it).length <= MarkdownDocumentParser.MAX_SECTION_CHARS + 8_192
                }
            )
            assertEquals("large", document.sections.first().anchor)
            assertTrue(document.sections.drop(1).all { it.anchor?.startsWith("large-part-") == true })
        }
    }

    @Test
    fun `virtual chapters retain fenced code semantics at a forced split`() {
        val source = buildString {
            append("# Code\n\n```kotlin\n")
            repeat(MarkdownDocumentParser.MAX_SECTION_CHARS / 16 + 100) {
                append("val item = \"").append(it).append("\"\n")
            }
            append("```\n")
        }

        val document = MarkdownDocumentParser.parse(source, "Code")

        assertTrue(document.sections.size > 1)
        assertTrue(document.contentOf(document.sections.first()).trimEnd().endsWith("```"))
        assertTrue(document.contentOf(document.sections[1]).startsWith("```"))
    }

    private fun buildLargeMarkdown(megabytes: Int): String = buildString(megabytes * 1_024 * 1_024) {
        append("# Large\n\n")
        val paragraph = "- [ ] item with [link](images/a.jpg) and `code`\n"
        while (length < megabytes * 1_024 * 1_024) {
            append(paragraph)
            if (length % 8_192 < paragraph.length) append('\n')
        }
    }
}
