package Forinxy.jiexi

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import Forinxy.jiexi.data.BatchAuthorParseSummary
import Forinxy.jiexi.data.BatchParseResult
import Forinxy.jiexi.data.GalleryMedia
import Forinxy.jiexi.data.HistoryRepository
import Forinxy.jiexi.data.LyricLine
import Forinxy.jiexi.data.ParseResult
import Forinxy.jiexi.data.VideoQualityOption
import Forinxy.jiexi.data.galleryItems
import Forinxy.jiexi.data.totalMediaAssetCount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class SaveState(
    val isSaving: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val progress: Float = 0f,
    val label: String = "",
    val indeterminate: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L
)

/** 保存前的大小确认请求：视频总大小超过阈值时由 UI 弹窗询问是否继续 */
data class SaveSizeConfirmRequest(
    val sizeBytes: Long,
    val limitBytes: Long,
    val continuation: kotlinx.coroutines.CompletableDeferred<Boolean>
)

/** 保存前画质选择请求：UI 弹窗列出各画质及大小，用户选择 */
data class QualitySelectionRequest(
    val videoId: String,
    val title: String,
    val options: List<VideoQualityOption>,
    val continuation: kotlinx.coroutines.CompletableDeferred<VideoQualityOption?>
)

/** 多链接批量解析状态（主页粘贴整段多链接文本时使用） */
sealed interface MultiLinkParseState {
    data object Idle : MultiLinkParseState

    data class Running(
        val current: Int,
        val total: Int,
        val succeeded: Int,
        val failed: Int,
        val lastTitle: String?
    ) : MultiLinkParseState

    data class Finished(
        val total: Int,
        val succeeded: Int,
        val failed: Int
    ) : MultiLinkParseState

    data class Error(val msg: String) : MultiLinkParseState
}

class ParserViewModel(application: Application) : AndroidViewModel(application) {
    private data class BatchPositionSelection(
        val startIndex: Int,
        val endIndex: Int
    ) {
        val count: Int
            get() = endIndex - startIndex + 1
    }

    private data class BatchRequestOptions(
        val requestedCount: Int?,
        val seedRequestedCount: Int?,
        val seedStartIndex: Int,
        val selection: BatchPositionSelection?
    )

    private data class BatchWorkParseOutcome(
        val result: ParseResult.Success?,
        val error: Throwable?,
        val riskSuspected: Boolean
    )

    private data class VideoUrlResolution(
        val url: String?,
        val refreshedAt: Long? = null
    )

    private data class CachedPlaybackUrl(
        val url: String,
        val cachedAtSeconds: Long
    )

    private data class MediaSaveTask(
        val progressKey: String,
        val fileName: String,
        val mimeType: String,
        val timestamp: Long,
        val index: Int = 0,
        val subPathOverride: String? = null,
        val extensionOverride: String? = null,
        val resolveRequest: suspend () -> DouyinDownloadRequest?
    )

    private val _parseResult = mutableStateOf<ParseResult>(ParseResult.Idle)
    val parseResult: State<ParseResult> = _parseResult

    private val _multiLinkParseState = mutableStateOf<MultiLinkParseState>(MultiLinkParseState.Idle)
    val multiLinkParseState: State<MultiLinkParseState> = _multiLinkParseState

    private val _batchParseResult = mutableStateOf<BatchParseResult>(BatchParseResult.Idle)
    val batchParseResult: State<BatchParseResult> = _batchParseResult

    private val _historyState = mutableStateOf<List<ParseResult.Success>>(emptyList())
    val historyState: State<List<ParseResult.Success>> = _historyState

    private val _batchHistoryState = mutableStateOf<List<BatchAuthorParseSummary>>(emptyList())
    val batchHistoryState: State<List<BatchAuthorParseSummary>> = _batchHistoryState

    /** 保存前画质选择（UI 观察此状态弹窗） */
    private val _qualitySelectionRequest = mutableStateOf<QualitySelectionRequest?>(null)
    val qualitySelectionRequest: State<QualitySelectionRequest?> = _qualitySelectionRequest

    /** 解析成功后直接在结果区展示的画质选项（含探测到的大小），供点击直接下载 */
    private val _videoQualityOptions = mutableStateOf<List<VideoQualityOption>?>(null)
    val videoQualityOptions: State<List<VideoQualityOption>?> = _videoQualityOptions

    fun respondQualitySelection(option: VideoQualityOption?) {
        _qualitySelectionRequest.value?.continuation?.complete(option)
        _qualitySelectionRequest.value = null
    }

    private val _saveState = mutableStateOf(SaveState())
    val saveState: State<SaveState> = _saveState

    /**
     * 用户粘贴的是作者主页链接（/share/user/ 或 /user/ 等）时置为（原始输入, 提取到的主页链接），
     * 用于引导跳转到批量解析页解析该作者全部作品；置空表示无待引导主页链接。
     */
    private val _homepageLinkDetected = mutableStateOf<Pair<String, String>?>(null)
    val homepageLinkDetected: State<Pair<String, String>?> = _homepageLinkDetected

    fun clearHomepageLinkDetected() {
        _homepageLinkDetected.value = null
    }

    /** 淇濆瓨鍓嶅ぇ灏忕‘璁わ紙UI 瑙傚療姝ょ姸鎬佸脊绐楋級 */
    private val _sizeConfirmRequest = mutableStateOf<SaveSizeConfirmRequest?>(null)
    val sizeConfirmRequest: State<SaveSizeConfirmRequest?> = _sizeConfirmRequest

    fun respondSizeConfirm(continueSave: Boolean) {
        _sizeConfirmRequest.value?.continuation?.complete(continueSave)
        _sizeConfirmRequest.value = null
    }

    private val mobileUserAgent: String by lazy { Forinxy.jiexi.NativeLib.getParserUserAgent() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            private val cookieStore = hashMapOf<String, List<Cookie>>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        })
        .build()

    private val downloadClient = client.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 24
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // 鍘绘帀 HTTP_1.1 闄愬埗锛氳 OkHttp 鍗忓晢 HTTP/2锛堟姈闊?CDN 鏀寔锛夛紝澶氳矾澶嶇敤鎻愬崌鍚炲悙
        .build()

    private val gson = Gson()
    private val historyRepository = HistoryRepository(application, gson)
    private val livePhotoPlayableUrlCache = ConcurrentHashMap<String, CachedPlaybackUrl>()
    private val livePhotoPlayableUrlCacheFile = File(
        application.filesDir,
        "live_photo_play_url_cache.json"
    )
    private val livePhotoPlayableUrlCacheMutex = Mutex()
    @Volatile
    private var livePhotoPlayableUrlCacheLoaded = false
    private val authorBatchManager = AuthorBatchManager(application, gson, client, historyRepository)
    private val workWebApiBridge = DouyinAuthorWebApiBridge(application.applicationContext)
    private val localParseEngine by lazy { LocalParseEngine(application) }
    private var parseJob: Job? = null
    private var batchParseJob: Job? = null
    private var qualityResolveJob: Job? = null
    private var parseRequestToken = 0L
    private var batchParseRequestToken = 0L
    @Volatile
    private var anonymousWarmupStarted = false

    companion object {
        private const val IMAGE_SAVE_CONCURRENCY = 3
        private const val QUALITY_SELECTION_TIMEOUT_MILLIS = 120_000L
        private const val MAX_LIVE_PHOTO_PLAY_URL_CACHE_ENTRIES = 256

        /** 鎾斁鍦板潃鏈夋晥鏈燂細瓒呰繃璇ユ椂闀胯涓鸿繃鏈燂紝鎾斁鍓嶉噸鏂拌В鏋愮洿閾?*/
        private const val PLAY_URL_STALE_MS = 6L * 60L * 60L * 1000L

        private const val SERVICE_UNAVAILABLE_MESSAGE = "\u670d\u52a1\u4e0d\u53ef\u7528"
        private const val EMPTY_INPUT_MESSAGE = "\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a"
        private const val INVALID_INPUT_MESSAGE = "\u8bf7\u8f93\u5165\u5206\u4eab\u94fe\u63a5\u6216\u4f5c\u54c1 ID\uff08\u652f\u6301\u6296\u97f3/\u5feb\u624b/\u5c0f\u7ea2\u4e66/B\u7ad9/\u5fae\u535a/\u5934\u6761\u7b49\uff09"
        private const val INVALID_AUTHOR_INPUT_MESSAGE = "\u8bf7\u8f93\u5165\u4f5c\u8005\u4e3b\u9875\u5206\u4eab\u94fe\u63a5"
        private const val SAVE_VIDEO_PREPARE_LABEL = "\u6b63\u5728\u51c6\u5907\u89c6\u9891..."
        private const val SAVE_VIDEO_LABEL = "\u6b63\u5728\u4fdd\u5b58\u89c6\u9891..."
        private const val SAVE_IMAGE_LABEL = "\u6b63\u5728\u4e0b\u8f7d\u5e76\u4fdd\u5b58..."
        private const val SAVE_BATCH_LABEL = "\u6b63\u5728\u4fdd\u5b58\u6279\u91cf\u5a92\u4f53..."
        private const val SAVE_VIDEO_SUCCESS = "\u89c6\u9891\u4fdd\u5b58\u6210\u529f"
        private const val SAVE_IMAGE_ALL_SUCCESS = "\u56fe\u7247\u5168\u90e8\u4fdd\u5b58\u6210\u529f"
        private const val SAVE_IMAGE_PARTIAL_FAILED = "\u90e8\u5206\u56fe\u7247\u4fdd\u5b58\u5931\u8d25"
        private const val SAVE_MUSIC_LABEL = "\u6b63\u5728\u4fdd\u5b58\u97f3\u4e50..."
        private const val SAVE_MUSIC_SUCCESS = "\u97f3\u4e50\u4fdd\u5b58\u6210\u529f"
        private const val SAVE_MUSIC_FAILED = "\u4fdd\u5b58\u97f3\u4e50\u5931\u8d25\uff1a\u65e0\u6cd5\u83b7\u53d6\u97f3\u4e50\u5730\u5740"
        private const val SAVE_COVER_LABEL = "\u6b63\u5728\u4fdd\u5b58\u5c01\u9762..."
        private const val SAVE_COVER_SUCCESS = "\u5c01\u9762\u4fdd\u5b58\u6210\u529f"
        private const val SAVE_COVER_FAILED = "\u4fdd\u5b58\u5c01\u9762\u5931\u8d25\uff1a\u65e0\u6cd5\u83b7\u53d6\u5c01\u9762\u5730\u5740"
        private const val SAVE_LYRICS_LABEL = "\u6b63\u5728\u4fdd\u5b58\u6b4c\u8bcd..."
        private const val SAVE_LYRICS_SUCCESS = "\u6b4c\u8bcd\u4fdd\u5b58\u6210\u529f"
        private const val SAVE_LYRICS_FAILED = "\u6b4c\u8bcd\u4fdd\u5b58\u5931\u8d25"
        private const val MUSIC_SAVE_SUBPATH = "dyparse/music"
        private const val SAVE_BATCH_ALL_SUCCESS = "\u6279\u91cf\u4fdd\u5b58\u5b8c\u6210"
        private const val SAVE_BATCH_PARTIAL_FAILED = "\u6279\u91cf\u4fdd\u5b58\u5b8c\u6210\uff0c\u4f46\u6709\u90e8\u5206\u5931\u8d25"
        private const val SAVE_BATCH_ALL_FAILED = "\u6279\u91cf\u4fdd\u5b58\u5931\u8d25"
        private const val BATCH_SAVE_CONCURRENCY = 3
        private const val BATCH_WORK_PARSE_MAX_ATTEMPTS = 1
        private const val BATCH_WORK_PARSE_BASE_DELAY_MS = 350L
        private const val BATCH_WORK_PARSE_INTERVAL_MS = 180L

        private val digitsRegex by lazy { Regex(NativeLib.getRegex(5)) }
    }

    init {
        loadHistory()
        loadBatchHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            // 瀹屾暣鍔犺浇鍘嗗彶锛堝惈 playUrl/rawPlayUrl锛夛紝淇濊瘉鍘嗗彶椤电偣鍑诲嵆鍙挱鏀撅紱
            // 杞婚噺鎶曞奖鏌ヨ淇濈暀鍦?Repository 灞傦紝涓嶅湪姝ゅ浣跨敤浠ラ伩鍏嶈交閲?瀹屾暣瀵硅薄娣风敤銆?
            val history = historyRepository.getParseHistory()
            withContext(Dispatchers.Main.immediate) {
                _historyState.value = history
            }
        }
    }

    /** 鍘嗗彶璇︽儏鎸夐渶鍔犺浇锛氭寜涓婚敭璇诲彇瀹屾暣 payloadJson锛堟挱鏀?鍥鹃泦/淇濆瓨鎵€闇€瀛楁锛?*/
    suspend fun loadHistoryDetail(videoId: String, parseTimestamp: Long): ParseResult.Success? {
        return historyRepository.getParseHistoryDetail(videoId, parseTimestamp)
    }

    private fun loadBatchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val history = authorBatchManager.loadHistoryIndex()
            withContext(Dispatchers.Main.immediate) {
                _batchHistoryState.value = history
            }
        }
    }

    fun parse(text: String) {
        val input = text.trim()
        parseJob?.cancel()

        // 环境检测：调试器/代理/VPN/Hook 等异常环境直接拒绝，提示服务不可用
        if (!SecurityGuard.enforce(getApplication())) {
            _parseResult.value = ParseResult.Error(SERVICE_UNAVAILABLE_MESSAGE)
            return
        }

        if (input.isBlank()) {
            _parseResult.value = ParseResult.Error(EMPTY_INPUT_MESSAGE)
            return
        }

        // 多链接批量解析：粘贴的整段文本含 2 条及以上可解析输入时，逐条解析并统一写入历史
        val multiInputs = ClipboardShareContent.extractAllParseInputs(input)
        if (multiInputs.size > 1) {
            startMultiLinkParse(multiInputs)
            return
        }

        // 显式作者主页链接（/share/user/ 或 /user/）不是单个作品，直接引导跳批量解析页
        detectExplicitHomepageLink(input)?.let { homepageLink ->
            _homepageLinkDetected.value = homepageLink
            _parseResult.value = ParseResult.Idle
            return
        }

        val isId = input.matches(digitsRegex)
        val detectedPlatform = Forinxy.jiexi.builtin.Platform.detect(input)
        if (!isId && detectedPlatform == null) {
            _parseResult.value = ParseResult.Error(INVALID_INPUT_MESSAGE)
            return
        }

        _parseResult.value = ParseResult.Loading
        _videoQualityOptions.value = null
        _multiLinkParseState.value = MultiLinkParseState.Idle

        val requestToken = ++parseRequestToken
        parseJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                // 短链需跟随重定向才能确认是否主页：在后台检测，命中则改为引导提示
                detectHomepageViaRedirect(input)?.let { homepageLink ->
                    if (requestToken == parseRequestToken) {
                        _homepageLinkDetected.value = homepageLink
                        _parseResult.value = ParseResult.Idle
                    }
                    return@launch
                }
                var result = performParse(input, useCookie = true)
                val parseDurationMs = System.currentTimeMillis() - startTime
                val duration = parseDurationMs / 1000.0

                if (result is ParseResult.Success) {
                    result = result.copy(
                        duration = duration,
                        lastPlayUrlUpdateTime = System.currentTimeMillis() / 1000
                    )
                    saveParseResult(result)
                    resolveVideoQualityOptions(result)
                }

                if (requestToken == parseRequestToken) {
                    _parseResult.value = result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestToken == parseRequestToken) {
                    _parseResult.value = ParseResult.Error("\u89e3\u6790\u5931\u8d25: ${e.message}")
                }
            }
        }
    }

    /**
     * 多链接批量解析：对拆出的每条输入依次走单条解析，成功的写入解析历史，
     * 失败条目不写历史并继续下一条；结束后刷新历史列表。
     */
    private fun startMultiLinkParse(inputs: List<String>) {
        if (inputs.isEmpty()) {
            _parseResult.value = ParseResult.Error(INVALID_INPUT_MESSAGE)
            return
        }
        _multiLinkParseState.value = MultiLinkParseState.Running(
            current = 0,
            total = inputs.size,
            succeeded = 0,
            failed = 0,
            lastTitle = null
        )
        _parseResult.value = ParseResult.Idle
        _videoQualityOptions.value = null

        val requestToken = ++parseRequestToken
        parseJob = viewModelScope.launch {
            var succeeded = 0
            var failed = 0
            var lastTitle: String? = null
            val interval = workRequestIntervalMs()
            for ((index, input) in inputs.withIndex()) {
                if (requestToken != parseRequestToken) {
                    throw CancellationException("Superseded by a newer parse request")
                }
                val result = try {
                    val startTime = System.currentTimeMillis()
                    var parsed = performParse(input, useCookie = true)
                    if (parsed is ParseResult.Success) {
                        parsed = parsed.copy(
                            duration = (System.currentTimeMillis() - startTime) / 1000.0,
                            lastPlayUrlUpdateTime = System.currentTimeMillis() / 1000
                        )
                    }
                    parsed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ParseResult.Error("\u89e3\u6790\u5931\u8d25: ${e.message}")
                }

                if (result is ParseResult.Success) {
                    succeeded += 1
                    lastTitle = result.title
                    saveParseResult(result)
                } else {
                    failed += 1
                }

                if (requestToken == parseRequestToken) {
                    _multiLinkParseState.value = MultiLinkParseState.Running(
                        current = index + 1,
                        total = inputs.size,
                        succeeded = succeeded,
                        failed = failed,
                        lastTitle = lastTitle
                    )
                }

                if (index < inputs.lastIndex && interval > 0L) {
                    delay(DouyinRequestLimiter.jitteredIntervalMs(interval))
                }
            }

            if (requestToken == parseRequestToken) {
                _multiLinkParseState.value = MultiLinkParseState.Finished(
                    total = inputs.size,
                    succeeded = succeeded,
                    failed = failed
                )
            }
        }
    }

    fun parseAuthorBatch(text: String, countInput: String, positionInput: String = "") {
        val input = text.trim()

        // 鐜妫€娴嬶細寮傚父鐜鐩存帴鎷掔粷锛屾彁绀烘湇鍔′笉鍙敤
        if (!SecurityGuard.enforce(getApplication())) {
            _batchParseResult.value = BatchParseResult.Error(SERVICE_UNAVAILABLE_MESSAGE)
            return
        }

        val requestOptions = runCatching {
            resolveBatchRequestOptions(countInput, positionInput)
        }.getOrElse {
            _batchParseResult.value = BatchParseResult.Error(it.message ?: "\u8bf7\u8f93\u5165\u6b63\u786e\u7684\u6307\u5b9a\u6392\u5e8f")
            return
        }
        val requestedCount = requestOptions.requestedCount
        val seedRequestedCount = requestOptions.seedRequestedCount
        batchParseJob?.cancel()

        if (input.isBlank()) {
            _batchParseResult.value = BatchParseResult.Error(EMPTY_INPUT_MESSAGE)
            return
        }

        if (!input.contains("douyin", ignoreCase = true)) {
            _batchParseResult.value = BatchParseResult.Error(INVALID_AUTHOR_INPUT_MESSAGE)
            return
        }

        _batchParseResult.value = BatchParseResult.Loading(
            currentCount = 0,
            requestedCount = requestedCount,
            message = "\u6b63\u5728\u89e3\u6790\u4f5c\u8005\u4e3b\u9875..."
        )

        val requestToken = ++batchParseRequestToken
        batchParseJob = viewModelScope.launch {
            try {
                val (summary, works) = authorBatchManager.parseAuthorBatch(
                    input = input,
                    requestedCount = seedRequestedCount,
                    collectStartIndex = requestOptions.seedStartIndex
                ) { author, currentCount ->
                    if (requestToken == batchParseRequestToken) {
                        _batchParseResult.value = BatchParseResult.Loading(
                            author = author,
                            currentCount = currentCount,
                            requestedCount = requestedCount,
                            message = "\u6b63\u5728\u83b7\u53d6\u4f5c\u54c1 ID..."
                        )
                    }
                }

                parseAndPersistBatchWorks(
                    requestedCount = requestedCount,
                    selection = null,
                    requestToken = requestToken,
                    summary = summary,
                    works = works
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestToken == batchParseRequestToken) {
                    _batchParseResult.value = BatchParseResult.Error(
                        e.message ?: "\u6279\u91cf\u89e3\u6790\u5931\u8d25"
                    )
                }
            }
        }
    }

    fun parseAuthorBatchFromCapturedIds(
        text: String,
        countInput: String,
        positionInput: String = "",
        awemeIds: List<String>,
        author: String? = null,
        authorUid: String? = null,
        authorSecUid: String? = null,
        avatarUrl: String? = null,
        isComplete: Boolean = false
    ) {
        val input = text.trim()

        // 鐜妫€娴嬶細寮傚父鐜鐩存帴鎷掔粷锛屾彁绀烘湇鍔′笉鍙敤
        if (!SecurityGuard.enforce(getApplication())) {
            _batchParseResult.value = BatchParseResult.Error(SERVICE_UNAVAILABLE_MESSAGE)
            return
        }

        val requestOptions = runCatching {
            resolveBatchRequestOptions(countInput, positionInput)
        }.getOrElse {
            _batchParseResult.value = BatchParseResult.Error(it.message ?: "\u8bf7\u8f93\u5165\u6b63\u786e\u7684\u6307\u5b9a\u6392\u5e8f")
            return
        }
        val requestedCount = requestOptions.requestedCount
        val seedRequestedCount = requestOptions.seedRequestedCount
        batchParseJob?.cancel()

        if (input.isBlank()) {
            _batchParseResult.value = BatchParseResult.Error(EMPTY_INPUT_MESSAGE)
            return
        }

        _batchParseResult.value = BatchParseResult.Loading(
            author = author,
            currentCount = if (requestOptions.selection == null) awemeIds.size else 0,
            requestedCount = requestedCount,
            message = "\u5df2\u83b7\u53d6\u4f5c\u54c1 ID\uff0c\u6b63\u5728\u89e3\u6790\u4f5c\u54c1\u8be6\u60c5..."
        )

        val requestToken = ++batchParseRequestToken
        batchParseJob = viewModelScope.launch {
            try {
                val (summary, works) = authorBatchManager.createAuthorBatchFromAwemeIds(
                    input = input,
                    requestedCount = seedRequestedCount,
                    collectStartIndex = requestOptions.seedStartIndex,
                    awemeIds = awemeIds,
                    author = author,
                    authorUid = authorUid,
                    authorSecUid = authorSecUid,
                    avatarUrl = avatarUrl,
                    isComplete = isComplete
                )

                parseAndPersistBatchWorks(
                    requestedCount = requestedCount,
                    selection = null,
                    requestToken = requestToken,
                    summary = summary,
                    works = works
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestToken == batchParseRequestToken) {
                    _batchParseResult.value = BatchParseResult.Error(
                        e.message ?: "\u6279\u91cf\u89e3\u6790\u5931\u8d25"
                    )
                }
            }
        }
    }

    suspend fun resolveAuthorCaptureUrl(input: String): String {
        return authorBatchManager.resolveAuthorCaptureUrl(input)
    }

    private suspend fun parseAndPersistBatchWorks(
        requestedCount: Int?,
        selection: BatchPositionSelection?,
        requestToken: Long,
        summary: BatchAuthorParseSummary,
        works: List<ParseResult.Success>
    ) {
        val targetWorks = filterBatchWorksBySelection(works, selection)
        if (selection != null && targetWorks.isEmpty()) {
            throw IOException("\u672a\u83b7\u53d6\u5230\u6307\u5b9a\u6392\u5e8f\u7684\u4f5c\u54c1")
        }

        val batchSettings = BatchParsePreferences.getSettings(getApplication())
        val failureStopThreshold = batchSettings.consecutiveFailureStopCount
        val reparsedWorks = mutableListOf<ParseResult.Success>()
        var failedWorkCount = 0
        var consecutiveFailureCount = 0
        var consecutiveRiskFailureCount = 0
        val riskStopThreshold = DouyinRequestLimiter.riskStopThreshold(failureStopThreshold)
        for ((index, seedWork) in targetWorks.withIndex()) {
            if (requestToken != batchParseRequestToken) {
                throw CancellationException("Superseded by a newer batch parse request")
            }
            // 鏈 seed 鏄惁鏃犲獟浣擄紙闇€瑕佽蛋 detail API 閫愭潯瑙ｆ瀽锛?
            val needsDetailParse = !seedWork.hasReusableBatchMedia()

            val parseOutcome = parseBatchWorkWithRetry(seedWork)
            val parsedWork = parseOutcome.result
            if (parsedWork != null) {
                if (parsedWork.matchesBatchAuthor(seedWork, summary)) {
                    consecutiveFailureCount = 0
                    consecutiveRiskFailureCount = 0
                    reparsedWorks += parsedWork.copy(
                        author = parsedWork.author.ifBlank { summary.author },
                        authorUid = parsedWork.authorUid ?: seedWork.authorUid ?: summary.authorUid,
                        authorSecUid = parsedWork.authorSecUid ?: summary.authorSecUid,
                        inputUrl = seedWork.inputUrl,
                        resolvedUrl = parsedWork.resolvedUrl ?: seedWork.resolvedUrl,
                        source = "batch",
                        batchId = summary.batchId
                    )
                } else {
                    failedWorkCount += 1
                    consecutiveFailureCount = 0
                    consecutiveRiskFailureCount = 0
                    Log.w(
                        "ParserViewModel",
                        "Skip foreign batch work ${parsedWork.videoId}: " +
                            "expected uid=${seedWork.authorUid ?: summary.authorUid}, " +
                            "sec=${seedWork.authorSecUid ?: summary.authorSecUid}; " +
                            "actual uid=${parsedWork.authorUid}, sec=${parsedWork.authorSecUid}"
                    )
                }
            } else {
                failedWorkCount += 1
                consecutiveFailureCount += 1
                if (parseOutcome.riskSuspected) {
                    consecutiveRiskFailureCount += 1
                } else {
                    consecutiveRiskFailureCount = 0
                }
            }

            if (requestToken == batchParseRequestToken) {
                val partialSummary = buildBatchProgressSummary(
                    summary = summary,
                    works = reparsedWorks,
                    requestedCount = requestedCount
                )
                _batchParseResult.value = BatchParseResult.Loading(
                    author = summary.author,
                    currentCount = index + 1,
                    requestedCount = requestedCount ?: works.size,
                    message = if (consecutiveRiskFailureCount > 0) {
                        "\u7591\u4f3c\u89e6\u53d1\u98ce\u63a7\uff0c\u6b63\u5728\u964d\u901f\u51b7\u5374\u540e\u7ee7\u7eed..."
                    } else if (failedWorkCount > 0) {
                        "\u6b63\u5728\u89e3\u6790\u4f5c\u54c1\u8be6\u60c5\uff0c\u5931\u8d25\u4f5c\u54c1\u5df2\u81ea\u52a8\u91cd\u8bd5..."
                    } else if (needsDetailParse) {
                        "\u6b63\u5728\u89e3\u6790\u4f5c\u54c1\u8be6\u60c5..."
                    } else {
                        "\u6b63\u5728\u52a0\u8f7d\u4f5c\u54c1\u5217\u8868..."
                    },
                    summary = partialSummary,
                    works = reparsedWorks.toList(),
                    failedCount = failedWorkCount
                )
            }

            if (parsedWork == null && parseOutcome.riskSuspected) {
                val riskState = DouyinRequestLimiter.reportRiskFailure()
                if (consecutiveRiskFailureCount >= riskStopThreshold) {
                    Log.w(
                        "ParserViewModel",
                        "Batch parse stopped after $consecutiveRiskFailureCount suspected risk failures",
                        parseOutcome.error
                    )
                    break
                }
                delay(DouyinRequestLimiter.jitteredIntervalMs(riskState.cooldownMs))
            }

            if (
                parsedWork == null &&
                failureStopThreshold > 0 &&
                consecutiveFailureCount >= failureStopThreshold
            ) {
                Log.w(
                    "ParserViewModel",
                    "Batch parse stopped after $consecutiveFailureCount consecutive failures"
                )
                break
            }

            if (requestedCount != null && reparsedWorks.size >= requestedCount) {
                break
            }

            if (index < targetWorks.lastIndex) {
                val pauseMs = when {
                    consecutiveFailureCount >= 3 -> maxOf(
                        batchSettings.workIntervalMs.toLong(),
                        BATCH_WORK_PARSE_BASE_DELAY_MS * 4
                    )
                    parsedWork == null -> maxOf(
                        batchSettings.workIntervalMs.toLong(),
                        BATCH_WORK_PARSE_BASE_DELAY_MS * 2
                    )
                    else -> batchSettings.workIntervalMs.toLong()
                }
                if (parsedWork == null) {
                    delay(DouyinRequestLimiter.jitteredIntervalMs(pauseMs))
                }
            }
        }

        val normalizedWorks = if (requestedCount != null) {
            reparsedWorks.take(requestedCount)
        } else {
            reparsedWorks
        }
        if (normalizedWorks.isEmpty()) {
            throw IOException("\u5df2\u83b7\u53d6\u5230\u4f5c\u54c1 ID\uff0c\u4f46\u8be6\u60c5\u5168\u90e8\u89e3\u6790\u5931\u8d25\u6216\u4e0d\u5c5e\u4e8e\u8be5\u4f5c\u8005")
        }

        val primaryWork = normalizedWorks.firstOrNull()
        val finalSummary = summary.copy(
            author = summary.author.takeUnless { it == "\u672a\u77e5\u4f5c\u8005" }
                ?: primaryWork?.author
                ?: summary.author,
            authorUid = summary.authorUid ?: primaryWork?.authorUid,
            authorSecUid = summary.authorSecUid ?: primaryWork?.authorSecUid,
            avatarUrl = summary.avatarUrl ?: primaryWork?.cover,
            requestedCount = requestedCount,
            parsedCount = normalizedWorks.size,
            videoCount = normalizedWorks.count { it.type == "video" },
            imageCount = normalizedWorks.count { it.type == "image" },
            totalMediaCount = normalizedWorks.sumOf { it.totalMediaAssetCount },
            parseTimestamp = System.currentTimeMillis() / 1000
        )
        val finalWorks = normalizedWorks.map {
            it.copy(
                parseTimestamp = finalSummary.parseTimestamp,
                lastPlayUrlUpdateTime = it.lastPlayUrlUpdateTime
                    ?: System.currentTimeMillis() / 1000
            )
        }

        val updatedHistory = authorBatchManager.saveBatchHistory(finalSummary, finalWorks)

        withContext(Dispatchers.Main.immediate) {
            _batchHistoryState.value = updatedHistory
        }

        if (requestToken == batchParseRequestToken) {
            _batchParseResult.value = BatchParseResult.Success(
                summary = finalSummary,
                works = finalWorks
            )
        }
    }

    private fun buildBatchProgressSummary(
        summary: BatchAuthorParseSummary,
        works: List<ParseResult.Success>,
        requestedCount: Int?
    ): BatchAuthorParseSummary {
        return summary.copy(
            requestedCount = requestedCount,
            parsedCount = works.size,
            videoCount = works.count { it.type == "video" },
            imageCount = works.count { it.type == "image" },
            totalMediaCount = works.sumOf { it.totalMediaAssetCount },
            isComplete = false
        )
    }

    private fun buildBatchSeedRequestCount(requestedCount: Int?): Int? {
        if (requestedCount == null) {
            return null
        }
        val singlePageLimit = 18
        val buffer = when {
            requestedCount <= 5 -> 2
            requestedCount <= 12 -> 4
            requestedCount <= 30 -> 8
            else -> minOf(maxOf(requestedCount / 3, 10), 20)
        }
        val maxBuffer = if (requestedCount < singlePageLimit) {
            (singlePageLimit - requestedCount).coerceAtLeast(0)
        } else {
            Int.MAX_VALUE
        }
        return requestedCount + minOf(buffer, maxBuffer)
    }

    private fun resolveBatchRequestOptions(
        countInput: String,
        positionInput: String
    ): BatchRequestOptions {
        val requestedCount = countInput.trim().toIntOrNull()?.takeIf { it > 0 }
        val selection = parseBatchPositionSelection(positionInput)
        return if (selection != null) {
            BatchRequestOptions(
                requestedCount = selection.count,
                seedRequestedCount = selection.endIndex,
                seedStartIndex = selection.startIndex,
                selection = selection
            )
        } else {
            BatchRequestOptions(
                requestedCount = requestedCount,
                seedRequestedCount = buildBatchSeedRequestCount(requestedCount),
                seedStartIndex = 1,
                selection = null
            )
        }
    }

    private fun parseBatchPositionSelection(input: String): BatchPositionSelection? {
        val normalized = input.trim()
        if (normalized.isBlank()) {
            return null
        }

        val rangeMatch = Regex("^(\\d+)\\s*[~锝?]\\s*(\\d+)$")
            .matchEntire(normalized)
        if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].toIntOrNull()
            val end = rangeMatch.groupValues[2].toIntOrNull()
            if (start == null || end == null || start <= 0 || end <= 0 || start > end) {
                throw IOException("\u8bf7\u8f93\u5165\u6b63\u786e\u7684\u6307\u5b9a\u6392\u5e8f\uff0c\u4f8b\u5982 10 \u6216 10~11")
            }
            return BatchPositionSelection(start, end)
        }

        val single = normalized.toIntOrNull()
        if (single != null && single > 0) {
            return BatchPositionSelection(single, single)
        }

        throw IOException("\u8bf7\u8f93\u5165\u6b63\u786e\u7684\u6307\u5b9a\u6392\u5e8f\uff0c\u4f8b\u5982 10 \u6216 10~11")
    }

    private fun filterBatchWorksBySelection(
        works: List<ParseResult.Success>,
        selection: BatchPositionSelection?
    ): List<ParseResult.Success> {
        if (selection == null) {
            return works
        }
        val startIndex = (selection.startIndex - 1).coerceAtLeast(0)
        return works.drop(startIndex).take(selection.count)
    }

    private fun ParseResult.Success.hasReusableBatchMedia(): Boolean {
        return when (type) {
            "image" -> !images.isNullOrEmpty()
            "video" -> !rawPlayUrl.isNullOrBlank() || !playUrl.isNullOrBlank()
            else -> false
        }
    }

    private fun ParseResult.Success.matchesBatchAuthor(
        seedWork: ParseResult.Success,
        summary: BatchAuthorParseSummary
    ): Boolean {
        return BatchAuthorMatcher.matchesResultAuthor(
            result = this,
            expectedUid = seedWork.authorUid ?: summary.authorUid,
            expectedSecUid = seedWork.authorSecUid ?: summary.authorSecUid
        )
    }

    private suspend fun parseBatchWorkWithRetry(seedWork: ParseResult.Success): BatchWorkParseOutcome {
        // 鍒楄〃鐩村嚭锛歴eed 鐩存帴浣滀负缁撴灉灞曠ず锛堝皝闈?鏍囬/ID 宸插氨缁級銆?
        // 鎾斁/淇濆瓨鏃舵墠瑙ｆ瀽璇︽儏锛坮esolveValidVideoUrl / resolveVideoSaveRequest 浼氳ˉ鍏級銆?
        // 涓嶅啀閫愭潯璋?detail API锛堥伩鍏嶉鎺?+ 婊¤冻"鍒楄〃鐩村嚭銆佺偣鍑绘墠瑙ｆ瀽"锛夈€?
        return BatchWorkParseOutcome(
            result = seedWork,
            error = null,
            riskSuspected = false
        )
    }

private suspend fun performParse(input: String, useCookie: Boolean = false): ParseResult = withContext(Dispatchers.IO) {
        serverOrLocalParse(input = input, useCookie = useCookie)
    }

    /**
     * 解析通道选择：已配置服务器 → 走服务器（原逻辑，支持 quality_list/原画质/批量 cookie）；
     * 未配置服务器（占位地址）→ 走 App 内置抖音登录会话本地解析（无需自建服务器）。
     */
    private suspend fun serverOrLocalParse(
        input: String,
        useCookie: Boolean = false,
        original: Boolean = false,
        highest: Boolean = false,
        batchId: String? = null
    ): ParseResult = withContext(Dispatchers.IO) {
        val cfg = ServerConfigStore.getConfig()
        if (ServerConfigStore.isPlaceholder(cfg.apiBase)) {
            localParseEngine.parse(input)
        } else {
            val serverResult = ServerApiClient.parse(
                input = input,
                useCookie = useCookie,
                original = original,
                highest = highest,
                batchId = batchId
            )
            // 内置服务器分享页通道对图集（/note/）无数据，失败时回退本地 detail API
            if (
                serverResult is ParseResult.Error &&
                batchId == null &&
                ServerConfigStore.isInternal(cfg.apiBase) &&
                looksLikeGalleryInput(input)
            ) {
                localParseEngine.parse(input)
            } else {
                serverResult
            }
        }
    }

    /** 判断输入是否可能为图集图文（/note/ 路径或短链），用于内置服务器失败时的本地回退 */
    private fun looksLikeGalleryInput(input: String): Boolean {
        val normalized = input.lowercase()
        return normalized.contains("/note/") ||
            normalized.contains("share/note") ||
            normalized.matches(Regex(".*v\\.douyin\\.com/.*"))
    }

    /** 同步检测：输入已含显式作者主页路径（/share/user/ /user/）时返回 (原文, 主页链接)，否则 null */
    private fun detectExplicitHomepageLink(input: String): Pair<String, String>? {
        val trimmed = input.trim()
        if (!trimmed.contains("douyin", ignoreCase = true)) return null
        val url = Regex("https?://[^\\s\\u4e00-\\u9fa5]+").find(trimmed)?.value
            ?: trimmed
        if (
            url.contains("/share/user/", ignoreCase = true) ||
            url.contains("/user/", ignoreCase = true)
        ) {
            return Pair(trimmed, url)
        }
        return null
    }

    /** 后台检测：短链跟随重定向后若是作者主页，返回 (原文, 主页链接)，否则 null */
    private suspend fun detectHomepageViaRedirect(input: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        val url = Regex("https?://[^\\s\\u4e00-\\u9fa5]+").find(trimmed)?.value
            ?: if (trimmed.matches(Regex("https?://\\S+"))) trimmed else return@withContext null
        if (!url.matches(Regex("https?://v\\.douyin\\.com/[\\w/=]+"))) {
            return@withContext null
        }
        val finalUrl = runCatching {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", mobileUserAgent)
                .header("Referer", "https://www.douyin.com/")
                .build()
            client.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        }.getOrNull() ?: return@withContext null
        if (
            finalUrl.contains("/share/user/", ignoreCase = true) ||
            finalUrl.contains("/user/", ignoreCase = true)
        ) {
            return@withContext Pair(trimmed, finalUrl)
        }
        null
    }

    /**
     * App 鍚姩鍚庣殑鍚庡彴闈欓粯棰勭儹锛氭湭璁剧疆鐧诲綍 Cookie 涓旀病鏈夊彲鐢ㄥ尶鍚嶄細璇濇椂锛?
     * 鎻愬墠鐢ㄧ灞?WebView 璁块棶 douyin.com 鎷垮埌娴忚鍣ㄧ湡瀹炲尶鍚嶈韩浠?
     * 锛坱twid/msToken 绛夛級骞舵寔涔呭寲锛屾妸"棣栨瑙ｆ瀽鏃剁殑棰勭儹"鎻愬墠鍒板惎鍔ㄩ樁娈碉紝
     * 閬垮厤棣栬鍚庣涓€娆¤В鏋愮瓑寰?WebView 鍐峰惎鍔?+ 棰勭儹鍏ㄩ摼璺€?
     * - 宸茬櫥褰曪紙鏈?Cookie锛夋垨宸叉湁 24h 鍐呭尶鍚嶄細璇濇椂鐩存帴璺宠繃
     * - 澶辫触闈欓粯蹇界暐锛屼笉褰卞搷浠讳綍鍔熻兘锛堥娆¤В鏋愪粛浼氭寜闇€棰勭儹鍏滃簳锛?
     */
    fun warmUpAnonymousSessionInBackground(delayMs: Long = 1_500L) {
        if (anonymousWarmupStarted) {
            return
        }
        anonymousWarmupStarted = true

        val appContext = getApplication<Application>().applicationContext
        if (DouyinAuthStore.hasAuthenticatedCookie(appContext)) {
            return
        }
        if (!AnonymousSessionStore.getCookie(appContext).isNullOrBlank()) {
            return
        }

        viewModelScope.launch {
            delay(delayMs)
            runCatching {
                val warmedCookie = workWebApiBridge.warmUpDouyinCookies()
                if (warmedCookie.isNotBlank()) {
                    AnonymousSessionStore.saveCookie(appContext, warmedCookie)
                }
            }.onFailure { throwable ->
                Log.w("ParserViewModel", "Background anonymous session warm-up failed", throwable)
            }
        }
    }

    private fun workRequestIntervalMs(): Long {
        return BatchParsePreferences.getSettings(getApplication()).workIntervalMs.toLong()
    }

    /**
     * 鍗曟潯瑙ｆ瀽鐨勮姹傝妭濂忥細鐢ㄦ埛鎵嬪姩瑙ｆ瀽棰戠巼浣庛€佸崟娆℃祦绋嬫湰韬姹傛暟灏戯紝
     * 鏃犻渶娌跨敤鎵归噺瑙ｆ瀽鐨勫彲閰嶇疆闀块棿闅旓紙榛樿 1500ms+锛夛紝鐢ㄨ緝鐭棿闅斿嵆鍙?
     * 鏄捐憲鍔犲揩鍗曟瑙ｆ瀽锛屽悓鏃朵粛鐣欏嚭闃查鎺х殑缂撳啿銆?
     */
    private fun singleParseIntervalMs(): Long {
        val batchInterval = workRequestIntervalMs()
        return if (batchInterval > 0L) {
            (batchInterval / 3).coerceIn(320L, 900L)
        } else {
            0L
        }
    }


    private suspend fun saveParseResult(result: ParseResult.Success) = withContext(Dispatchers.IO) {
        publishParseHistory(historyRepository.saveParseResult(result))
    }

    suspend fun getBatchHistoryWorks(batchId: String): List<ParseResult.Success> {
        return authorBatchManager.getBatchHistoryWorks(batchId)
    }

    fun deleteHistoryItem(item: ParseResult.Success) {
        viewModelScope.launch(Dispatchers.IO) {
            publishParseHistory(historyRepository.deleteParseResult(item))
        }
    }

    fun deleteBatchHistoryItem(summary: BatchAuthorParseSummary) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedHistory = authorBatchManager.deleteBatchHistoryItem(summary)
            withContext(Dispatchers.Main.immediate) {
                _batchHistoryState.value = updatedHistory
            }
        }
    }

    fun clearParseHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.clearParseHistory()
            withContext(Dispatchers.Main.immediate) {
                _historyState.value = emptyList()
            }
        }
    }

    fun clearBatchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.clearBatchHistory()
            withContext(Dispatchers.Main.immediate) {
                _batchHistoryState.value = emptyList()
            }
        }
    }


    suspend fun getValidVideoUrl(item: ParseResult.Success): String? {
        return resolveValidVideoUrl(item).url
    }

    suspend fun refreshValidVideoUrl(item: ParseResult.Success): String? {
        return resolveValidVideoUrl(item, forceRefresh = true).url
    }

    /** 保存/下载请求头：UA + Referer + 本地 cookie（抖音 CDN 校验，缺 Referer/Cookie 会 403） */
    private fun buildSaveHeaders(url: String): Map<String, String> {
        val cookie = DouyinAuthStore.getCookie(getApplication<Application>().applicationContext)
        return DouyinVideoDownloadResolver.buildDownloadHeaders(url, cookie)
    }
    /** 画质选择结果 */
    private sealed class QualitySelectionOutcome {
        object NoOptions : QualitySelectionOutcome()
        object Cancelled : QualitySelectionOutcome()
        data class Chosen(val option: VideoQualityOption) : QualitySelectionOutcome()
    }

    /**
     * 弹出画质选择对话框。返回：
     * - NoOptions：服务器未返回画质列表（老接口），回退到原有保存逻辑
     * - Chosen：用户选定具体画质
     * - Cancelled：用户取消或等待超时
     */
    private suspend fun requestVideoQualitySelection(
        representative: ParseResult.Success
    ): QualitySelectionOutcome {
        val options = withContext(Dispatchers.IO) {
            resolveQualityOptionsWithProbedSizes(representative)
        }
        if (options.isEmpty()) {
            return QualitySelectionOutcome.NoOptions
        }
        val continuation = CompletableDeferred<VideoQualityOption?>()
        _qualitySelectionRequest.value = QualitySelectionRequest(
            videoId = representative.videoId,
            title = representative.title,
            options = options,
            continuation = continuation
        )
        val selected = withTimeoutOrNull(QUALITY_SELECTION_TIMEOUT_MILLIS) {
            continuation.await()
        }
        _qualitySelectionRequest.value = null
        return if (selected == null) {
            QualitySelectionOutcome.Cancelled
        } else {
            QualitySelectionOutcome.Chosen(selected)
        }
    }

    /** 画质列表补全实际大小（服务器未给 data_size 时 HEAD 预探测） */
    private suspend fun resolveQualityOptionsWithProbedSizes(
        representative: ParseResult.Success
    ): List<VideoQualityOption> {
        val base = representative.qualityList.orEmpty()
        if (base.isEmpty()) {
            return emptyList()
        }
        return coroutineScope {
            base.map { option ->
                async {
                    if (option.sizeBytes != null) {
                        option
                    } else {
                        val probe = probeDownloadSize(
                            DouyinDownloadRequest(
                                url = option.url,
                                headers = buildSaveHeaders(option.url)
                            )
                        )
                        val size = probe?.totalBytes?.takeIf { it > 0L }
                        if (size != null) option.copy(sizeBytes = size) else option
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * 解析成功后异步探测各画质大小，发布到 [videoQualityOptions]，
     * 供结果区「画质＋大小」按钮组直接点击下载。非视频 / 无画质列表时置空。
     */
    private fun resolveVideoQualityOptions(result: ParseResult.Success) {
        qualityResolveJob?.cancel()
        if (result.type != "video" || result.qualityList.isNullOrEmpty()) {
            _videoQualityOptions.value = null
            return
        }
        qualityResolveJob = viewModelScope.launch(Dispatchers.IO) {
            val options = resolveQualityOptionsWithProbedSizes(result)
            withContext(Dispatchers.Main.immediate) {
                if (_parseResult.value is ParseResult.Success) {
                    _videoQualityOptions.value = options
                }
            }
        }
    }

    /** 直接按指定画质下载视频：结果区画质按钮点击入口，跳过弹窗直接保存 */
    fun saveVideoWithQuality(
        context: Context,
        result: ParseResult.Success,
        option: VideoQualityOption
    ) {
        if (_saveState.value.isSaving || result.type != "video") {
            return
        }
        if (!SecurityGuard.enforce(context.applicationContext)) {
            Toast.makeText(context, SERVICE_UNAVAILABLE_MESSAGE, Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            saveVideoCore(context = context, result = result, requestedQuality = option)
        }
    }


    private suspend fun resolveVideoSaveRequest(
        item: ParseResult.Success,
        requestedQuality: VideoQualityOption? = null
    ): DouyinDownloadRequest? {
        // 用户在画质选择弹窗里指定了具体画质：直接用该画质地址保存
        if (requestedQuality != null && requestedQuality.url.isNotBlank()) {
            return DouyinDownloadRequest(
                url = requestedQuality.url,
                headers = buildSaveHeaders(requestedQuality.url)
            )
        }
        if (item.type != "video") {
            return null
        }
        // 淇濆瓨鏃跺尯鍒嗭紙鏈€楂樼敾璐ㄤ紭鍏堢骇 > 鍘熺敾璐?> 榛樿锛夛細
        // - 鏈€楂樼敾璐ㄥ紑鍚?鈫?浼樺厛鐢?item.originalPlayUrl锛堟湇鍔″櫒瑙ｆ瀽鏃跺凡杩斿洖锛夛紝缂哄け鎵嶉噸鏂拌В鏋?
        // - 鍘熺敾璐ㄥ紑鍚?鈫?鍚屼笂
        // - 閮藉叧闂?鈫?鐢ㄨВ鏋愰樁娈佃繑鍥炵殑鍦板潃锛堝崟鏉″尶鍚?1080p / 鎵归噺 cookie锛?
        val wantHighest = DouyinAuthStore.isHighestQualityVideoSaveEnabled(getApplication())
        val wantOriginal = DouyinAuthStore.isOriginalVideoSaveEnabled(getApplication())
        if (wantHighest || wantOriginal) {
            // cache address reuse only for original mode (highest must re-parse)
            if (!wantHighest) {
                val cachedQualityUrl = item.originalPlayUrl?.takeIf { it.isNotBlank() }
                if (cachedQualityUrl != null) {
                    return DouyinDownloadRequest(url = cachedQualityUrl, headers = buildSaveHeaders(cachedQualityUrl))
                }
            }
            val qualityItem = runCatching {
                serverOrLocalParse(
                    input = item.videoId,
                    useCookie = true,
                    original = wantOriginal && !wantHighest,
                    highest = wantHighest
                ) as? ParseResult.Success
            }.getOrNull()
            if (qualityItem != null) {
                val url = qualityItem.originalPlayUrl
                    ?.takeIf { it.isNotBlank() }
                    ?: qualityItem.rawPlayUrl
                    ?: qualityItem.playUrl
                if (!url.isNullOrBlank()) {
                    return DouyinDownloadRequest(url = url, headers = buildSaveHeaders(url))
                }
            }
        }
        return getValidVideoUrl(item)?.let(::DouyinDownloadRequest)
    }

    /**
     * 鍗曟潯瑙嗛淇濆瓨鍓嶏細鎺㈡祴瀹為檯涓嬭浇澶у皬锛岃嫢瓒呰繃璁剧疆闃堝€煎垯寮圭獥璇㈤棶鏄惁缁х画銆?
     * 杩斿洖 (鏄惁缁х画, 鎺㈡祴缁撴灉)锛涙帰娴嬬粨鏋滀緵涓嬭浇闃舵澶嶇敤閬垮厤浜屾鎺㈡祴銆?
     * 鎺㈡祴澶辫触锛堟嬁涓嶅埌澶у皬锛夋椂涓嶆彁绀猴紝鐩存帴缁х画淇濆瓨銆?
     * 闃堝€艰缃?0 琛ㄧず鍏抽棴璇ュ姛鑳姐€?
     */
    private suspend fun confirmVideoSaveSizeIfNeeded(
        context: Context,
        request: DouyinDownloadRequest
    ): Pair<Boolean, SaveSizeProbe?> {
        val probe = probeDownloadSize(request)
        val limitMb = SaveSizePreferences.getLimitMb(context)
        if (limitMb <= 0) {
            return true to probe
        }

        val totalBytes = probe?.totalBytes ?: return true to probe
        val limitBytes = limitMb.toLong() * 1024L * 1024L
        if (totalBytes <= limitBytes) {
            return true to probe
        }

        // 瓒呰繃闃堝€硷細鎸傝捣绛夊緟 UI 寮圭獥纭
        val continuation = CompletableDeferred<Boolean>()
        _sizeConfirmRequest.value = SaveSizeConfirmRequest(
            sizeBytes = totalBytes,
            limitBytes = limitBytes,
            continuation = continuation
        )
        val proceed = withTimeoutOrNull(120_000L) { continuation.await() } ?: false
        return proceed to probe
    }

    /** 鎺㈡祴瑙嗛璧勬簮锛氫紭鍏?HEAD 鎷垮ぇ灏忥紱Range bytes=0-0 纭鏄惁鏀寔鍒嗘涓嬭浇
     *  姣忔 1.5 绉掕秴鏃讹紝澶辫触蹇€熻繑鍥?null锛堜笉闃诲淇濆瓨锛?*/
    private suspend fun probeDownloadSize(request: DouyinDownloadRequest): SaveSizeProbe? =
        withContext(Dispatchers.IO) {
            val headResult = runCatching {
                withTimeoutOrNull(1_500L) {
                    val headRequest = Request.Builder()
                        .url(request.url)
                        .apply {
                            request.headers.forEach { (name, value) -> header(name, value) }
                        }
                        .head()
                        .build()
                    downloadClient.newCall(headRequest).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body?.contentLength()?.takeIf { it > 0 }
                    }
                }
            }.getOrNull()

            if (headResult != null) {
                return@withContext SaveSizeProbe(
                    totalBytes = headResult,
                    rangeSupported = true
                )
            }

            runCatching {
                withTimeoutOrNull(1_500L) {
                    val rangeRequest = Request.Builder()
                        .url(request.url)
                        .apply {
                            request.headers.forEach { (name, value) -> header(name, value) }
                        }
                        .header("Range", "bytes=0-0")
                        .build()
                    downloadClient.newCall(rangeRequest).execute().use { response ->
                        if (response.code == 206) {
                            val total = response.header("Content-Range")
                                ?.substringAfter('/', missingDelimiterValue = "")
                                ?.trim()
                                ?.toLongOrNull()
                                ?.takeIf { it > 0 }
                            total?.let { SaveSizeProbe(totalBytes = it, rangeSupported = true) }
                        } else if (response.isSuccessful) {
                            response.body?.contentLength()
                                ?.takeIf { it > 0 }
                                ?.let { SaveSizeProbe(totalBytes = it, rangeSupported = false) }
                        } else {
                            null
                        }
                    }
                }
            }.getOrNull()
        }


    suspend fun getPlayableVideoItem(item: ParseResult.Success): ParseResult.Success {
        val resolution = resolveValidVideoUrl(item)
        return applyVideoUrlResolution(item, resolution)
    }

    suspend fun refreshPlayableVideoItem(item: ParseResult.Success): ParseResult.Success {
        val resolution = resolveValidVideoUrl(item, forceRefresh = true)
        return applyVideoUrlResolution(item, resolution)
    }

    private fun applyVideoUrlResolution(
        item: ParseResult.Success,
        resolution: VideoUrlResolution
    ): ParseResult.Success {
        val resolvedUrl = resolution.url ?: return item
        val refreshedAt = resolution.refreshedAt
        return if (
            item.type == "video" &&
            (resolvedUrl != item.playUrl || refreshedAt != null && refreshedAt != item.lastPlayUrlUpdateTime)
        ) {
            item.copy(
                playUrl = resolvedUrl,
                lastPlayUrlUpdateTime = refreshedAt ?: item.lastPlayUrlUpdateTime
            )
        } else {
            item
        }
    }

    suspend fun getPlayableLivePhotoUrl(media: GalleryMedia): String? = withContext(Dispatchers.IO) {
        val livePhotoUrl = media.livePhotoRawUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        getCachedLivePhotoPlayableUrl(livePhotoUrl)?.let { return@withContext it }
        refreshPlayableLivePhotoUrl(media)
    }

    suspend fun refreshPlayableLivePhotoUrl(media: GalleryMedia): String? = withContext(Dispatchers.IO) {
        val livePhotoUrl = media.livePhotoRawUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        val resolvedUrl = resolveFinalDirectMediaUrl(livePhotoUrl) ?: livePhotoUrl
        if (!resolvedUrl.isNullOrBlank()) {
            cacheLivePhotoPlayableUrl(
                rawUrl = livePhotoUrl,
                resolvedUrl = resolvedUrl,
                cachedAtSeconds = System.currentTimeMillis() / 1000
            )
        }
        resolvedUrl
    }

    private suspend fun getCachedLivePhotoPlayableUrl(rawUrl: String): String? = withContext(Dispatchers.IO) {
        livePhotoPlayableUrlCacheMutex.withLock {
            ensureLivePhotoPlayableUrlCacheLoadedNoLock()
            livePhotoPlayableUrlCache[rawUrl]?.url
        }
    }

    private suspend fun cacheLivePhotoPlayableUrl(
        rawUrl: String,
        resolvedUrl: String,
        cachedAtSeconds: Long
    ) = withContext(Dispatchers.IO) {
        livePhotoPlayableUrlCacheMutex.withLock {
            ensureLivePhotoPlayableUrlCacheLoadedNoLock()
            livePhotoPlayableUrlCache[rawUrl] = CachedPlaybackUrl(
                url = resolvedUrl,
                cachedAtSeconds = cachedAtSeconds
            )
            persistLivePhotoPlayableUrlCacheNoLock()
        }
    }

    private fun ensureLivePhotoPlayableUrlCacheLoadedNoLock() {
        if (livePhotoPlayableUrlCacheLoaded) {
            return
        }
        livePhotoPlayableUrlCacheLoaded = true
        if (!livePhotoPlayableUrlCacheFile.exists()) {
            return
        }

        try {
            val type = object : TypeToken<Map<String, CachedPlaybackUrl>>() {}.type
            val stored: Map<String, CachedPlaybackUrl> =
                gson.fromJson(livePhotoPlayableUrlCacheFile.readText(), type) ?: emptyMap()
            livePhotoPlayableUrlCache.clear()
            livePhotoPlayableUrlCache.putAll(normalizeLivePhotoPlayableUrlCache(stored))
        } catch (throwable: Exception) {
            Log.w("ParserViewModel", "Failed to load live photo play url cache", throwable)
            livePhotoPlayableUrlCache.clear()
        }
    }

    private fun persistLivePhotoPlayableUrlCacheNoLock() {
        val normalized = normalizeLivePhotoPlayableUrlCache(livePhotoPlayableUrlCache)
        livePhotoPlayableUrlCache.clear()
        livePhotoPlayableUrlCache.putAll(normalized)

        try {
            livePhotoPlayableUrlCacheFile.parentFile?.mkdirs()
            livePhotoPlayableUrlCacheFile.writeText(gson.toJson(normalized))
        } catch (throwable: Exception) {
            Log.w("ParserViewModel", "Failed to persist live photo play url cache", throwable)
        }
    }

    private fun normalizeLivePhotoPlayableUrlCache(
        source: Map<String, CachedPlaybackUrl>
    ): LinkedHashMap<String, CachedPlaybackUrl> {
        return source.entries
            .asSequence()
            .filter { (rawUrl, cached) ->
                rawUrl.isNotBlank() &&
                    cached.url.isNotBlank() &&
                    cached.cachedAtSeconds > 0
            }
            .sortedByDescending { it.value.cachedAtSeconds }
            .take(MAX_LIVE_PHOTO_PLAY_URL_CACHE_ENTRIES)
            .associateTo(LinkedHashMap()) { it.key to it.value }
    }

    private suspend fun resolveValidVideoUrl(
        item: ParseResult.Success,
        forceRefresh: Boolean = false
    ): VideoUrlResolution = withContext(Dispatchers.IO) {
        if (item.type != "video") {
            return@withContext VideoUrlResolution(item.playUrl)
        }
        // 鍒楄〃鐩村嚭鐨?seed 鍙兘娌℃湁濯掍綋锛氱偣鍑绘挱鏀炬椂鎵嶈В鏋愶紙鏈嶅姟鍣?cookie 妯″紡锛?
        if (item.rawPlayUrl.isNullOrBlank() && item.playUrl.isNullOrBlank()) {
            val parsed = runCatching {
                serverOrLocalParse(
                    input = item.videoId,
                    useCookie = true,
                    original = false
                ) as? ParseResult.Success
            }.getOrNull()
            if (parsed != null) {
                return@withContext VideoUrlResolution(
                    url = parsed.playUrl ?: parsed.rawPlayUrl,
                    refreshedAt = System.currentTimeMillis()
                )
            }
            return@withContext VideoUrlResolution(null)
        }

        if (!forceRefresh && !item.playUrl.isNullOrBlank()) {
            // 缂撳瓨浼樺厛锛氭挱鏀惧櫒鎸?rawPlayUrl 缂撳瓨鍛戒腑鍗崇洿鎺ユ挱鏀俱€?
            // 鎾斁鍦板潃鏈夋椂鏁堬細璺濅笂娆″埛鏂拌秴杩囬槇鍊煎垯鍏堥噸鏂拌В鏋愮洿閾撅紝
            // 淇濊瘉鎾斁鍣ㄦ嬁鍒扮殑 URL 鏄柊椴滅殑锛堢紦瀛樻湭鍛戒腑鏃跺挨涓洪噸瑕侊級銆?
            val lastUpdate = item.lastPlayUrlUpdateTime
                ?: item.parseTimestamp
            val urlAgeMs = (System.currentTimeMillis() / 1000 - lastUpdate) * 1000
            if (urlAgeMs <= PLAY_URL_STALE_MS) {
                return@withContext VideoUrlResolution(item.playUrl)
            }
        }

        var refreshSucceeded = false
        val rawUrl = item.rawPlayUrl ?: return@withContext VideoUrlResolution(item.playUrl)
        val newUrl = try {
            DouyinRequestLimiter.acquire(singleParseIntervalMs())
            // 鍒锋柊鎾斁鐩撮摼锛氬鐢ㄤ笅杞借姹傚ご锛圲A/Referer/Cookie锛夛紝
            // 鍚﹀垯鎶栭煶 aweme/v1/play 鎺ュ彛鍙兘涓嶈繑鍥炴纭殑 302 閲嶅畾鍚戙€?
            // cookie 浼樺厛鐢ㄧ櫥褰曟€侊紝鍏舵鍥炶惤鎸佷箙鍖栧尶鍚嶄細璇濓紙ttwid/msToken锛夛紝
            // 纭繚閲嶅惎鍚庢棤鐧诲綍 cookie 鏃跺埛鏂颁篃鑳介€氳繃銆?
            val appContext = getApplication<Application>().applicationContext
            val refreshCookie = DouyinAuthStore.getCookie(appContext)
                ?.takeIf { it.isNotBlank() }
                ?: AnonymousSessionStore.getCookie(appContext)
            val refreshRequest = Request.Builder()
                .url(rawUrl)
                .apply {
                    DouyinVideoDownloadResolver.buildDownloadHeaders(
                        rawUrl,
                        refreshCookie
                    ).forEach { (name, value) -> header(name, value) }
                }
                .build()
            client.newCall(refreshRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    if (DouyinRequestLimiter.isLikelyRiskStatus(response.code)) {
                        DouyinRequestLimiter.reportRiskFailure()
                    } else {
                        DouyinRequestLimiter.reportSuccess()
                    }
                    item.playUrl
                } else {
                    DouyinRequestLimiter.reportSuccess()
                    refreshSucceeded = true
                    // 璺熼殢閲嶅畾鍚戝悗鐨勬渶缁堟挱鏀惧湴鍧€锛坮esponse.request.url 涓烘渶缁?URL锛?
                    response.request.url.toString()
                }
            }
        } catch (e: Exception) {
            if (DouyinRequestLimiter.isLikelyRisk(e)) {
                DouyinRequestLimiter.reportRiskFailure()
            }
            item.playUrl
        }
        val refreshedAt = if (refreshSucceeded && !newUrl.isNullOrBlank()) {
            System.currentTimeMillis() / 1000
        } else {
            null
        }

        if (refreshedAt != null && (newUrl != item.playUrl || refreshedAt != item.lastPlayUrlUpdateTime)) {
            if (!item.batchId.isNullOrBlank()) {
                authorBatchManager.updateBatchWork(item) {
                    it.copy(
                        playUrl = newUrl,
                        lastPlayUrlUpdateTime = refreshedAt
                    )
                }
            } else {
                val updatedItem = item.copy(
                    playUrl = newUrl,
                    lastPlayUrlUpdateTime = refreshedAt
                )
                publishParseHistory(historyRepository.updateParseResult(updatedItem))
            }
        }

        return@withContext VideoUrlResolution(newUrl, refreshedAt)
    }

    private fun buildDirectMediaDownloadRequest(url: String): DouyinDownloadRequest {
        val cookieHeader = DouyinAuthStore.getCookie(getApplication<Application>().applicationContext)
        return DouyinDownloadRequest(
            url = url,
            headers = DouyinVideoDownloadResolver.buildDownloadHeaders(url, cookieHeader)
        )
    }

    private suspend fun resolveFinalDirectMediaUrl(rawUrl: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(rawUrl)
            .apply {
                buildDirectMediaDownloadRequest(rawUrl).headers.forEach { (name, value) ->
                    header(name, value)
                }
            }
            .build()

        return@withContext try {
            DouyinRequestLimiter.acquire(singleParseIntervalMs())
            client.newCall(request).execute().use { response ->
                if (DouyinRequestLimiter.isLikelyRiskStatus(response.code)) {
                    DouyinRequestLimiter.reportRiskFailure()
                } else {
                    DouyinRequestLimiter.reportSuccess()
                }
                response.request.url.toString()
            }
        } catch (e: Exception) {
            if (DouyinRequestLimiter.isLikelyRisk(e)) {
                DouyinRequestLimiter.reportRiskFailure()
            }
            rawUrl
        }
    }

    /**
     * 单条视频保存核心逻辑：准备地址 → 大小确认 → 带进度落盘。
     * 被 [saveMedia]（先弹画质选择框）与 [saveVideoWithQuality]（结果区点画质按钮，直接按选定画质保存）共用。
     */
    private suspend fun saveVideoCore(
        context: Context,
        result: ParseResult.Success,
        requestedQuality: VideoQualityOption?
    ) {
        // 鏈€楂樼敾璐?鍘熺敾璐ㄥ紑鍚椂锛屼繚瀛樺墠闇€瑕佹湇鍔″櫒閲嶆柊瑙ｆ瀽锛坈ookie 妯″紡锛夛紝
        // 鑰楁椂杈冮暱锛屾樉绀?鍑嗗瑙嗛"鎻愮ず锛涘叧闂椂鐢ㄨВ鏋愰樁娈靛湴鍧€锛岀洿鎺ヤ繚瀛?
        val needServerReParse = requestedQuality == null && (
            DouyinAuthStore.isHighestQualityVideoSaveEnabled(
                context.applicationContext
            ) || DouyinAuthStore.isOriginalVideoSaveEnabled(context.applicationContext)
            )
        val shouldShowPrepareState = needServerReParse
        var prepareStateShown = false
        val prepareStateJob = if (shouldShowPrepareState) {
            viewModelScope.launch {
                delay(400L)
                prepareStateShown = true
                _saveState.value = SaveState(
                    isSaving = true,
                    total = 1,
                    current = 0,
                    progress = 0f,
                    label = SAVE_VIDEO_PREPARE_LABEL,
                    indeterminate = true
                )
            }
        } else {
            null
        }

        val downloadRequest = runCatching {
            resolveVideoSaveRequest(result, requestedQuality)
        }.onFailure { throwable ->
            Log.w("ParserViewModel", "Failed to prepare video save request", throwable)
        }.getOrNull()
        prepareStateJob?.cancel()
        if (downloadRequest == null) {
            if (prepareStateShown) {
                _saveState.value = SaveState()
            }
            return
        }

        // 鍗曟潯瑙嗛淇濆瓨鍓嶏細鎺㈡祴澶у皬锛岃秴杩囬槇鍊兼椂璇㈤棶鏄惁缁х画
        val (proceed, sizeProbe) = confirmVideoSaveSizeIfNeeded(
            context = context,
            request = downloadRequest
        )
        if (!proceed) {
            _saveState.value = SaveState()
            return
        }

        val videoFileName = buildVideoFileName(result.title, result.author)

        _saveState.value = SaveState(
            isSaving = true,
            total = 1,
            current = 0,
            progress = 0f,
            label = SAVE_VIDEO_LABEL,
            indeterminate = false
        )

        val success = saveFile(
            context = context,
            client = downloadClient,
            url = downloadRequest.url,
            headers = downloadRequest.headers,
            mimeType = "video/mp4",
            timestamp = result.timestamp,
            fileName = videoFileName,
            probe = sizeProbe
        ) { bytes, total ->
            if (total > 0) {
                withContext(Dispatchers.Main.immediate) {
                    _saveState.value = _saveState.value.copy(
                        current = 1,
                        progress = bytes.toFloat() / total,
                        downloadedBytes = bytes,
                        totalBytes = total
                    )
                }
            }
        }

        _saveState.value = SaveState()
        if (success) {
            Toast.makeText(context, SAVE_VIDEO_SUCCESS, Toast.LENGTH_SHORT).show()
        }
    }

    /** 音乐保存：直接把解析出的音频地址落盘（dyparse/music） */
    private suspend fun saveMusicCore(context: Context, result: ParseResult.Success) {
        val audioUrl = result.playUrl ?: result.originalPlayUrl ?: result.rawPlayUrl
        if (audioUrl.isNullOrBlank()) {
            Toast.makeText(context, SAVE_MUSIC_FAILED, Toast.LENGTH_SHORT).show()
            return
        }
        val (mimeType, extension) = detectAudioUrlType(audioUrl)
        val fileName = result.titlePrefixForFileName()
            .let { buildBaseMediaName(it, result.author, "music") }

        _saveState.value = SaveState(
            isSaving = true,
            total = 1,
            current = 0,
            progress = 0f,
            label = SAVE_MUSIC_LABEL,
            indeterminate = false
        )

        val success = saveFile(
            context = context,
            client = downloadClient,
            url = audioUrl,
            headers = emptyMap(),
            mimeType = mimeType,
            timestamp = result.timestamp,
            fileName = fileName,
            subPathOverride = MUSIC_SAVE_SUBPATH,
            extensionOverride = extension
        ) { bytes, total ->
            if (total > 0) {
                withContext(Dispatchers.Main.immediate) {
                    _saveState.value = _saveState.value.copy(
                        current = 1,
                        progress = bytes.toFloat() / total
                    )
                }
            }
        }

        _saveState.value = SaveState()
        if (success) {
            Toast.makeText(context, SAVE_MUSIC_SUCCESS, Toast.LENGTH_SHORT).show()
        }
    }

    /** 保存音乐封面到下载目录 */
    fun saveCover(context: Context, result: ParseResult.Success) {
        if (_saveState.value.isSaving) return
        if (!SecurityGuard.enforce(context.applicationContext)) {
            Toast.makeText(context, SERVICE_UNAVAILABLE_MESSAGE, Toast.LENGTH_SHORT).show()
            return
        }
        val coverUrl = result.cover
        if (coverUrl.isNullOrBlank()) {
            Toast.makeText(context, SAVE_COVER_FAILED, Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            _saveState.value = SaveState(
                isSaving = true,
                total = 1,
                current = 0,
                progress = 0f,
                label = SAVE_COVER_LABEL,
                indeterminate = false
            )
            val fileName = result.titlePrefixForFileName()
                .let { buildBaseMediaName(it, result.author, "cover") }
            val success = saveFile(
                context = context,
                client = downloadClient,
                url = coverUrl,
                headers = emptyMap(),
                mimeType = contentTypeFromUrl(coverUrl) ?: "image/jpeg",
                timestamp = result.timestamp,
                fileName = fileName,
                subPathOverride = MUSIC_SAVE_SUBPATH,
                extensionOverride = null
            ) { bytes, total ->
                if (total > 0) {
                    withContext(Dispatchers.Main.immediate) {
                        _saveState.value = _saveState.value.copy(
                            current = 1,
                            progress = bytes.toFloat() / total
                        )
                    }
                }
            }
            _saveState.value = SaveState()
            Toast.makeText(
                context,
                if (success) SAVE_COVER_SUCCESS else "\u5c01\u9762\u4fdd\u5b58\u5931\u8d25",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 保存歌词文本到下载目录（.lrc 或 .txt） */
    fun saveLyrics(context: Context, result: ParseResult.Success, asLrc: Boolean = true) {
        if (_saveState.value.isSaving) return
        if (!SecurityGuard.enforce(context.applicationContext)) {
            Toast.makeText(context, SERVICE_UNAVAILABLE_MESSAGE, Toast.LENGTH_SHORT).show()
            return
        }
        val lines = result.lyrics.orEmpty()
        if (lines.isEmpty()) {
            Toast.makeText(context, SAVE_LYRICS_FAILED, Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            _saveState.value = SaveState(
                isSaving = true,
                total = 1,
                current = 0,
                progress = 0f,
                label = SAVE_LYRICS_LABEL,
                indeterminate = true
            )
            val fileName = result.titlePrefixForFileName()
                .let { buildBaseMediaName(it, result.author, "lyrics") }
            val text = if (asLrc) buildLrc(lines) else lines.joinToString("\n") { it.text }
            val extension = if (asLrc) "lrc" else "txt"
            val success = saveTextFile(
                context = context,
                fileName = fileName,
                content = text,
                extension = extension,
                mimeType = "text/plain",
                subPath = MUSIC_SAVE_SUBPATH
            )
            _saveState.value = SaveState()
            Toast.makeText(
                context,
                if (success) SAVE_LYRICS_SUCCESS else SAVE_LYRICS_FAILED,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun buildLrc(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        lines.forEach { line ->
            val ts = line.start?.let { formatLrcTime(it) }
            if (ts != null) {
                sb.append('[').append(ts).append(']')
            }
            sb.append(line.text).append('\n')
        }
        return sb.toString()
    }

    private fun formatLrcTime(seconds: Double): String {
        val totalMs = (seconds * 1000).toInt()
        val mm = totalMs / 60000
        val ss = (totalMs % 60000) / 1000
        val ms = totalMs % 1000
        return String.format(java.util.Locale.US, "%02d:%02d.%02d", mm, ss, ms / 10)
    }

    private fun contentTypeFromUrl(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains(".png") || lower.contains(".webp") -> "image/png"
            lower.contains(".gif") -> "image/gif"
            else -> "image/jpeg"
        }
    }

    private fun detectAudioUrlType(url: String): Pair<String, String> {
        val lower = url.lowercase()
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
        val pathExt = Regex("[.]\\w+([?#]|$)").find(path)
            ?.value?.trim('.', '?', '#')?.lowercase()
        val ext = pathExt?.takeIf { it.length in 2..4 } ?: when {
            lower.contains(".m4a") -> "m4a"
            lower.contains(".aac") -> "aac"
            lower.contains(".flac") -> "flac"
            lower.contains(".wav") -> "wav"
            else -> "mp3"
        }
        val mime = when (ext) {
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "wav" -> "audio/x-wav"
            else -> "audio/mpeg"
        }
        return mime to ext
    }

    fun saveMedia(
        context: Context,
        result: ParseResult.Success,
        specificGalleryMedia: List<GalleryMedia>? = null
    ) {
        if (_saveState.value.isSaving) {
            return
        }

        // 鐜妫€娴嬶細寮傚父鐜鐩存帴鎷掔粷淇濆瓨
        if (!SecurityGuard.enforce(context.applicationContext)) {
            Toast.makeText(context, SERVICE_UNAVAILABLE_MESSAGE, Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            if (result.type == "music") {
                saveMusicCore(context = context, result = result)
                return@launch
            }
            if (result.type == "video") {
                var requestedQuality: VideoQualityOption? = null
                // 每次下载前询问画质（开启后列出各画质大小供选择）
                if (QualitySelectionPreferences.isAskEveryDownload(context.applicationContext)) {
                    when (val outcome = requestVideoQualitySelection(result)) {
                        is QualitySelectionOutcome.Cancelled -> return@launch
                        is QualitySelectionOutcome.Chosen -> requestedQuality = outcome.option
                        is QualitySelectionOutcome.NoOptions -> Unit
                    }
                }
                saveVideoCore(
                    context = context,
                    result = result,
                    requestedQuality = requestedQuality
                )
                return@launch
            }

            val galleryTasks = buildGallerySaveTasks(result, specificGalleryMedia)
            if (galleryTasks.isEmpty()) {
                return@launch
            }

            val totalMediaCount = galleryTasks.size
            val completedCount = AtomicInteger(0)
            val progressMap = ConcurrentHashMap<String, Float>()
            val downloadLimiter = Semaphore(IMAGE_SAVE_CONCURRENCY)

            _saveState.value = SaveState(
                isSaving = true,
                total = totalMediaCount,
                current = 0,
                progress = 0f,
                label = SAVE_IMAGE_LABEL
            )

            val results = coroutineScope {
                galleryTasks.map { task ->
                    async {
                        downloadLimiter.withPermit {
                            val downloadRequest = task.resolveRequest()
                            val success = if (downloadRequest != null) {
                                saveFile(
                                    context = context,
                                    client = downloadClient,
                                    url = downloadRequest.url,
                                    headers = downloadRequest.headers,
                                    mimeType = task.mimeType,
                                    timestamp = task.timestamp,
                                    index = task.index,
                                    fileName = task.fileName,
                                    subPathOverride = task.subPathOverride,
                                    extensionOverride = task.extensionOverride
                                ) { bytes, total ->
                                    if (total > 0) {
                                        progressMap[task.progressKey] =
                                            (bytes.toFloat() / total).coerceIn(0f, 1f)
                                        val aggregateProgress = (
                                            completedCount.get() + progressMap.values.sum()
                                            ) / totalMediaCount.toFloat()
                                        withContext(Dispatchers.Main.immediate) {
                                            _saveState.value = _saveState.value.copy(
                                                current = completedCount.get(),
                                                progress = aggregateProgress.coerceIn(0f, 1f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                false
                            }

                            progressMap.remove(task.progressKey)
                            val completed = completedCount.incrementAndGet()
                            withContext(Dispatchers.Main.immediate) {
                                _saveState.value = _saveState.value.copy(
                                    current = completed,
                                    progress = completed.toFloat() / totalMediaCount
                                )
                            }
                            success
                        }
                    }
                }.awaitAll()
            }

            _saveState.value = SaveState()
            if (results.all { it }) {
                Toast.makeText(context, SAVE_IMAGE_ALL_SUCCESS, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, SAVE_IMAGE_PARTIAL_FAILED, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildGallerySaveTasks(
        result: ParseResult.Success,
        specificGalleryMedia: List<GalleryMedia>? = null,
        baseNameOverride: String? = null
    ): List<MediaSaveTask> {
        if (result.type != "image") {
            return emptyList()
        }

        val selectedGalleryMedia = if (specificGalleryMedia.isNullOrEmpty()) {
            result.galleryItems
        } else {
            specificGalleryMedia
                .map { selected ->
                    result.galleryItems.find { it.index == selected.index } ?: selected
                }
                .sortedBy { it.index }
        }
        if (selectedGalleryMedia.isEmpty()) {
            return emptyList()
        }

        val imageItems = selectedGalleryMedia.filter { !it.imageUrl.isNullOrBlank() }
        val livePhotoItems = selectedGalleryMedia.filter { it.hasLivePhoto }
        val baseName = baseNameOverride ?: buildBaseMediaName(
            result.titlePrefixForFileName(),
            result.author,
            "image"
        )

        val tasks = mutableListOf<MediaSaveTask>()

        // 实况配对：图片与配套动态视频用相同基名（xxx.01.jpg + xxx.01.mov），
        // 并保存到同一目录（dyparse/image），供支持 Live Photo 的相册识别。
        val livePairByIndex = livePhotoItems.associateBy { it.index }

        imageItems.forEachIndexed { saveIndex, media ->
            val imageUrl = media.imageUrl ?: return@forEachIndexed
            val imageFileName = buildImageFileName(baseName, saveIndex, imageItems.size)
            tasks += MediaSaveTask(
                progressKey = "image_${result.videoId}_${media.index}_${result.parseTimestamp}",
                fileName = imageFileName,
                mimeType = "image/jpeg",
                timestamp = result.timestamp,
                index = media.index,
                resolveRequest = { buildDirectMediaDownloadRequest(imageUrl) }
            )

            // 该图片是实况：同一个任务序列里把配套视频也按相同基名保存为 .mov
            val livePhotoUrl = livePairByIndex[media.index]?.livePhotoRawUrl
            if (!livePhotoUrl.isNullOrBlank()) {
                tasks += MediaSaveTask(
                    progressKey = "live_${result.videoId}_${media.index}_${result.parseTimestamp}",
                    fileName = imageFileName,
                    mimeType = "video/quicktime",
                    timestamp = result.timestamp,
                    index = media.index,
                    subPathOverride = "dyparse/image",
                    extensionOverride = "mov",
                    resolveRequest = { buildDirectMediaDownloadRequest(livePhotoUrl) }
                )
                return@forEachIndexed
            }
        }

        // 仅有实况视频而无静态图的媒体单独保存（命名方式不变）
        val savedLiveIndexes = selectedGalleryMedia
            .filter { it.imageUrl.isNullOrBlank() && it.hasLivePhoto }
        savedLiveIndexes.forEachIndexed { saveIndex, media ->
            val livePhotoUrl = media.livePhotoRawUrl ?: return@forEachIndexed
            tasks += MediaSaveTask(
                progressKey = "live_only_${result.videoId}_${media.index}_${result.parseTimestamp}",
                fileName = buildLivePhotoFileName(baseName, saveIndex, savedLiveIndexes.size),
                mimeType = "video/mp4",
                timestamp = result.timestamp,
                index = media.index,
                resolveRequest = { buildDirectMediaDownloadRequest(livePhotoUrl) }
            )
        }

        return tasks
    }

    private fun buildLivePhotoFileName(baseName: String, index: Int, totalVideos: Int): String {
        return if (totalVideos > 1) {
            "${baseName}_live_${(index + 1).toString().padStart(2, '0')}"
        } else {
            "${baseName}_live"
        }
    }

    fun saveBatchMedia(
        context: Context,
        summary: BatchAuthorParseSummary,
        works: List<ParseResult.Success>
    ) {
        if (_saveState.value.isSaving || works.isEmpty()) {
            return
        }

        // 鐜妫€娴嬶細寮傚父鐜鐩存帴鎷掔粷淇濆瓨
        if (!SecurityGuard.enforce(context.applicationContext)) {
            Toast.makeText(context, SERVICE_UNAVAILABLE_MESSAGE, Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            val chosenQuality = if (
                QualitySelectionPreferences.isAskEveryDownload(context.applicationContext)
            ) {
                val representative = works.firstOrNull { it.type == "video" }
                if (representative != null) {
                    when (val outcome = requestVideoQualitySelection(representative)) {
                        is QualitySelectionOutcome.Cancelled -> return@launch
                        is QualitySelectionOutcome.Chosen -> outcome.option
                        is QualitySelectionOutcome.NoOptions -> null
                    }
                } else {
                    null
                }
            } else {
                null
            }
            val tasks = buildBatchSaveTasks(works, chosenQuality)
            if (tasks.isEmpty()) {
                return@launch
            }

            _saveState.value = SaveState(
                isSaving = true,
                total = tasks.size,
                current = 0,
                progress = 0f,
                label = SAVE_BATCH_LABEL
            )

            val completedCount = AtomicInteger(0)
            val progressMap = ConcurrentHashMap<String, Float>()
            val limiter = Semaphore(BATCH_SAVE_CONCURRENCY)

            val results = coroutineScope {
                tasks.map { task ->
                    async {
                        limiter.withPermit {
                            val downloadRequest = task.resolveRequest()
                            val success = if (downloadRequest != null) {
                                saveFile(
                                    context = context,
                                    client = downloadClient,
                                    url = downloadRequest.url,
                                    headers = downloadRequest.headers,
                                    mimeType = task.mimeType,
                                    timestamp = task.timestamp,
                                    index = task.index,
                                    fileName = task.fileName,
                                    subPathOverride = task.subPathOverride,
                                    extensionOverride = task.extensionOverride
                                ) { bytes, total ->
                                    if (total > 0) {
                                        progressMap[task.progressKey] =
                                            (bytes.toFloat() / total).coerceIn(0f, 1f)
                                        val aggregateProgress = (
                                            completedCount.get() + progressMap.values.sum()
                                            ) / tasks.size.toFloat()
                                        withContext(Dispatchers.Main.immediate) {
                                            _saveState.value = _saveState.value.copy(
                                                current = completedCount.get(),
                                                progress = aggregateProgress.coerceIn(0f, 1f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                false
                            }

                            progressMap.remove(task.progressKey)
                            val completed = completedCount.incrementAndGet()
                            withContext(Dispatchers.Main.immediate) {
                                _saveState.value = _saveState.value.copy(
                                    current = completed,
                                    progress = completed.toFloat() / tasks.size
                                )
                            }
                            success
                        }
                    }
                }.awaitAll()
            }

            _saveState.value = SaveState()
            val successCount = results.count { it }
            val failedCount = results.size - successCount

            val message = when {
                failedCount == 0 -> SAVE_BATCH_ALL_SUCCESS
                successCount > 0 -> SAVE_BATCH_PARTIAL_FAILED
                else -> SAVE_BATCH_ALL_FAILED
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** 批量保存：把用户为第一条视频选定的画质，映射到后续每条作品（按 ratio / 原画） */
    private fun mapBatchQuality(
        work: ParseResult.Success,
        chosenQuality: VideoQualityOption?
    ): VideoQualityOption? {
        if (chosenQuality == null) {
            return null
        }
        return work.qualityList.orEmpty().firstOrNull { q ->
            if (chosenQuality.isOriginal) {
                q.isOriginal || q.ratio == "default"
            } else {
                q.ratio == chosenQuality.ratio
            }
        }
    }

    private fun buildBatchSaveTasks(
        works: List<ParseResult.Success>,
        chosenQuality: VideoQualityOption? = null
    ): List<MediaSaveTask> {
        val baseNameCounts = mutableMapOf<String, Int>()
        return works.flatMap { work ->
            val baseName = buildBaseMediaName(work.titlePrefixForFileName(), work.author, work.type)
            val occurrence = (baseNameCounts[baseName] ?: 0) + 1
            baseNameCounts[baseName] = occurrence
            val workBaseName = appendDuplicateSuffix(baseName, occurrence)

            if (work.type == "video") {
                listOf(
                    MediaSaveTask(
                        progressKey = "video_${work.videoId}_${work.parseTimestamp}",
                        fileName = workBaseName,
                        mimeType = "video/mp4",
                        timestamp = work.timestamp,
                        resolveRequest = { resolveVideoSaveRequest(work, mapBatchQuality(work, chosenQuality)) }
                    )
                )
            } else {
                buildGallerySaveTasks(
                    result = work,
                    baseNameOverride = workBaseName
                )
            }
        }
    }

    private suspend fun publishParseHistory(updatedHistory: List<ParseResult.Success>) {
        withContext(Dispatchers.Main.immediate) {
            _historyState.value = updatedHistory
        }
    }

    override fun onCleared() {
        parseJob?.cancel()
        batchParseJob?.cancel()
        viewModelScope.launch {
            authorBatchManager.destroy()
            workWebApiBridge.destroy()
            localParseEngine.destroy()
        }
        super.onCleared()
    }

    private fun buildVideoFileName(title: String, author: String): String {
        return buildBaseMediaName(title.substringBefore('#').trim(), author, "video")
    }

    private fun buildImageFileName(title: String, author: String, index: Int, totalImages: Int): String {
        val baseName = buildBaseMediaName(title.substringBefore('#').trim(), author, "image")
        return buildImageFileName(baseName, index, totalImages)
    }

    private fun buildImageFileName(baseName: String, index: Int, totalImages: Int): String {
        return if (totalImages > 1) {
            "${baseName}_${(index + 1).toString().padStart(2, '0')}"
        } else {
            baseName
        }
    }

    private fun buildBaseMediaName(
        titlePrefix: String,
        author: String,
        fallback: String
    ): String {
        val safeTitle = normalizeFileNamePart(titlePrefix)
        val safeAuthor = normalizeFileNamePart(author)

        return listOf(safeTitle, safeAuthor)
            .filter { it.isNotBlank() }
            .joinToString("_")
            .ifBlank { fallback }
    }

    private fun ParseResult.Success.titlePrefixForFileName(): String {
        return title.substringBefore('#').trim()
    }

    private fun appendDuplicateSuffix(baseName: String, occurrence: Int): String {
        return if (occurrence <= 1) {
            baseName
        } else {
            "${baseName}_$occurrence"
        }
    }

    private fun normalizeFileNamePart(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.')
            .trim()
            .take(40)
    }

}
