package Forinxy.jiexi

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import Forinxy.jiexi.data.ParseResult
import Forinxy.jiexi.data.local.ClipboardRecordEntity
import Forinxy.jiexi.data.local.HistoryDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 剪贴板记录页 ViewModel：读取 Room 中的剪贴板记录（按复制时间倒序），
 * 支持对失败/待解析记录重新解析、单条删除、清空。
 */
class ClipboardRecordsViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = HistoryDatabase.get(application).historyDao()

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
            val success = result is ParseResult.Success
            dao.updateClipboardRecord(
                id = record.id,
                status = if (success) ClipboardMonitorService.STATUS_DONE else ClipboardMonitorService.STATUS_FAILED,
                videoId = (result as? ParseResult.Success)?.videoId,
                title = (result as? ParseResult.Success)?.title,
                author = (result as? ParseResult.Success)?.author,
                type = (result as? ParseResult.Success)?.type,
                parseError = (result as? ParseResult.Error)?.msg,
                parsedAt = if (success) System.currentTimeMillis() else null
            )
            _parsingIds.value = _parsingIds.value - record.id
        }
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