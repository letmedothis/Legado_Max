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
        assertTrue(document.sections[1].markdown.contains("# not a chapter"))
        assertFalse(document.sections[1].markdown.contains("Chapter Two\n-----------"))
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
        assertEquals("Opening paragraph.", document.sections.single().markdown)
    }

    @Test
    fun `empty heading sections are volumes`() {
        val document = MarkdownDocumentParser.parse(
            "# Part One\n\n## Chapter One\n\nBody",
            "Fallback"
        )

        assertTrue(document.sections[0].isVolume)
        assertFalse(document.sections[1].isVolume)
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
}
