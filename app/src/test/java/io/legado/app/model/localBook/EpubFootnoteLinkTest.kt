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
    fun `ordinary and malformed links are rejected`() {
        assertNull(EpubFootnoteLink.decode("https://example.com/#note"))
        assertNull(EpubFootnoteLink.decode("legado://epub-note?label=%ZZ"))
    }
}
