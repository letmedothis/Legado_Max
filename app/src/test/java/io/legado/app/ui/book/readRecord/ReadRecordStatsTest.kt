package io.legado.app.ui.book.readRecord

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadRecordStatsTest {

    @Test
    fun `hours are grouped into four reading slots`() {
        assertEquals(ReadTimeSlot.NIGHT, readTimeSlotForHour(0))
        assertEquals(ReadTimeSlot.MORNING, readTimeSlotForHour(5))
        assertEquals(ReadTimeSlot.DAY, readTimeSlotForHour(12))
        assertEquals(ReadTimeSlot.EVENING, readTimeSlotForHour(18))
        assertEquals(ReadTimeSlot.NIGHT, readTimeSlotForHour(23))
    }

    @Test
    fun `reading speed is words per minute`() {
        assertEquals(120L, calculateReadingSpeed(600, 5 * 60_000L))
        assertEquals(0L, calculateReadingSpeed(600, 0L))
        assertEquals(0L, calculateReadingSpeed(0, 60_000L))
    }

    @Test
    fun `completion percent is bounded and uses one based chapter position`() {
        assertEquals(1, completionPercent(0, 100))
        assertEquals(50, completionPercent(49, 100))
        assertEquals(100, completionPercent(99, 100))
        assertEquals(100, completionPercent(120, 100))
        assertEquals(0, completionPercent(0, 0))
    }
}
