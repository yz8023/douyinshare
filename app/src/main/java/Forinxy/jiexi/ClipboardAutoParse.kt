package Forinxy.jiexi

import android.content.ClipboardManager
import android.content.Context
import com.google.gson.Gson
import Forinxy.jiexi.data.ParseResult
import Forinxy.jiexi.data.local.ClipboardRecordEntity
import Forinxy.jiexi.data.local.HistoryDatabase
import kotlinx.coroutines.CancellationException

/**
 * 剪贴板捕获与自动解析共享逻辑：供「剪贴板监听服务」「无障碍监听」「悬浮球即时读取」
 * 三个通道共用，保证任意通道触发时走同一套识别/去重/解析/落库流程。
 *
 * 去重采用内存窗口 + 数据库记录两层，多个通道并发触发时同一内容只处理一次。
 */
object ClipboardAutoParse {

    /** 内存去重窗口（窗口长度与剪贴板监听服务一致） */
    private val seenTimes = HashMap<String, Long>()

    private val gson = Gson()

    /**
     * 捕获并解析一段剪贴板内容。
     *
     * @param text 可直接传入已读取的文本；传 null 时自行读取系统剪贴板。
     * @return 需要展示的通知文案；未命中抖音内容或已去重时返回 null。
     */
    suspend fun capture(context: Context, text: String? = null): String? {
        val raw = text ?: readClipboardText(context) ?: return null
        if (!ClipboardShareContent.isDouyinShare(raw)) return null
        val parseInput = ClipboardShareContent.extractParseInput(raw) ?: return null
        val hash = ClipboardShareContent.contentHash(raw)
        if (recentlyHandled(hash)) return null

        val db = HistoryDatabase.get(context)
        val since = System.currentTimeMillis() - ClipboardMonitorService.DEDUPE_WINDOW_MS
        if (db.historyDao().countClipboardRecordsSince(hash, since) > 0) return null

        val id = db.historyDao().insertClipboardRecord(
            ClipboardRecordEntity(
                content = raw,
                contentHash = hash,
                copiedAt = System.currentTimeMillis(),
                status = ClipboardMonitorService.STATUS_PENDING
            )
        )

        val result = try {
            ClipboardParseEngine.parse(context, parseInput)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ParseResult.Error("解析失败(${e.message})")
        }

        val success = result is ParseResult.Success
        val successItem = result as? ParseResult.Success
        db.historyDao().updateClipboardRecord(
            id = id,
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

        return if (success) {
            "已解析：${(result as ParseResult.Success).title ?: "未知"}"
        } else {
            "解析失败：${(result as? ParseResult.Error)?.msg ?: "未知错误"}"
        }
    }

    /** 读取系统剪贴板文本（Android 10+ 需应用处于前台或经无障碍通道调用才可读到） */
    fun readClipboardText(context: Context): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        return runCatching { clip.getItemAt(0).coerceToText(context).toString() }.getOrNull()
    }

    /** 窗口内是否已处理过同一内容（多通道并发时保证只处理一次） */
    @Synchronized
    private fun recentlyHandled(hash: String): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - ClipboardMonitorService.DEDUPE_WINDOW_MS
        val iterator = seenTimes.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
        return seenTimes.put(hash, now) != null
    }
}
