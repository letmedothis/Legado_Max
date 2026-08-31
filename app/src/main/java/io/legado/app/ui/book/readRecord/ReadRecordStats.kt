package io.legado.app.ui.book.readRecord

/** 阅读时段，用于把连续的 24 小时归并成易读的统计维度。 */
enum class ReadTimeSlot(val label: String) {
    NIGHT("深夜"),
    MORNING("早晨"),
    DAY("白天"),
    EVENING("晚上")
}

/** 根据本地小时返回阅读时段；越界值会按 0~23 归一化。 */
fun readTimeSlotForHour(hour: Int): ReadTimeSlot {
    return when (hour.coerceIn(0, 23)) {
        in 5..11 -> ReadTimeSlot.MORNING
        in 12..17 -> ReadTimeSlot.DAY
        in 18..22 -> ReadTimeSlot.EVENING
        else -> ReadTimeSlot.NIGHT
    }
}

/** 返回每分钟阅读字数；没有有效时长时返回 0，避免出现无意义的极大值。 */
fun calculateReadingSpeed(words: Long, durationMillis: Long): Long {
    if (words <= 0L || durationMillis <= 0L) return 0L
    return words * 60_000L / durationMillis
}

/** 根据当前章节下标和章节总数计算完成率，结果始终限制在 0~100。 */
fun completionPercent(chapterIndex: Int, chapterCount: Int): Int {
    if (chapterCount <= 0 || chapterIndex < 0) return 0
    return (((chapterIndex + 1).toLong() * 100L) / chapterCount)
        .toInt()
        .coerceIn(0, 100)
}
