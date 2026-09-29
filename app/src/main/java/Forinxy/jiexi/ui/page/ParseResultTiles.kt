package Forinxy.jiexi.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import Forinxy.jiexi.data.ParseResult
import Forinxy.jiexi.data.galleryImageCount
import Forinxy.jiexi.data.livePhotoCount

/**
 * 平铺展示用的解析结果小卡片：封面 + 类型角标。
 *
 * 用于「批量解析记录展开全部详情」时把每条解析结果平铺出来，点击进入详情。
 */
@Composable
internal fun ParseResultTile(
    item: ParseResult.Success,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.74f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            if (item.cover.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.type == "video") {
                            Icons.Outlined.Movie
                        } else {
                            Icons.Outlined.Image
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                shape = RoundedCornerShape(999.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                )
            ) {
                Text(
                    text = tileTypeLabel(item),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun tileTypeLabel(item: ParseResult.Success): String = when {
    item.type == "video" -> "视频"
    item.type == "music" -> "音频"
    item.livePhotoCount > 0 -> "图集 ${item.galleryImageCount} · 实况 ${item.livePhotoCount}"
    else -> "图集 ${item.galleryImageCount}"
}

/**
 * 平铺展示一组解析结果。非懒加载实现，可安全嵌入 LazyColumn / LazyVerticalGrid 的单个 item 内，
 * 不会产生嵌套滚动的崩溃。
 */
@Composable
internal fun ParseResultTileGrid(
    items: List<ParseResult.Success>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    spacing: Dp = 6.dp,
    onOpen: (ParseResult.Success) -> Unit
) {
    if (items.isEmpty()) return
    val cols = columns.coerceAtLeast(1)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        items.chunked(cols).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                rowItems.forEach { item ->
                    ParseResultTile(
                        item = item,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(item) }
                    )
                }
                repeat(cols - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
