package Forinxy.jiexi.ui.page

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import Forinxy.jiexi.ClipboardMonitorService
import Forinxy.jiexi.ClipboardRecordsViewModel
import Forinxy.jiexi.ParserViewModel
import Forinxy.jiexi.copyPlainText
import Forinxy.jiexi.data.ParseResult
import Forinxy.jiexi.data.local.ClipboardRecordEntity
import Forinxy.jiexi.ui.theme.MiuixSurface
import Forinxy.jiexi.ui.theme.floatingBottomBarContentPadding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val batchTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/** 批次窗口：同一批次内相邻记录复制时间差不超过该值（毫秒） */
private const val BATCH_WINDOW_MS = 60 * 1000L

private fun statusInfo(status: String): Pair<String, Color> = when (status) {
    ClipboardMonitorService.STATUS_DONE ->
        "已解析" to Color(0xFF2E7D32)
    ClipboardMonitorService.STATUS_FAILED ->
        "解析失败" to Color(0xFFC62828)
    else -> "等待解析" to Color(0xFFF57C00)
}

/** 按短时间窗口归档：相邻记录复制时间差 ≤ 窗口归为同一批次，批次整体按最新在前 */
private fun groupRecordsByBatch(
    records: List<ClipboardRecordEntity>
): List<Pair<ClipboardRecordEntity, List<ClipboardRecordEntity>>> {
    if (records.isEmpty()) return emptyList()
    val sorted = records.sortedByDescending { it.copiedAt }
    val batches = mutableListOf<MutableList<ClipboardRecordEntity>>()
    for (record in sorted) {
        val last = batches.lastOrNull()?.lastOrNull()
        if (last != null && (last.copiedAt - record.copiedAt) <= BATCH_WINDOW_MS) {
            batches.last().add(record)
        } else {
            batches.add(mutableListOf(record))
        }
    }
    // 返回 (批次头记录, 批次内全部记录)，组头用批次头时间展示
    return batches.map { batch ->
        batch.first() to batch
    }
}

private fun matchesQuery(record: ClipboardRecordEntity, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    if (record.title?.contains(q, ignoreCase = true) == true) return true
    if (record.author?.contains(q, ignoreCase = true) == true) return true
    if (record.videoId?.contains(q, ignoreCase = true) == true) return true
    return record.content.contains(q, ignoreCase = true)
}

@Composable
fun ClipboardRecordsPage(
    viewModel: ClipboardRecordsViewModel = viewModel(),
    parserViewModel: ParserViewModel = viewModel()
) {
    val records by viewModel.records.collectAsState()
    val parsingIds = viewModel.parsingIds
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDetailDialog by remember { mutableStateOf<ClipboardRecordEntity?>(null) }
    var detailItem by remember { mutableStateOf<ParseResult.Success?>(null) }
    var isLoadingDetail by remember { mutableStateOf(false) }

    val filtered = records.filter { matchesQuery(it, query) }

    LaunchedEffect(showDetailDialog) {
        val record = showDetailDialog ?: return@LaunchedEffect
        isLoadingDetail = true
        detailItem = viewModel.getDetail(record)
        isLoadingDetail = false
    }

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
                    text = "自动记录复制的抖音链接并按复制时间归档，后台自动解析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
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
                groupRecordsByBatch(filtered).forEach { (head, batchRecords) ->
                    item(key = "batch_${head.id}") {
                        BatchHeader(head = head, count = batchRecords.size)
                    }
                    items(batchRecords, key = { it.id }) { record ->
                        ClipboardRecordRow(
                            record = record,
                            parsing = record.id in parsingIds.value,
                            onClick = {
                                showDetailDialog = record
                            },
                            onReParse = { viewModel.parseAgain(record) },
                            onCopy = {
                                copyPlainText(context, clipboardManager, record.content, "已复制原文")
                            },
                            onDelete = { viewModel.delete(record) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { showClearConfirm = true },
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

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部记录", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text("确定要清空全部剪贴板记录吗？此操作不可恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearConfirm = false
                        Toast.makeText(context, "已清空全部记录", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    showDetailDialog?.let { record ->
        when {
            isLoadingDetail -> AlertDialog(
                onDismissRequest = { showDetailDialog = null },
                confirmButton = {},
                text = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            )
            detailItem != null -> ParseItemDetailDialog(
                viewModel = parserViewModel,
                item = detailItem!!,
                onDismiss = { showDetailDialog = null },
                onDelete = {
                    viewModel.delete(record)
                    showDetailDialog = null
                },
                onItemUpdated = { updatedItem ->
                    detailItem = updatedItem
                }
            )
            else -> {
                showDetailDialog = null
                Toast.makeText(context, "解析结果暂不可用，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = {
            Text(
                text = "搜索标题 / 作者 / 链接",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "清除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        shape = RoundedCornerShape(17.dp)
    )
}

@Composable
private fun BatchHeader(head: ClipboardRecordEntity, count: Int) {
    val headTime = batchTimeFormat.format(Date(head.copiedAt))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = headTime,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "共 $count 条",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ClipboardRecordRow(
    record: ClipboardRecordEntity,
    parsing: Boolean,
    onClick: () -> Unit,
    onReParse: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    MiuixSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = record.cover,
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
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
    }
}
