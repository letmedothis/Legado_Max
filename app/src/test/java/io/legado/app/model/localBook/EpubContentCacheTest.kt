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
    fun `legacy unversioned cache is invalid`() {
        assertNull(EpubContentCache.decode("旧版正文缓存"))
    }
}
