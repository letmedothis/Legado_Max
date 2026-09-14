package io.legado.app.model

/** 当前播放内容对应的阅读记录身份。 */
internal data class PlaybackReadSession(
    val deviceId: String,
    val bookName: String,
    val bookAuthor: String,
    val chapterTitle: String,
    val source: String = "",
)

/**
 * 将播放器生命周期转换为 [ReadSessionRecorder] 的开始、心跳与收口事件。
 *
 * 播放器只负责提供当前书籍身份；是否记录、来源标记和结束前最后一次心跳统一在这里处理，
 * 避免各 Service 漏掉暂停、停止或后台播放场景。
 */
internal class PlaybackReadSessionTracker(
    private val source: String,
    private val isEnabled: () -> Boolean,
    private val sessionProvider: () -> PlaybackReadSession?,
    private val sink: PlaybackReadSessionSink = ReadSessionRecorderSink,
) {
    private var active = false

    @Synchronized
    fun start(now: Long = System.currentTimeMillis()) {
        if (!isEnabled()) return
        val session = currentSession() ?: return
        sink.start(session, now)
        active = true
    }

    @Synchronized
    fun tick(now: Long = System.currentTimeMillis()) {
        if (!isEnabled()) return
        val session = currentSession() ?: return
        if (!active) {
            sink.start(session, now)
            active = true
        }
        sink.tick(session, now)
    }

    @Synchronized
    fun flush(now: Long = System.currentTimeMillis()) {
        if (!active) return
        if (isEnabled()) {
            currentSession()?.let { sink.tick(it, now) }
        }
        sink.flush()
        active = false
    }

    private fun currentSession(): PlaybackReadSession? = sessionProvider()?.copy(source = source)
}

internal interface PlaybackReadSessionSink {
    fun start(session: PlaybackReadSession, now: Long)

    fun tick(session: PlaybackReadSession, now: Long)

    fun flush()
}

private object ReadSessionRecorderSink : PlaybackReadSessionSink {
    override fun start(session: PlaybackReadSession, now: Long) {
        ReadSessionRecorder.onReadStart(
            deviceId = session.deviceId,
            bookName = session.bookName,
            bookAuthor = session.bookAuthor,
            chapterTitle = session.chapterTitle,
            source = session.source,
            now = now,
        )
    }

    override fun tick(session: PlaybackReadSession, now: Long) {
        ReadSessionRecorder.onReadTick(
            deviceId = session.deviceId,
            bookName = session.bookName,
            bookAuthor = session.bookAuthor,
            chapterTitle = session.chapterTitle,
            source = session.source,
            now = now,
        )
    }

    override fun flush() {
        ReadSessionRecorder.flush()
    }
}
