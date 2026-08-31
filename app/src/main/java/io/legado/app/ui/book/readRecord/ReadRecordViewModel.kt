package io.legado.app.ui.book.readRecord

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.dao.DailyReadStat
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.entities.readRecord.ReadRecordSource
import io.legado.app.data.repository.BookRepository
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import splitties.init.appCtx

data class RecordIdentity(
    val deviceId: String,
    val bookName: String,
    val bookAuthor: String,
    val source: String
)

data class ReadRecordUiState(
    val isLoading: Boolean = true,
    val totalReadTime: Long = 0,
    val todayReadTime: Long = 0,
    val monthReadTime: Long = 0,
    val readWords: Long = 0,
    val readingSpeed: Long = 0,
    val timeOfDay: Map<String, Long> = emptyMap(),
    val completionRate: Int = 0,
    val completedBookCount: Int = 0,
    val activeDays: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val todayBookCount: Int = 0,
    val groupedRecords: Map<String, List<ReadRecordDetail>> = emptyMap(),
    val timelineRecords: Map<String, List<ReadRecordSession>> = emptyMap(),
    val latestRecords: List<ReadRecord> = emptyList(),
    val readTimeRecords: List<ReadRecord> = emptyList(),
    val selectedDate: LocalDate? = null,
    val searchKey: String? = null,
    val dailyReadCounts: Map<LocalDate, Int> = emptyMap(),
    val dailyReadTimes: Map<LocalDate, Long> = emptyMap(),
    val isSelectionMode: Boolean = false,
    val selectedRecords: Set<RecordIdentity> = emptySet(),
    val timelineHasMore: Boolean = false,
    val timelineLoadingMore: Boolean = false
)

enum class DisplayMode {
    AGGREGATE,
    TIMELINE,
    LATEST,
    READ_TIME
}

/**
 * 按显示模式加载的额外数据。
 * - AGGREGATE 模式加载 details
 * - TIMELINE 模式不再使用此结构（改用 _timelineSessions 分页加载）
 * - LATEST / READ_TIME 模式不需要额外数据（使用 recordsFlow）
 */
private data class ModeData(
    val details: List<ReadRecordDetail>? = null
)

/** 轻量级统计数据（SQL 聚合，始终加载）。 */
private data class StatsData(
    val totalReadTime: Long,
    val dailyStats: List<DailyReadStat>,
    val todayReadTime: Long,
    val todayBookCount: Int,
    val totalReadWords: Long,
    val timeSlotStats: Map<ReadTimeSlot, Long>
)

private data class CompletionData(
    val rate: Int = 0,
    val completedCount: Int = 0
)

/** 搜索 + 日期筛选状态。 */
private data class FilterState(
    val query: String,
    val dateStr: String?,
    val source: String?
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReadRecordViewModel : ViewModel() {

    private val repository = ReadRecordRepository(appDb.readRecordDao)
    private val bookRepository = BookRepository()

    private val coverPathCache = ConcurrentHashMap<String, String?>()
    private val chapterTitleCache = ConcurrentHashMap<String, String?>()
    private val chapterCountCache = ConcurrentHashMap<String, Int>()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

    private val _displayMode = MutableStateFlow(loadDisplayMode())
    val displayMode = _displayMode.asStateFlow()
    private val _enableReadRecord = MutableStateFlow(AppConfig.enableReadRecord)
    val enableReadRecord = _enableReadRecord.asStateFlow()
    private val _searchKey = MutableStateFlow("")
    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    private val _sourceFilter = MutableStateFlow<String?>(null)
    val sourceFilter = _sourceFilter.asStateFlow()
    private val _isSelectionMode = MutableStateFlow(false)
    private val _selectedRecords = MutableStateFlow<Set<RecordIdentity>>(emptySet())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (appCtx.getPrefInt(PreferKey.readRecordRepairVersion) < ReadRecordRepository.CURRENT_REPAIR_VERSION) {
                repository.repairRecords { bookName ->
                    bookRepository.getAuthorByBookName(bookName)
                }
                appCtx.putPrefInt(
                    PreferKey.readRecordRepairVersion,
                    ReadRecordRepository.CURRENT_REPAIR_VERSION
                )
            }
        }
    }

    private fun loadDisplayMode(): DisplayMode {
        val savedOrdinal = appCtx.getPrefInt(PreferKey.readRecordDisplayMode, DisplayMode.AGGREGATE.ordinal)
        return enumValueOf<DisplayMode>(DisplayMode.values().getOrNull(savedOrdinal)?.name ?: DisplayMode.AGGREGATE.name)
    }

    // ==================== 拆分后的 Flow ====================

    /** 搜索 + 日期筛选状态（派生自 _searchKey 和 _selectedDate） */
    private val filterState = combine(_searchKey, _selectedDate, _sourceFilter) { query, date, source ->
        FilterState(query, date?.format(DateTimeFormatter.ISO_LOCAL_DATE), source)
    }

    /**
     * 轻量级统计数据 Flow —— SQL 聚合，始终加载。
     */
    private val baseStatsFlow = combine(
        repository.getTotalReadTime(),
        repository.getDailyStats(),
        repository.getReadTimeByDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)),
        repository.getBookCountByDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
    ) { total, daily, todayTime, todayCount ->
        StatsData(total, daily, todayTime, todayCount, 0L, emptyMap())
    }

    /** 统计用详情流使用分页加载，避免一次性从 Room 读取超大 Cursor。 */
    private val statsDetailsFlow = filterState.flatMapLatest { filter ->
        repository.getFilteredDetails(filter.query, filter.dateStr)
            .map { details -> details.filter { filter.source == null || it.source == filter.source } }
    }

    private val statsSessionsFlow = filterState.flatMapLatest { filter ->
        repository.getFilteredSessions(filter.query, filter.dateStr)
            .map { sessions -> sessions.filter { filter.source == null || it.source == filter.source } }
    }

    private val statsFlow = combine(
        baseStatsFlow,
        filterState,
        statsDetailsFlow,
        statsSessionsFlow
    ) { base, filter, details, sessions ->
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val hasFilter = filter.query.isNotBlank() || filter.dateStr != null || filter.source != null
        val dailyStats = if (!hasFilter) {
            base.dailyStats
        } else {
            details.groupBy { it.date }.map { (date, items) ->
                DailyReadStat(date, items.size, items.sumOf { it.readTime })
            }.sortedByDescending { it.date }
        }
        val totalReadTime = if (!hasFilter) base.totalReadTime else details.sumOf { it.readTime }
        val todayReadTime = if (!hasFilter) base.todayReadTime else details.filter { it.date == today }.sumOf { it.readTime }
        val todayBookCount = if (!hasFilter) base.todayBookCount else details.asSequence()
            .filter { it.date == today }
            .map { Triple(it.deviceId, it.bookName, it.bookAuthor) }
            .distinct()
            .count()
        val timeSlots = sessions.groupBy {
            val hour = Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).hour
            readTimeSlotForHour(hour)
        }.mapValues { (_, items) -> items.sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) } }
        StatsData(totalReadTime, dailyStats, todayReadTime, todayBookCount, details.sumOf { it.readWords }, timeSlots)
    }

    /**
     * 阅读记录 Flow（SummaryCard + LATEST / READ_TIME 模式共用）。
     */
    private val recordsFlow = filterState.flatMapLatest { filter ->
        val records = if (filter.dateStr == null) {
            repository.getRecordsWithDetailTime(filter.query)
        } else {
            repository.getRecordsByDate(filter.query, filter.dateStr)
        }
        records.map { items -> items.filter { filter.source == null || it.source == filter.source } }
    }

    private val completionFlow = recordsFlow.mapLatest { records ->
        if (records.isEmpty()) return@mapLatest CompletionData()
        val percentages = coroutineScope {
            records.map { record ->
                async(Dispatchers.IO) {
                    val key = "${record.bookName}\u0000${record.bookAuthor}"
                    val chapterCount = chapterCountCache[key]
                        ?: bookRepository.getChapterCount(record.bookName, record.bookAuthor).also {
                            chapterCountCache[key] = it
                        }
                    completionPercent(
                        record.durChapterIndex,
                        chapterCount
                    )
                }
            }.awaitAll()
        }
        CompletionData(
            rate = percentages.average().toInt(),
            completedCount = percentages.count { it >= 100 }
        )
    }

    /**
     * 按显示模式加载的额外数据 Flow。
     * TIMELINE 模式不再使用此 Flow（改用 _timelineSessions 分页加载）。
     */
    private val modeDataFlow: StateFlow<ModeData> = _displayMode
        .flatMapLatest { mode ->
            when (mode) {
                DisplayMode.AGGREGATE, DisplayMode.LATEST, DisplayMode.READ_TIME -> filterState.flatMapLatest { filter ->
                    repository.getFilteredDetails(filter.query, filter.dateStr)
                        .map { details -> ModeData(details = details.filter { filter.source == null || it.source == filter.source }) }
                }
                DisplayMode.TIMELINE -> flowOf(ModeData())
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ModeData())

    /** 选择模式状态 */
    private val selectionState = combine(_isSelectionMode, _selectedRecords) { isSel, selRecs ->
        isSel to selRecs
    }

    // ==================== TIMELINE 分页加载 ====================

    private val timelinePageSize = 50

    /** 当前已加载的 timeline sessions（已合并+分组后的 Map） */
    private val _timelineSessions = MutableStateFlow<Map<String, List<ReadRecordSession>>>(emptyMap())
    /** 当前已加载的原始 sessions（用于增量追加） */
    private val _timelineRawSessions = MutableStateFlow<List<ReadRecordSession>>(emptyList())
    /** 是否还有更多数据可加载 */
    private val _timelineHasMore = MutableStateFlow(false)
    /** 是否正在加载更多 */
    private val _timelineLoadingMore = MutableStateFlow(false)

    /** 用于取消上一次首屏加载的 Job */
    private var timelineReloadJob: Job? = null

    init {
        // 当 displayMode / 搜索 / 日期筛选 变化时，重置 timeline 分页并重新加载首屏
        viewModelScope.launch(Dispatchers.IO) {
            combine(_displayMode, filterState) { mode, filter -> mode to filter }
                .collect { (mode, filter) ->
                    if (mode == DisplayMode.TIMELINE) {
                        timelineReloadJob?.cancel()
                        timelineReloadJob = launch(Dispatchers.IO) {
                        loadTimelineFirstPage(filter.query, filter.dateStr, filter.source)
                        }
                    }
                }
        }
    }

    private suspend fun loadTimelineFirstPage(query: String, dateFilter: String?, source: String?) {
        _timelineLoadingMore.value = true
        val sessions = withContext(Dispatchers.IO) {
            repository.loadSessionsPage(query, dateFilter, null, timelinePageSize)
        }
        val filtered = sessions.filter { source == null || it.source == source }
        _timelineRawSessions.value = filtered
        _timelineSessions.value = buildTimelineMap(filtered)
        _timelineHasMore.value = sessions.size >= timelinePageSize
        _timelineLoadingMore.value = false
        // 批量预取章节标题和封面路径
        prefetchMetadata(filtered)
    }

    fun loadMoreTimelineSessions() {
        if (_timelineLoadingMore.value || !_timelineHasMore.value) return
        if (_displayMode.value != DisplayMode.TIMELINE) return
        val currentRaw = _timelineRawSessions.value
        if (currentRaw.isEmpty()) return
        val lastSession = currentRaw.last()
        val query = _searchKey.value
        val dateStr = _selectedDate.value?.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val source = _sourceFilter.value
        viewModelScope.launch(Dispatchers.IO) {
            _timelineLoadingMore.value = true
            val moreSessions = repository.loadSessionsPage(
                query, dateStr, lastSession.startTime, timelinePageSize
            )
            if (moreSessions.isEmpty()) {
                _timelineHasMore.value = false
                _timelineLoadingMore.value = false
                return@launch
            }
            val filteredMore = moreSessions.filter { source == null || it.source == source }
            val combinedRaw = currentRaw + filteredMore
            _timelineRawSessions.value = combinedRaw
            _timelineSessions.value = buildTimelineMap(combinedRaw)
            _timelineHasMore.value = moreSessions.size >= timelinePageSize
            _timelineLoadingMore.value = false
            // 批量预取新增 sessions 的元数据
            prefetchMetadata(filteredMore)
        }
    }

    /**
     * 批量预取 sessions 中 unique 书对的章节标题和封面路径，填充缓存。
     * 使用并发协程加速预取。
     */
    private suspend fun prefetchMetadata(sessions: List<ReadRecordSession>) {
        val uniqueBooks = sessions.map { it.bookName to it.bookAuthor }.distinct()
        coroutineScope {
            uniqueBooks.map { (bookName, bookAuthor) ->
                async(Dispatchers.IO) {
                    val key = cacheKey(bookName, bookAuthor)
                    // 章节标题：缓存未命中才预取（ConcurrentHashMap 不允许 null value）
                    if (!chapterTitleCache.containsKey(key)) {
                        val title = bookRepository.getBookDurChapterTitle(bookName, bookAuthor)
                        title?.let { chapterTitleCache[key] = it }
                    }
                    // 封面路径：缓存未命中才预取（可能触发联网搜封面）
                    if (!coverPathCache.containsKey(key)) {
                        val cover = bookRepository.getBookCoverByNameAndAuthor(bookName, bookAuthor)
                        cover?.let { coverPathCache[key] = it }
                    }
                }
            }.awaitAll()
        }
    }

    /** Timeline + 选择模式合并状态（用于 combine，避免超过 5 路合流） */
    private data class ExtraState(
        val timelineRecords: Map<String, List<ReadRecordSession>>,
        val timelineHasMore: Boolean,
        val timelineLoadingMore: Boolean,
        val isSelectionMode: Boolean,
        val selectedRecords: Set<RecordIdentity>
    )

    private val extraState = combine(
        _timelineSessions,
        _timelineHasMore,
        _timelineLoadingMore,
        selectionState
    ) { sessions, hasMore, loadingMore, selection ->
        val (isSel, selRecs) = selection
        ExtraState(sessions, hasMore, loadingMore, isSel, selRecs)
    }

    private val extraAndCompletionState = combine(extraState, completionFlow) { extra, completion ->
        extra to completion
    }

    /**
     * UI 状态 Flow —— 组合统计数据、记录、模式数据、筛选状态和额外状态（timeline 分页 + 选择模式）。
     */
    val uiState: StateFlow<ReadRecordUiState> = combine(
        statsFlow,
        recordsFlow,
        modeDataFlow,
        filterState,
        extraAndCompletionState
    ) { stats, records, modeData, filter, extraAndCompletion ->
        val (extra, completion) = extraAndCompletion
        val selectedDate = filter.dateStr?.let { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }

        // AGGREGATE 模式：details 按 date 分组
        val groupedRecords = modeData.details
            ?.groupBy { it.date }
            ?: emptyMap()
        val readWords = stats.totalReadWords
        val effectiveTime = stats.totalReadTime
        val timeOfDay = stats.timeSlotStats
            .filterValues { it > 0L }
            .mapKeys { it.key.label }

        // daily stats 转 Map
        val dailyReadCounts = stats.dailyStats.associate {
            LocalDate.parse(it.date, DateTimeFormatter.ISO_LOCAL_DATE) to it.readCount
        }
        val dailyReadTimes = stats.dailyStats.associate {
            LocalDate.parse(it.date, DateTimeFormatter.ISO_LOCAL_DATE) to it.totalReadTime
        }

        // 活跃日 = 有阅读记录的天数
        val activeDays = dailyReadCounts.size

        // 本月阅读时长 = 当月各天阅读时长之和
        val currentMonth = LocalDate.now().withDayOfMonth(1)
        val monthReadTime = dailyReadTimes.entries
            .filter { it.key >= currentMonth }
            .sumOf { it.value }

        // 连续阅读天数：从有阅读记录的日期集合中计算
        val sortedActiveDates = dailyReadCounts.keys.sorted()
        val activeDateSet = dailyReadCounts.keys
        val currentStreak = calcCurrentStreak(activeDateSet)
        val longestStreak = calcLongestStreak(sortedActiveDates)

        ReadRecordUiState(
            isLoading = false,
            totalReadTime = stats.totalReadTime,
            todayReadTime = stats.todayReadTime,
            monthReadTime = monthReadTime,
            readWords = readWords,
            readingSpeed = calculateReadingSpeed(readWords, effectiveTime),
            timeOfDay = timeOfDay,
            completionRate = completion.rate,
            completedBookCount = completion.completedCount,
            activeDays = activeDays,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            todayBookCount = stats.todayBookCount,
            groupedRecords = groupedRecords,
            timelineRecords = extra.timelineRecords,
            latestRecords = records,
            readTimeRecords = records.sortedByDescending { it.readTime },
            selectedDate = selectedDate,
            searchKey = filter.query,
            dailyReadCounts = dailyReadCounts,
            dailyReadTimes = dailyReadTimes,
            isSelectionMode = extra.isSelectionMode,
            selectedRecords = extra.selectedRecords,
            timelineHasMore = extra.timelineHasMore,
            timelineLoadingMore = extra.timelineLoadingMore
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ReadRecordUiState(isLoading = true)
        )

    /**
     * 构建时间线 Map：按日期分组 + 合并连续会话。
     */
    private fun buildTimelineMap(sessions: List<ReadRecordSession>): Map<String, List<ReadRecordSession>> {
        return sessions
            .groupBy { dateFormat.format(Date(it.startTime)) }
            .mapValues { (_, daySessions) -> mergeContinuousSessions(daySessions).reversed() }
            .toSortedMap(compareByDescending { it })
    }

    fun setSearchKey(query: String) {
        _searchKey.value = query
    }

    fun setDisplayMode(mode: DisplayMode) {
        _displayMode.value = mode
        appCtx.putPrefInt(PreferKey.readRecordDisplayMode, mode.ordinal)
    }

    fun setEnableReadRecord(enabled: Boolean) {
        AppConfig.enableReadRecord = enabled
        _enableReadRecord.value = enabled
    }

    fun setSelectedDate(date: LocalDate?) {
        _selectedDate.value = date
    }

    fun setSourceFilter(source: String?) {
        _sourceFilter.value = source
    }

    fun deleteDetail(detail: ReadRecordDetail) {
        viewModelScope.launch { repository.deleteDetail(detail) }
    }

    fun deleteSession(session: ReadRecordSession) {
        viewModelScope.launch { repository.deleteSession(session) }
    }

    fun deleteReadRecord(record: ReadRecord) {
        viewModelScope.launch {
            val selectedDate = _selectedDate.value?.format(DateTimeFormatter.ISO_LOCAL_DATE)
            if (selectedDate == null) {
                repository.deleteReadRecord(record)
            } else {
                repository.deleteReadRecordByDate(record, selectedDate)
            }
        }
    }

    private fun mergeContinuousSessions(sessions: List<ReadRecordSession>): List<ReadRecordSession> {
        if (sessions.isEmpty()) return emptyList()
        val sortedSessions = sessions.sortedBy { it.startTime }
        val mergedList = mutableListOf<ReadRecordSession>()
        mergedList.add(sortedSessions.first().copy())

        val gapLimit = 20 * 60 * 1000L

        for (i in 1 until sortedSessions.size) {
            val current = sortedSessions[i]
            val last = mergedList.last()
            if (current.bookName == last.bookName &&
                current.bookAuthor == last.bookAuthor &&
                (current.startTime - last.endTime) <= gapLimit
            ) {
                mergedList[mergedList.lastIndex] = last.copy(endTime = maxOf(last.endTime, current.endTime))
            } else {
                mergedList.add(current.copy())
            }
        }
        return mergedList
    }

    suspend fun getChapterTitle(bookName: String, bookAuthor: String, chapterIndexLong: Long): String? {
        return bookRepository.getChapterTitle(bookName, bookAuthor, chapterIndexLong.toInt())
    }

    suspend fun getBookDurChapterTitle(bookName: String, bookAuthor: String): String? {
        val key = cacheKey(bookName, bookAuthor)
        chapterTitleCache[key]?.let { return it }
        val result = bookRepository.getBookDurChapterTitle(bookName, bookAuthor)
        result?.let { chapterTitleCache[key] = it }
        return result
    }

    suspend fun getBookCover(bookName: String, bookAuthor: String): String? {
        val key = cacheKey(bookName, bookAuthor)
        coverPathCache[key]?.let { return it }
        // 查询内部可能触发同步联网搜封面(java.ajax)，必须离开主线程执行
        val result = withContext(Dispatchers.IO) {
            bookRepository.getBookCoverByNameAndAuthor(bookName, bookAuthor)
        }
        result?.let { coverPathCache[key] = it }
        return result
    }

    fun getConfiguredDefaultCover(): String? {
        return bookRepository.getConfiguredDefaultCover()
    }

    suspend fun getMergeCandidates(targetRecord: ReadRecord): List<ReadRecord> {
        return repository.getMergeCandidates(targetRecord)
    }

    fun mergeReadRecords(targetRecord: ReadRecord, sourceRecords: List<ReadRecord>) {
        if (sourceRecords.isEmpty()) return
        viewModelScope.launch {
            repository.mergeReadRecordInto(targetRecord, sourceRecords)
        }
    }

    fun mergeAllSameNameRecords(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            onResult(repository.mergeAllSameNameRecords())
        }
    }

    fun enterSelectionMode(record: ReadRecord) {
        _isSelectionMode.value = true
        _selectedRecords.value = setOf(recordIdentity(record.deviceId, record.bookName, record.bookAuthor, record.source))
    }

    fun enterSelectionMode(detail: ReadRecordDetail) {
        _isSelectionMode.value = true
        _selectedRecords.value = setOf(recordIdentity(detail.deviceId, detail.bookName, detail.bookAuthor, detail.source))
    }

    fun enterSelectionMode(session: ReadRecordSession) {
        _isSelectionMode.value = true
        _selectedRecords.value = setOf(recordIdentity(session.deviceId, session.bookName, session.bookAuthor, session.source))
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedRecords.value = emptySet()
    }

    fun toggleRecordSelection(record: ReadRecord) {
        val identity = recordIdentity(record.deviceId, record.bookName, record.bookAuthor, record.source)
        toggleIdentitySelection(identity)
    }

    fun toggleRecordSelection(detail: ReadRecordDetail) {
        val identity = recordIdentity(detail.deviceId, detail.bookName, detail.bookAuthor, detail.source)
        toggleIdentitySelection(identity)
    }

    fun toggleRecordSelection(session: ReadRecordSession) {
        val identity = recordIdentity(session.deviceId, session.bookName, session.bookAuthor, session.source)
        toggleIdentitySelection(identity)
    }

    private fun toggleIdentitySelection(identity: RecordIdentity) {
        val currentSelection = _selectedRecords.value.toMutableSet()
        if (currentSelection.contains(identity)) {
            currentSelection.remove(identity)
            if (currentSelection.isEmpty()) {
                _isSelectionMode.value = false
            }
        } else {
            currentSelection.add(identity)
        }
        _selectedRecords.value = currentSelection
    }

    fun selectAllRecords(displayMode: DisplayMode) {
        val allIdentities = _selectedRecords.value.toMutableSet()
        when (displayMode) {
            DisplayMode.LATEST -> {
                allIdentities.addAll(uiState.value.latestRecords.map { 
                    recordIdentity(it.deviceId, it.bookName, it.bookAuthor, it.source)
                })
            }
            DisplayMode.READ_TIME -> {
                allIdentities.addAll(uiState.value.readTimeRecords.map { 
                    recordIdentity(it.deviceId, it.bookName, it.bookAuthor, it.source)
                })
            }
            DisplayMode.AGGREGATE -> {
                allIdentities.addAll(uiState.value.groupedRecords.values.flatten().map { 
                    recordIdentity(it.deviceId, it.bookName, it.bookAuthor, it.source)
                })
            }
            DisplayMode.TIMELINE -> {
                allIdentities.addAll(uiState.value.timelineRecords.values.flatten().map { 
                    recordIdentity(it.deviceId, it.bookName, it.bookAuthor, it.source)
                })
            }
        }
        _selectedRecords.value = allIdentities
    }

    fun deleteSelectedRecords() {
        viewModelScope.launch {
            val selectedList = _selectedRecords.value.map { identity ->
                ReadRecord(
                    deviceId = identity.deviceId,
                    bookName = identity.bookName,
                    bookAuthor = identity.bookAuthor,
                    source = identity.source
                )
            }
            selectedList.forEach { record ->
                repository.deleteReadRecord(record)
            }
            exitSelectionMode()
        }
    }

    fun addTestReadRecord(bookName: String, bookAuthor: String): ReadRecord {
        val deviceId = AppConst.androidId
        val now = System.currentTimeMillis()
        val record = ReadRecord(
            deviceId = deviceId,
            bookName = bookName,
            bookAuthor = bookAuthor,
            readTime = 30 * 60 * 1000L,
            lastRead = now
        )
        val session = ReadRecordSession(
            deviceId = deviceId,
            bookName = bookName,
            bookAuthor = bookAuthor,
            startTime = now - 30 * 60 * 1000L,
            endTime = now,
            words = 5000L
        )
        viewModelScope.launch {
            repository.saveReadSession(session)
        }
        return record
    }

    fun isSelected(record: ReadRecord): Boolean {
        return _selectedRecords.value.contains(recordIdentity(record.deviceId, record.bookName, record.bookAuthor, record.source))
    }

    fun isSelected(detail: ReadRecordDetail): Boolean {
        return _selectedRecords.value.contains(recordIdentity(detail.deviceId, detail.bookName, detail.bookAuthor, detail.source))
    }

    fun isSelected(session: ReadRecordSession): Boolean {
        return _selectedRecords.value.contains(recordIdentity(session.deviceId, session.bookName, session.bookAuthor, session.source))
    }

    private fun cacheKey(bookName: String, bookAuthor: String) = "$bookName|$bookAuthor"
}

private fun recordIdentity(
    deviceId: String,
    bookName: String,
    bookAuthor: String,
    source: String = ReadRecordSource.TEXT.name
): RecordIdentity {
    return RecordIdentity(deviceId, bookName, bookAuthor, source)
}

/**
 * 当前连续阅读天数：从今天（或昨天）开始往前数，连续有阅读记录的最大天数。
 * 参数为 Set 以保证 `in` 操作 O(1) 查找。
 */
private fun calcCurrentStreak(activeDates: Set<LocalDate>): Int {
    if (activeDates.isEmpty()) return 0
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    // 今天有记录则从今天开始；今天没有但昨天有则从昨天开始
    val start = when {
        today in activeDates -> today
        yesterday in activeDates -> yesterday
        else -> return 0
    }
    var streak = 0
    var cursor = start
    while (cursor in activeDates) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

/**
 * 历史最长连续阅读天数：遍历所有活跃日期，找出最长连续区间。
 */
private fun calcLongestStreak(activeDates: List<LocalDate>): Int {
    if (activeDates.isEmpty()) return 0
    var longest = 1
    var current = 1
    for (i in 1 until activeDates.size) {
        if (activeDates[i] == activeDates[i - 1].plusDays(1)) {
            current++
            if (current > longest) longest = current
        } else {
            current = 1
        }
    }
    return longest
}
