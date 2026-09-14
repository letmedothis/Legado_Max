package io.legado.app.model

import io.legado.app.data.entities.readRecord.ReadRecordSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReadSessionTrackerTest {

    @Test
    fun startTickAndFlushUseAudioSourceAndCloseTheSession() {
        val sink = FakePlaybackReadSessionSink()
        val tracker = PlaybackReadSessionTracker(
            source = ReadRecordSource.AUDIO.name,
            isEnabled = { true },
            sessionProvider = {
                PlaybackReadSession(
                    deviceId = "device",
                    bookName = "book",
                    bookAuthor = "author",
                    chapterTitle = "chapter",
                )
            },
            sink = sink,
        )

        tracker.start(now = 1_000L)
        tracker.tick(now = 2_000L)
        tracker.flush(now = 3_000L)

        assertEquals(listOf(1_000L), sink.starts.map { it.second })
        assertEquals(listOf(2_000L, 3_000L), sink.ticks.map { it.second })
        assertEquals(ReadRecordSource.AUDIO.name, sink.starts.single().first.source)
        assertEquals(1, sink.flushCount)
    }

    @Test
    fun disabledRecordingDoesNotEmitEvents() {
        val sink = FakePlaybackReadSessionSink()
        val tracker = PlaybackReadSessionTracker(
            source = ReadRecordSource.AUDIO.name,
            isEnabled = { false },
            sessionProvider = {
                PlaybackReadSession("device", "book", "author", "chapter")
            },
            sink = sink,
        )

        tracker.start(now = 1_000L)
        tracker.tick(now = 2_000L)
        tracker.flush(now = 3_000L)

        assertTrue(sink.starts.isEmpty())
        assertTrue(sink.ticks.isEmpty())
        assertEquals(0, sink.flushCount)
    }

    @Test
    fun disablingRecordingAfterStartClosesTheActiveSession() {
        val sink = FakePlaybackReadSessionSink()
        var enabled = true
        val tracker = PlaybackReadSessionTracker(
            source = ReadRecordSource.AUDIO.name,
            isEnabled = { enabled },
            sessionProvider = {
                PlaybackReadSession("device", "book", "author", "chapter")
            },
            sink = sink,
        )

        tracker.start(now = 1_000L)
        enabled = false
        tracker.flush(now = 2_000L)

        assertTrue(sink.ticks.isEmpty())
        assertEquals(1, sink.flushCount)
    }

    @Test
    fun missingBookDoesNotFlushAnotherPlaybackSession() {
        val sink = FakePlaybackReadSessionSink()
        val tracker = PlaybackReadSessionTracker(
            source = ReadRecordSource.AUDIO.name,
            isEnabled = { true },
            sessionProvider = { null },
            sink = sink,
        )

        tracker.flush(now = 3_000L)

        assertTrue(sink.ticks.isEmpty())
        assertEquals(0, sink.flushCount)
    }

    private class FakePlaybackReadSessionSink : PlaybackReadSessionSink {
        val starts = mutableListOf<Pair<PlaybackReadSession, Long>>()
        val ticks = mutableListOf<Pair<PlaybackReadSession, Long>>()
        var flushCount = 0

        override fun start(session: PlaybackReadSession, now: Long) {
            starts += session to now
        }

        override fun tick(session: PlaybackReadSession, now: Long) {
            ticks += session to now
        }

        override fun flush() {
            flushCount++
        }
    }
}
