package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubContentCacheTest {

    @Test
    fun `current cache round trips without exposing version header`() {
        val stored = EpubContentCache.encode("正文\n第二段")

        assertEquals("正文\n第二段", EpubContentCache.decode(stored))
    }

    @Test
    fun `cache from before extended footnote support is invalid`() {
        assertNull(EpubContentCache.decode("LEGADO_EPUB_CONTENT_V2\n旧版正文缓存"))
        assertNull(EpubContentCache.decode("LEGADO_EPUB_CONTENT_V3\n旧版正文缓存"))
        assertNull(EpubContentCache.decode("旧版正文缓存"))
    }
}
