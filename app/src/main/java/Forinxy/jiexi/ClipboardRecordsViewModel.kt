package Forinxy.jiexi

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import Forinxy.jiexi.data.ParseResult
import Forinxy.jiexi.data.local.ClipboardRecordEntity
import Forinxy.jiexi.data.local.HistoryDatabase
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 剪贴板记录页 ViewModel：读取 Room 中的剪贴板记录（按复制时间倒序），
 * 支持对失败/待解析记录重新解析、单条删除、清空。
 */
class ClipboardRecordsViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = HistoryDatabase.get(application).historyDao()
    private val gson = Gson()

    val records: StateFlow<List<ClipboardRecordEntity>> = dao.getClipboardRecordsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _parsingIds = mutableStateOf(setOf<Long>())
    val parsingIds: State<Set<Long>> = _parsingIds

    fun parseAgain(record: ClipboardRecordEntity) {
        if (record.id in _parsingIds.value) return
        val input = ClipboardShareContent.extractParseInput(record.content) ?: return
        _parsingIds.value = _parsingIds.value + record.id
        viewModelScope.launch {
            dao.updateClipboardRecord(
                id = record.id,
                status = ClipboardMonitorService.STATUS_PENDING,
                videoId = null,
                title = null,
                author = null,
                type = null,
                cover = null,
                payloadJson = null,
                parseError = null,
                parsedAt = null
            )
            val result = try {
                ClipboardParseEngine.parse(getApplication(), input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ParseResult.Error("解析失败(${e.message})")
            }
            val successItem = result as? ParseResult.Success
            val success = successItem != null
            dao.updateClipboardRecord(
                id = record.id,
                status = if (success) ClipboardMonitorService.STATUS_DONE else ClipboardMonitorService.STATUS_FAILED,
                videoId = successItem?.videoId,
                title = successItem?.title,
                author = successItem?.author,
                type = successItem?.type,
                cover = successItem?.cover,
                payloadJson = successItem?.let { runCatching { gson.toJson(it) }.getOrNull() },
                parseError = (result as? ParseResult.Error)?.msg,
                parsedAt = if (success) System.currentTimeMillis() else null
            )
            _parsingIds.value = _parsingIds.value - record.id
        }
    }

    /**
     * 获取记录的完整解析结果（卡片点击详情用）：
     * 优先反序列化已保存的 payloadJson；缺失（旧数据/未保存）时重新解析并回写。
     */
    suspend fun getDetail(record: ClipboardRecordEntity): ParseResult.Success? = withContext(Dispatchers.IO) {
        record.payloadJson?.let { json ->
            runCatching { gson.fromJson(json, ParseResult.Success::class.java) }.getOrNull()?.let { return@withContext it }
        }
        val input = ClipboardShareContent.extractParseInput(record.content) ?: return@withContext null
        val result = try {
            ClipboardParseEngine.parse(getApplication(), input)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val successItem = result as? ParseResult.Success ?: return@withContext null
        dao.updateClipboardRecord(
            id = record.id,
            status = ClipboardMonitorService.STATUS_DONE,
            videoId = successItem.videoId,
            title = successItem.title,
            author = successItem.author,
            type = successItem.type,
            cover = successItem.cover,
            payloadJson = runCatching { gson.toJson(successItem) }.getOrNull(),
            parseError = null,
            parsedAt = System.currentTimeMillis()
        )
        successItem
    }

    fun delete(record: ClipboardRecordEntity) {
        viewModelScope.launch {
            dao.deleteClipboardRecord(record.id)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            dao.clearClipboardRecords()
        }
    }
}