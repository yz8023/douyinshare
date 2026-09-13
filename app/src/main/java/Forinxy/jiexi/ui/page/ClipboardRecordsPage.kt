package Forinxy.jiexi.ui.page

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import Forinxy.jiexi.ClipboardMonitorService
import Forinxy.jiexi.ClipboardRecordsViewModel
import Forinxy.jiexi.copyPlainText
import Forinxy.jiexi.data.local.ClipboardRecordEntity
import Forinxy.jiexi.ui.theme.floatingBottomBarContentPadding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
private val dayLabelFormat = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** 按日期分组：当天记录内部按复制时间倒序，不同日期整体按最新在前 */
private fun groupRecordsByDay(
    records: List<ClipboardRecordEntity>
): List<Pair<String, List<ClipboardRecordEntity>>> {
    return records.groupBy { dayKeyFormat.format(Date(it.copiedAt)) }
        .map { (_, list) ->
            dayLabelFormat.format(Date(list.first().copiedAt)) to list.sortedByDescending { it.copiedAt }
        }
        .sortedByDescending { (_, list) -> list.maxOf { it.copiedAt } }
}

private fun statusInfo(status: String): Pair<String, Color> = when (status) {
    ClipboardMonitorService.STATUS_DONE ->
        "已解析" to Color(0xFF2E7D32)
    ClipboardMonitorService.STATUS_FAILED ->
        "解析失败" to Color(0xFFC62828)
    else -> "等待解析" to Color(0xFFF57C00)
}

@Composable
fun ClipboardRecordsPage(viewModel: ClipboardRecordsViewModel = viewModel()) {
    val records by viewModel.records.collectAsState()
    val parsingIds = viewModel.parsingIds
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = floatingBottomBarContentPadding(top = 16.dp)
        ) {
            item {
                Text(
                    text = "剪贴板记录",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "自动记录复制的抖音链接并按复制时间分组，后台自动解析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (records.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsNone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.height(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "暂无记录",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "在设置里开启「剪贴板自动监听」，\n复制抖音链接后这里会自动出现解析记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            } else {
                groupRecordsByDay(records).forEach { (dayLabel, dayRecords) ->
                    item(key = "day_$dayLabel") {
                        Text(
                            text = dayLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(dayRecords, key = { it.id }) { record ->
                        ClipboardRecordRow(
                            record = record,
                            parsing = record.id in parsingIds.value,
                            onReParse = { viewModel.parseAgain(record) },
                            onCopy = {
                                copyPlainText(context, clipboardManager, record.content, "已复制原文")
                            },
                            onDelete = { viewModel.delete(record) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { viewModel.clearAll() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text("清空全部记录")
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardRecordRow(
    record: ClipboardRecordEntity,
    parsing: Boolean,
    onReParse: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (statusLabel, statusColor) = statusInfo(record.status)
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = timeFormat.format(Date(record.copiedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        val primary = record.title?.takeIf { it.isNotBlank() }
            ?: record.content.trim().lineSequence().firstOrNull()?.take(40).orEmpty()
        Text(
            text = if (record.type == "image") "$primary（图集）" else primary,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        record.author?.takeIf { it.isNotBlank() }?.let { author ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "@$author",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        record.parseError?.takeIf { it.isNotBlank() }?.let { error ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (parsing) {
                Text(
                    text = "解析中…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else if (record.status != ClipboardMonitorService.STATUS_DONE) {
                TextButton(onClick = onReParse, modifier = Modifier.height(32.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Reply,
                            contentDescription = null,
                            modifier = Modifier
                                .height(14.dp)
                                .width(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重新解析", fontSize = 13.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onCopy, modifier = Modifier.height(32.dp)) {
                Text("复制", fontSize = 13.sp)
            }
            IconButton(onClick = onDelete, modifier = Modifier.height(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(18.dp)
                )
            }
        }
    }
}