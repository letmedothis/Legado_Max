package io.legado.app.model.localBook

import io.legado.app.constant.AppPattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBookRecognitionTest {

    @Test
    fun `md and markdown extensions are recognized case insensitively`() {
        assertTrue("README.md".matches(AppPattern.bookFileRegex))
        assertTrue("指南.MD".matches(AppPattern.bookFileRegex))
        assertTrue("notes.markdown".matches(AppPattern.bookFileRegex))
        assertTrue("文档.MARKDOWN".matches(AppPattern.bookFileRegex))
        assertFalse("README.md.exe".matches(AppPattern.bookFileRegex))
        assertFalse("README.mdown".matches(AppPattern.bookFileRegex))
    }
}
