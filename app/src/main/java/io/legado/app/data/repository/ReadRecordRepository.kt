package io.legado.app.data.repository

import io.legado.app.data.dao.DailyReadStat
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.entities.readRecord.ReadRecordSource
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import io.legado.app.constant.AppConst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

class ReadRecordRepository(
    private val dao: ReadRecordDao,
    private val currentDeviceIdProvider: () -> String = { AppConst.androidId }
) {
    companion object {
        const val CURRENT_REPAIR_VERSION = 4

        /** 相邻阅读片段的会话合并阈值（毫秒）：间隔 ≤ 20 分钟视为同一次阅读，与时间线视图 mergeContinuousSessions 口径一致 */
        const val SESSION_MERGE_GAP = 20 * 60 * 1000L
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

    private data class RecordIdentity(
        val deviceId: String,
        val bookName: String,
        val bookAuthor: String,
        val source: String
    )

    private fun getCurrentDeviceId(): String = currentDeviceIdProvider()

    private fun normalizeBookName(bookName: String): String = bookName.trim()

    private fun normalizeBookAuthor(bookAuthor: String): String = bookAuthor.trim()

    private fun normalizeSource(source: String?): String = runCatching {
        ReadRecordSource.valueOf(source ?: ReadRecordSource.TEXT.name).name
    }.getOrDefault(ReadRecordSource.TEXT.name)

    private fun normalizeRecord(record: ReadRecord): ReadRecord {
        return record.copy(
            bookName = normalizeBookName(record.bookName),
            bookAuthor = normalizeBookAuthor(record.bookAuthor),
            source = normalizeSource(record.source)
        )
    }

    private fun normalizeDetail(detail: ReadRecordDetail): ReadRecordDetail {
        return detail.copy(
            bookName = normalizeBookName(detail.bookName),
            bookAuthor = normalizeBookAuthor(detail.bookAuthor),
            source = normalizeSource(detail.source)
        )
    }

    private fun normalizeSession(session: ReadRecordSession): ReadRecordSession {
        return session.copy(
            bookName = normalizeBookName(session.bookName),
            bookAuthor = normalizeBookAuthor(session.bookAuthor),
            source = normalizeSource(session.source)
        )
    }

    private fun hasValidIdentity(deviceId: String, bookName: String): Boolean {
        return deviceId.isNotBlank() && bookName.isNotBlank()
    }

    private fun isValidRecord(record: ReadRecord): Boolean {
        return hasValidIdentity(record.deviceId, record.bookName)
    }

    private fun isValidDetail(detail: ReadRecordDetail): Boolean {
        return hasValidIdentity(detail.deviceId, detail.bookName)
    }

    private fun isValidSession(session: ReadRecordSession): Boolean {
        return hasValidIdentity(session.deviceId, session.bookName)
    }

    // ==================== SQL 聚合查询（方案三） ====================

    /**
     * 获取总阅读时间的 Flow（SQL 聚合，单次查询）。
     */
    fun getTotalReadTime(): Flow<Long> {
        return dao.getCalculatedTotalReadTime()
    }

    /**
     * 按日期聚合统计（SQL GROUP BY，替代内存 groupBy）。
     */
    fun getDailyStats(): Flow<List<DailyReadStat>> {
        return dao.getDailyStats()
    }

    /**
     * 指定日期的总阅读时间。
     */
    fun getReadTimeByDate(date: String): Flow<Long> {
        return dao.getReadTimeByDate(date)
    }

    /**
     * 指定日期的阅读书籍数（SQL COUNT DISTINCT）。
     */
    fun getBookCountByDate(date: String): Flow<Int> {
        return dao.getBookCountByDate(date)
    }

    /**
     * 获取过滤后的详情记录（搜索 + 日期筛选下推到 SQL）。
     * 使用 detailsCountFlow 作为触发器 + 分页加载，避免 CursorWindow 溢出。
     */
    fun getFilteredDetails(query: String, dateFilter: String?): Flow<List<ReadRecordDetail>> {
        return dao.detailsCountFlow().map { loadFilteredDetailsPaginated(query, dateFilter) }
    }

    private suspend fun loadFilteredDetailsPaginated(query: String, dateFilter: String?): List<ReadRecordDetail> {
        val pageSize = 500
        val details = mutableListOf<ReadRecordDetail>()
        var offset = 0
        while (true) {
            val page = dao.getFilteredDetailsPage(query, dateFilter, pageSize, offset)
            if (page.isEmpty()) break
            details.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        return details
    }

    /**
     * 获取过滤后的会话记录（搜索 + 日期筛选下推到 SQL）。
     * 使用 sessionsCountFlow 作为触发器 + 分页加载，避免 CursorWindow 溢出。
     */
    fun getFilteredSessions(query: String, dateFilter: String?): Flow<List<ReadRecordSession>> {
        return dao.sessionsCountFlow().map { loadFilteredSessionsPaginated(query, dateFilter) }
    }

    fun getAllSessions(): Flow<List<ReadRecordSession>> = dao.getAllSessions()

    private suspend fun loadFilteredSessionsPaginated(query: String, dateFilter: String?): List<ReadRecordSession> {
        val pageSize = 500
        val sessions = mutableListOf<ReadRecordSession>()
        var offset = 0
        while (true) {
            val page = dao.getFilteredSessionsPage(query, dateFilter, pageSize, offset)
            if (page.isEmpty()) break
            sessions.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        return sessions
    }

    /**
     * 分页懒加载 sessions：返回 startTime < [beforeTimestamp] 的前 [limit] 条记录。
     * 用于时间线视图的首屏加载和滚动加载更多。
     */
    suspend fun loadSessionsPage(
        query: String,
        dateFilter: String?,
        beforeTimestamp: Long?,
        limit: Int
    ): List<ReadRecordSession> {
        return dao.getFilteredSessionsBefore(query, dateFilter, beforeTimestamp, limit)
    }

    /**
     * 带详情时间校正的阅读记录列表（无日期筛选时使用）。
     * SQL JOIN 完成 readTime = MAX(readRecord.readTime, detail 之和) 的计算。
     */
    fun getRecordsWithDetailTime(query: String): Flow<List<ReadRecord>> {
        return dao.getRecordsWithDetailTime(query)
    }

    /**
     * 指定日期的每本书阅读统计（有日期筛选时使用）。
     * SQL GROUP BY + JOIN 直接返回聚合结果。
     */
    fun getRecordsByDate(query: String, date: String): Flow<List<ReadRecord>> {
        return dao.getRecordsByDate(query, date)
    }

    fun getBookSessions(bookName: String, bookAuthor: String): Flow<List<ReadRecordSession>> {
        return dao.getSessionsByBookFlow(getCurrentDeviceId(), bookName, bookAuthor)
    }

    /**
     * 按日期聚合统计单本书的会话：每天的会话数和总时长。
     * SQL GROUP BY 替代全量加载 Session 列表 + 内存 groupBy。
     */
    fun getBookTimelineDays(bookName: String, bookAuthor: String): Flow<List<ReadRecordTimelineDay>> {
        // 先按天分组再合并，避免跨天 session 被归并到前一天导致日统计偏差（与时间线视图 buildTimelineMap 口径一致）。
        // 单本书全量加载合并：碎片合并在 SQL 层做不了，且单书数据量可控
        return getBookSessions(bookName, bookAuthor).map { sessions ->
            sessions
                .groupBy { dateFormat.format(Date(it.startTime)) }
                .mapValues { (_, daySessions) ->
                    mergeCloseSessions(daySessions).sortedByDescending { it.startTime }
                }
                .toSortedMap(compareByDescending { it })
                .map { (date, daySessions) ->
                    ReadRecordTimelineDay(date = date, sessions = daySessions)
                }
        }
    }

    /**
     * 合并同一天内间隔 ≤ [SESSION_MERGE_GAP] 的相邻会话（翻页高频上报产生的碎片）。
     */
    private fun mergeCloseSessions(sessions: List<ReadRecordSession>): List<ReadRecordSession> {
        if (sessions.isEmpty()) return emptyList()
        val sorted = sessions.sortedBy { it.startTime }
        val merged = mutableListOf<ReadRecordSession>()
        merged.add(sorted.first().copy())
        for (i in 1 until sorted.size) {
            val current = sorted[i]
            val last = merged.last()
            if ((current.startTime - last.endTime) <= SESSION_MERGE_GAP) {
                merged[merged.lastIndex] = last.copy(
                    endTime = max(current.endTime, last.endTime),
                    words = last.words + current.words
                )
            } else {
                merged.add(current.copy())
            }
        }
        return merged
    }

    /**
     * 获取单本书的阅读时间（SQL 聚合，单次查询）。
     * 替代旧版 combine(getReadTimeFlow, getAllRecordDetails) 的全量加载方式。
     */
    fun getBookReadTime(bookName: String, bookAuthor: String): Flow<Long> {
        return dao.getBookReadTimeCalculated(getCurrentDeviceId(), bookName, bookAuthor)
    }

    suspend fun getMergeCandidates(targetRecord: ReadRecord): List<ReadRecord> {
        return dao.getReadRecordsByNameExcludingTarget(
            targetRecord.bookName,
            targetRecord.deviceId,
            targetRecord.bookAuthor
        )
    }

    suspend fun saveReadSession(newSession: ReadRecordSession) {
        val session = normalizeSession(newSession)
        if (!isValidSession(session)) return
        splitSessionByDay(session).forEach { sessionSegment ->
            val segmentDuration = sessionSegment.endTime - sessionSegment.startTime
            if (segmentDuration <= 0L && sessionSegment.words <= 0L) return@forEach
            dao.insertSession(sessionSegment)
            val dateString = dateFormat.format(Date(sessionSegment.startTime))
            updateReadRecordDetail(sessionSegment, segmentDuration, sessionSegment.words, dateString)
            updateReadRecord(sessionSegment, segmentDuration)
        }
    }

    private fun splitSessionByDay(session: ReadRecordSession): List<ReadRecordSession> {
        val totalDuration = session.endTime - session.startTime
        if (totalDuration <= 0L) {
            return if (session.words > 0L) listOf(session) else emptyList()
        }
        val zoneId = ZoneId.systemDefault()
        val segments = mutableListOf<ReadRecordSession>()
        var segmentStart = session.startTime
        var remainingWords = session.words.coerceAtLeast(0L)

        while (segmentStart < session.endTime) {
            val nextDayStart = Instant.ofEpochMilli(segmentStart)
                .atZone(zoneId)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
            val segmentEnd = min(session.endTime, nextDayStart)
            val segmentDuration = segmentEnd - segmentStart
            if (segmentDuration <= 0L) {
                break
            }
            val isLastSegment = segmentEnd >= session.endTime
            val segmentWords = when {
                remainingWords <= 0L -> 0L
                isLastSegment -> remainingWords
                else -> ((session.words * segmentDuration) / totalDuration).coerceAtMost(remainingWords)
            }
            segments += session.copy(
                id = 0,
                startTime = segmentStart,
                endTime = segmentEnd,
                words = segmentWords
            )
            remainingWords -= segmentWords
            segmentStart = segmentEnd
        }
        return segments
    }

    private suspend fun updateReadRecord(session: ReadRecordSession, durationDelta: Long) {
        if (durationDelta <= 0) return
        val existingRecord = dao.getReadRecord(session.deviceId, session.bookName, session.bookAuthor, session.source)
        if (existingRecord != null) {
            dao.update(
                existingRecord.copy(
                    readTime = existingRecord.readTime + durationDelta,
                    lastRead = session.endTime,
                    source = session.source
                )
            )
        } else {
            dao.insert(
                ReadRecord(
                    deviceId = session.deviceId,
                    bookName = session.bookName,
                    bookAuthor = session.bookAuthor,
                    readTime = durationDelta,
                    lastRead = session.endTime,
                    source = session.source
                )
            )
        }
    }

    private suspend fun updateReadRecordDetail(
        session: ReadRecordSession,
        durationDelta: Long,
        wordsDelta: Long,
        dateString: String
    ) {
        if (durationDelta <= 0 && wordsDelta <= 0) return
        val existingDetail = dao.getDetail(
            session.deviceId,
            session.bookName,
            session.bookAuthor,
            dateString,
            session.source
        )
        if (existingDetail != null) {
            existingDetail.readTime += durationDelta
            existingDetail.readWords += wordsDelta
            existingDetail.firstReadTime = min(existingDetail.firstReadTime, session.startTime)
            existingDetail.lastReadTime = max(existingDetail.lastReadTime, session.endTime)
            dao.insertDetail(existingDetail)
        } else {
            dao.insertDetail(
                ReadRecordDetail(
                    deviceId = session.deviceId,
                    bookName = session.bookName,
                    bookAuthor = session.bookAuthor,
                    date = dateString,
                    readTime = durationDelta,
                    readWords = wordsDelta,
                    firstReadTime = session.startTime,
                    lastReadTime = session.endTime,
                    source = session.source
                )
            )
        }
    }

    suspend fun deleteDetail(detail: ReadRecordDetail) {
        dao.deleteDetail(detail)
        dao.deleteSessionsByBookAndDate(
            detail.deviceId,
            detail.bookName,
            detail.bookAuthor,
            detail.date,
            detail.source
        )
        updateReadRecordTotal(detail.deviceId, detail.bookName, detail.bookAuthor, source = detail.source)
    }

    suspend fun deleteSession(session: ReadRecordSession) {
        dao.deleteSession(session)

        val dateString = dateFormat.format(Date(session.startTime))
        val remainingSessions =
            dao.getSessionsByBookAndDate(
                session.deviceId,
                session.bookName,
                session.bookAuthor,
                dateString,
                session.source
            )

        if (remainingSessions.isEmpty()) {
            val detail = dao.getDetail(
                session.deviceId,
                session.bookName,
                session.bookAuthor,
                dateString,
                session.source
            )
            detail?.let { dao.deleteDetail(it) }
        } else {
            val totalTime = remainingSessions.sumOf { it.endTime - it.startTime }
            val totalWords = remainingSessions.sumOf { it.words }
            val firstRead = remainingSessions.minOf { it.startTime }
            val lastRead = remainingSessions.maxOf { it.endTime }

            val existingDetail = dao.getDetail(
                session.deviceId,
                session.bookName,
                session.bookAuthor,
                dateString,
                session.source
            )
            existingDetail?.copy(
                readTime = totalTime,
                readWords = totalWords,
                firstReadTime = firstRead,
                lastReadTime = lastRead
            )?.let { dao.insertDetail(it) }
        }

        updateReadRecordTotal(session.deviceId, session.bookName, session.bookAuthor, source = session.source)
    }

    private suspend fun updateReadRecordTotal(
        deviceId: String,
        bookName: String,
        bookAuthor: String,
        minimumReadTime: Long = 0L,
        minimumLastRead: Long = 0L,
        source: String? = null
    ) {
        val allRemainingSessions = dao.getSessionsByBook(deviceId, bookName, bookAuthor, source)
        val allRemainingDetails = dao.getDetailsByBook(deviceId, bookName, bookAuthor, source)

        if (allRemainingSessions.isEmpty() && allRemainingDetails.isEmpty()) {
            dao.getReadRecord(deviceId, bookName, bookAuthor, source ?: "TEXT")?.let { existing ->
                if (minimumReadTime > 0L || minimumLastRead > 0L) {
                    dao.update(
                        existing.copy(
                            readTime = max(existing.readTime, minimumReadTime),
                            lastRead = max(existing.lastRead, minimumLastRead)
                        )
                    )
                } else {
                    dao.deleteReadRecord(existing)
                }
            }
        } else {
            val sessionTotalTime = allRemainingSessions.sumOf { it.endTime - it.startTime }
            val detailTotalTime = allRemainingDetails.sumOf { it.readTime }
            val totalTime = max(max(sessionTotalTime, detailTotalTime), minimumReadTime)
            val sessionLastRead = allRemainingSessions.maxOfOrNull { it.endTime } ?: 0L
            val detailLastRead = allRemainingDetails.maxOfOrNull { it.lastReadTime } ?: 0L
            val lastRead = max(max(sessionLastRead, detailLastRead), minimumLastRead)

            val existingRecord = dao.getReadRecord(deviceId, bookName, bookAuthor, source ?: "TEXT")
            if (existingRecord != null) {
                dao.update(
                    existingRecord.copy(
                        readTime = totalTime,
                        lastRead = lastRead
                    )
                )
            } else {
                dao.insert(
                    ReadRecord(
                        deviceId = deviceId,
                        bookName = bookName,
                        bookAuthor = bookAuthor,
                        readTime = totalTime,
                        lastRead = lastRead,
                        source = source ?: ReadRecordSource.TEXT.name
                    )
                )
            }
        }
    }

    suspend fun deleteReadRecord(record: ReadRecord) {
        dao.deleteReadRecord(record)
        dao.deleteDetailsByBook(record.deviceId, record.bookName, record.bookAuthor, record.source)
        dao.deleteSessionsByBook(record.deviceId, record.bookName, record.bookAuthor, record.source)
    }

    suspend fun deleteReadRecordByDate(record: ReadRecord, date: String) {
        dao.getDetail(record.deviceId, record.bookName, record.bookAuthor, date, record.source)?.let {
            dao.deleteDetail(it)
        }
        dao.deleteSessionsByBookAndDate(record.deviceId, record.bookName, record.bookAuthor, date, record.source)
        updateReadRecordTotal(record.deviceId, record.bookName, record.bookAuthor, source = record.source)
    }

    /**
     * 合并全部同名书籍的阅读记录：每个书名组以 lastRead 最新（其次 readTime 最大）的记录为目标，
     * 其余记录合并进去。整体运行在 IO 线程，返回合并的书籍组数。
     */
    suspend fun mergeAllSameNameRecords(): Int = withContext(Dispatchers.IO) {
        val groups = dao.getAllReadRecordsList()
            .groupBy { it.bookName to it.source }
            .filterValues { it.size > 1 }
        var mergedCount = 0
        groups.values.forEach { records ->
            val target = records.maxWith(
                compareBy({ it.lastRead }, { it.readTime })
            )
            val sources = records.filter { it != target }
            if (sources.isNotEmpty()) {
                mergeReadRecordInto(target, sources)
                mergedCount++
            }
        }
        mergedCount
    }

    suspend fun mergeReadRecordInto(targetRecord: ReadRecord, sourceRecords: List<ReadRecord>) {
        sourceRecords.forEach { sourceRecord ->
            mergeSingleReadRecordInto(targetRecord, sourceRecord)
        }
    }

    private suspend fun mergeSingleReadRecordInto(targetRecord: ReadRecord, sourceRecord: ReadRecord) {
        if (targetRecord == sourceRecord) return
        if (targetRecord.bookName != sourceRecord.bookName || targetRecord.source != sourceRecord.source) return

        val source = dao.getReadRecord(
            sourceRecord.deviceId,
            sourceRecord.bookName,
            sourceRecord.bookAuthor,
            sourceRecord.source
        ) ?: return

        val target = dao.getReadRecord(
            targetRecord.deviceId,
            targetRecord.bookName,
            targetRecord.bookAuthor,
            targetRecord.source
        ) ?: targetRecord.copy(readTime = 0L, lastRead = 0L)

        val useSourceProgress = source.lastRead >= target.lastRead

        val mergedReadTime = target.readTime + source.readTime
        val mergedLastRead = max(target.lastRead, source.lastRead)

        dao.insert(
            target.copy(
                readTime = mergedReadTime,
                lastRead = mergedLastRead,
                durChapterTitle = if (useSourceProgress) source.durChapterTitle else target.durChapterTitle,
                durChapterIndex = if (useSourceProgress) source.durChapterIndex else target.durChapterIndex
            )
        )

        val sourceDetails = dao.getDetailsByBook(
            sourceRecord.deviceId,
            sourceRecord.bookName,
            sourceRecord.bookAuthor,
            sourceRecord.source
        )
        sourceDetails.forEach { detail ->
            val existingTargetDetail = dao.getDetail(
                targetRecord.deviceId,
                targetRecord.bookName,
                targetRecord.bookAuthor,
                detail.date,
                targetRecord.source
            )
            if (existingTargetDetail == null) {
                dao.insertDetail(
                    detail.copy(
                        deviceId = targetRecord.deviceId,
                        bookAuthor = targetRecord.bookAuthor
                    )
                )
            } else {
                dao.insertDetail(
                    existingTargetDetail.copy(
                        readTime = existingTargetDetail.readTime + detail.readTime,
                        readWords = existingTargetDetail.readWords + detail.readWords,
                        firstReadTime = min(existingTargetDetail.firstReadTime, detail.firstReadTime),
                        lastReadTime = max(existingTargetDetail.lastReadTime, detail.lastReadTime)
                    )
                )
            }
        }
        dao.deleteDetailsByBook(sourceRecord.deviceId, sourceRecord.bookName, sourceRecord.bookAuthor, sourceRecord.source)

        val sourceSessions = dao.getSessionsByBook(
            sourceRecord.deviceId,
            sourceRecord.bookName,
            sourceRecord.bookAuthor,
            sourceRecord.source
        )
        sourceSessions.forEach { session ->
            dao.updateSession(
                session.copy(
                    deviceId = targetRecord.deviceId,
                    bookAuthor = targetRecord.bookAuthor
                )
            )
        }

        dao.deleteReadRecord(source)
        updateReadRecordTotal(
            targetRecord.deviceId,
            targetRecord.bookName,
            targetRecord.bookAuthor,
            minimumReadTime = mergedReadTime,
            minimumLastRead = mergedLastRead,
            source = targetRecord.source
        )
    }

    suspend fun fixEmptyAuthors(getAuthorByBookName: suspend (String) -> String?) {
        val recordsWithEmptyAuthor = dao.getRecordsWithEmptyAuthor()
        recordsWithEmptyAuthor.forEach { record ->
            val author = getAuthorByBookName(record.bookName)
            if (!author.isNullOrBlank()) {
                val existingRecord = dao.getReadRecord(record.deviceId, record.bookName, author, record.source)
                if (existingRecord != null) {
                    mergeSingleReadRecordInto(existingRecord, record)
                } else {
                    migrateRecordAuthor(record, author)
                }
            }
        }
    }

    suspend fun repairRecords(getAuthorByBookName: suspend (String) -> String?) {
        cleanupBlankBookNameData()
        fixEmptyAuthors(getAuthorByBookName)
        normalizeDuplicateDeviceRecords()
        rebuildAggregateRecordsFromHistory()
    }

    suspend fun cleanupBlankBookNameData() {
        dao.deleteRecordsWithBlankBookName()
        dao.deleteDetailsWithBlankBookName()
        dao.deleteSessionsWithBlankBookName()
    }

    suspend fun rebuildAggregateRecordsFromHistory() {
        val allRecords = dao.all.associateBy {
            RecordIdentity(it.deviceId, it.bookName, it.bookAuthor, it.source)
        }
        val detailsByIdentity = dao.getAllDetailsList()
            .groupBy { RecordIdentity(it.deviceId, it.bookName, it.bookAuthor, it.source) }
        val sessionsByIdentity = dao.getAllSessionsList()
            .groupBy { RecordIdentity(it.deviceId, it.bookName, it.bookAuthor, it.source) }

        val allIdentities = mutableSetOf<RecordIdentity>()
        allIdentities.addAll(detailsByIdentity.keys)
        allIdentities.addAll(sessionsByIdentity.keys)

        val toUpsert = mutableListOf<ReadRecord>()
        for (identity in allIdentities) {
            val bookSessions = sessionsByIdentity[identity].orEmpty()
            val bookDetails = detailsByIdentity[identity].orEmpty()
            val existingRecord = allRecords[identity]

            val sessionTotalTime = bookSessions.sumOf { it.endTime - it.startTime }
            val detailTotalTime = bookDetails.sumOf { it.readTime }
            val totalTime = max(max(sessionTotalTime, detailTotalTime), existingRecord?.readTime ?: 0L)

            val sessionLastRead = bookSessions.maxOfOrNull { it.endTime } ?: 0L
            val detailLastRead = bookDetails.maxOfOrNull { it.lastReadTime } ?: 0L
            val lastRead = max(max(sessionLastRead, detailLastRead), existingRecord?.lastRead ?: 0L)

            if (existingRecord != null) {
                if (existingRecord.readTime != totalTime || existingRecord.lastRead != lastRead) {
                    toUpsert.add(
                        existingRecord.copy(readTime = totalTime, lastRead = lastRead)
                    )
                }
            } else {
                toUpsert.add(
                    ReadRecord(
                        deviceId = identity.deviceId,
                        bookName = identity.bookName,
                        bookAuthor = identity.bookAuthor,
                        source = identity.source,
                        readTime = totalTime,
                        lastRead = lastRead
                    )
                )
            }
        }

        if (toUpsert.isNotEmpty()) {
            dao.insertAll(toUpsert)
        }
    }

    suspend fun normalizeDuplicateDeviceRecords() {
        val currentDeviceId = getCurrentDeviceId()
        val groupedRecords = dao.all.groupBy { Triple(it.bookName, it.bookAuthor, it.source) }
        groupedRecords.values.forEach { records ->
            if (records.size <= 1) return@forEach
            val targetRecord = records.firstOrNull { it.deviceId == currentDeviceId }
                ?: records.maxByOrNull { it.lastRead }
                ?: return@forEach

            if (targetRecord.deviceId != currentDeviceId) {
                val migratedTarget = targetRecord.copy(deviceId = currentDeviceId)
                importSingleRecord(migratedTarget)
                importSingleDetailRecords(
                    dao.getDetailsByBook(
                        targetRecord.deviceId,
                        targetRecord.bookName,
                        targetRecord.bookAuthor,
                        targetRecord.source
                    ).map { it.copy(deviceId = currentDeviceId) }
                )
                importSingleSessionRecords(
                    dao.getSessionsByBook(
                        targetRecord.deviceId,
                        targetRecord.bookName,
                        targetRecord.bookAuthor,
                        targetRecord.source
                    ).map { it.copy(id = 0, deviceId = currentDeviceId) }
                )
                dao.deleteDetailsByBook(targetRecord.deviceId, targetRecord.bookName, targetRecord.bookAuthor, targetRecord.source)
                dao.deleteSessionsByBook(targetRecord.deviceId, targetRecord.bookName, targetRecord.bookAuthor, targetRecord.source)
                dao.deleteReadRecord(targetRecord)
            }

            val normalizedTarget = dao.getReadRecord(
                currentDeviceId,
                targetRecord.bookName,
                targetRecord.bookAuthor,
                targetRecord.source
            ) ?: return@forEach

            records.filter { it.deviceId != currentDeviceId }.forEach { sourceRecord ->
                mergeSingleReadRecordInto(normalizedTarget, sourceRecord)
            }
        }
    }

    private suspend fun migrateRecordAuthor(record: ReadRecord, author: String) {
        val source = dao.getReadRecord(record.deviceId, record.bookName, record.bookAuthor, record.source) ?: return

        dao.insert(
            source.copy(
                bookAuthor = author
            )
        )
        dao.deleteReadRecord(source)

        val sourceDetails = dao.getDetailsByBook(record.deviceId, record.bookName, record.bookAuthor, record.source)
        sourceDetails.forEach { detail ->
            val existingTargetDetail = dao.getDetail(
                record.deviceId,
                record.bookName,
                author,
                detail.date,
                record.source
            )
            if (existingTargetDetail == null) {
                dao.insertDetail(detail.copy(bookAuthor = author))
            } else {
                dao.insertDetail(
                    existingTargetDetail.copy(
                        readTime = existingTargetDetail.readTime + detail.readTime,
                        readWords = existingTargetDetail.readWords + detail.readWords,
                        firstReadTime = min(existingTargetDetail.firstReadTime, detail.firstReadTime),
                        lastReadTime = max(existingTargetDetail.lastReadTime, detail.lastReadTime)
                    )
                )
            }
        }
        dao.deleteDetailsByBook(record.deviceId, record.bookName, record.bookAuthor, record.source)

        val sourceSessions = dao.getSessionsByBook(record.deviceId, record.bookName, record.bookAuthor, record.source)
        sourceSessions.forEach { session ->
            dao.updateSession(session.copy(bookAuthor = author))
        }
    }

    suspend fun importRecords(
        records: List<ReadRecord>,
        details: List<ReadRecordDetail> = emptyList(),
        sessions: List<ReadRecordSession> = emptyList()
    ) {
        val currentDeviceId = getCurrentDeviceId()

        // 批量插入所有记录（归一化 deviceId 到当前设备）
        val normalizedRecords = records
            .map { normalizeRecord(it).copy(deviceId = currentDeviceId) }
            .filter { isValidRecord(it) }
        if (normalizedRecords.isNotEmpty()) {
            dao.insertAll(normalizedRecords)
        }

        // 批量插入所有详情记录
        val normalizedDetails = details
            .map { normalizeDetail(it).copy(deviceId = currentDeviceId) }
            .filter { isValidDetail(it) }
        if (normalizedDetails.isNotEmpty()) {
            dao.insertAllDetails(normalizedDetails)
        }

        // 批量插入所有会话记录
        val normalizedSessions = sessions
            .map { normalizeSession(it).copy(id = 0, deviceId = currentDeviceId) }
            .filter { isValidSession(it) }
        if (normalizedSessions.isNotEmpty()) {
            dao.insertAllSessions(normalizedSessions)
        }

        // 使用内存计算重建聚合记录，避免 N+1 查询
        rebuildImportedBookTotalsFast()
    }

    private suspend fun importSingleDetailRecords(details: List<ReadRecordDetail>) {
        details.forEach { detail ->
            importSingleDetail(detail)
        }
    }

    private suspend fun importSingleSessionRecords(sessions: List<ReadRecordSession>) {
        sessions.forEach { session ->
            importSingleSession(session)
        }
    }

    private suspend fun importSingleRecord(record: ReadRecord) {
        val normalized = normalizeRecord(record).copy(deviceId = getCurrentDeviceId())
        if (!isValidRecord(normalized)) return
        val existing = dao.getReadRecord(
            normalized.deviceId,
            normalized.bookName,
            normalized.bookAuthor
        )
        if (existing == null || existing.readTime < normalized.readTime) {
            dao.insert(normalized)
        }
    }

    private suspend fun importSingleDetail(detail: ReadRecordDetail) {
        val normalized = normalizeDetail(detail).copy(deviceId = getCurrentDeviceId())
        if (!isValidDetail(normalized)) return
        val existing = dao.getDetail(
            normalized.deviceId,
            normalized.bookName,
            normalized.bookAuthor,
            normalized.date
        )
        if (existing == null || existing.readTime < normalized.readTime) {
            dao.insertDetail(normalized)
        }
    }

    private suspend fun importSingleSession(session: ReadRecordSession) {
        val normalized = normalizeSession(session).copy(id = 0, deviceId = getCurrentDeviceId())
        if (!isValidSession(normalized)) return
        val existing = dao.getSessionExact(
            normalized.deviceId,
            normalized.bookName,
            normalized.bookAuthor,
            normalized.startTime,
            normalized.endTime,
            normalized.words,
            normalized.source
        )
        if (existing == null) {
            dao.insertSession(normalized)
        }
    }

    /**
     * 快速重建导入书籍的聚合记录。
     *
     * 使用内存计算替代逐书查询，将 3N+1 次 DB 操作降为 3 次 SELECT + 1 次批量 INSERT。
     */
    private suspend fun rebuildImportedBookTotalsFast() {
        val currentDeviceId = getCurrentDeviceId()
        val allRecords = dao.all.associateBy { it.bookName to it.bookAuthor }
        val detailsByBook = dao.getAllDetailsList()
            .filter { it.deviceId == currentDeviceId }
            .groupBy { it.bookName to it.bookAuthor }
        val sessionsByBook = dao.getAllSessionsList()
            .filter { it.deviceId == currentDeviceId }
            .groupBy { it.bookName to it.bookAuthor }

        val allBookKeys = mutableSetOf<Pair<String, String>>()
        allBookKeys.addAll(allRecords.keys)
        allBookKeys.addAll(detailsByBook.keys)
        allBookKeys.addAll(sessionsByBook.keys)

        val toUpsert = mutableListOf<ReadRecord>()
        for ((bookName, bookAuthor) in allBookKeys) {
            val bookSessions = sessionsByBook[bookName to bookAuthor].orEmpty()
            val bookDetails = detailsByBook[bookName to bookAuthor].orEmpty()
            val existingRecord = allRecords[bookName to bookAuthor]

            val sessionTotalTime = bookSessions.sumOf { it.endTime - it.startTime }
            val detailTotalTime = bookDetails.sumOf { it.readTime }
            val totalTime = max(max(sessionTotalTime, detailTotalTime), existingRecord?.readTime ?: 0L)

            val sessionLastRead = bookSessions.maxOfOrNull { it.endTime } ?: 0L
            val detailLastRead = bookDetails.maxOfOrNull { it.lastReadTime } ?: 0L
            val lastRead = max(max(sessionLastRead, detailLastRead), existingRecord?.lastRead ?: 0L)

            if (existingRecord != null) {
                if (existingRecord.readTime != totalTime || existingRecord.lastRead != lastRead) {
                    toUpsert.add(
                        existingRecord.copy(readTime = totalTime, lastRead = lastRead)
                    )
                }
            } else {
                toUpsert.add(
                    ReadRecord(
                        deviceId = currentDeviceId,
                        bookName = bookName,
                        bookAuthor = bookAuthor,
                        readTime = totalTime,
                        lastRead = lastRead
                    )
                )
            }
        }

        if (toUpsert.isNotEmpty()) {
            dao.insertAll(toUpsert)
        }
    }
}
