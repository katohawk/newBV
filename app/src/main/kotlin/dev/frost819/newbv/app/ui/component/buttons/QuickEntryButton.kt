package dev.frost819.newbv.app.ui.component.buttons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import dev.frost819.newbv.app.ui.component.videocard.SmallVideoCard
import dev.frost819.newbv.app.ui.component.videocard.VideoCardData
import dev.frost819.newbv.app.ui.navigation.PgcFeatureRoute
import dev.frost819.newbv.app.ui.navigation.SearchResultRoute
import dev.frost819.newbv.app.ui.navigation.UserSpaceRoute
import dev.frost819.newbv.app.ui.navigation.VideoDetailRoute
import dev.frost819.newbv.app.util.ToastUtils
import dev.frost819.newbv.app.viewmodel.quickentry.QuickEntryViewModel
import dev.frost819.newbv.core.focus.focusInvertedColors
import dev.frost819.newbv.core.focus.touchClickable
import dev.frost819.newbv.data.quickentry.QuickEntry
import dev.frost819.newbv.data.quickentry.QuickEntryType

/**
 * 「收藏到首页」按钮。
 *
 * 样式与详情页的播放/追番等操作按钮保持一致
 * （shapes.small + focusInvertedColors，非圆角胶囊）。
 * 明确可见、遥控器可聚焦、OK 键直接切换收藏状态（不依赖长按、无确认弹窗）。
 * 已收藏显示 ★ 高亮态，再次点击即取消。
 *
 * @param entry 要收藏的导航入口。
 */
@Composable
fun QuickEntryButton(
    entry: QuickEntry,
    modifier: Modifier = Modifier,
) {
    if (!entry.isValid) return
    val viewModel: QuickEntryViewModel = hiltViewModel()
    val savedKeys by viewModel.savedKeys.collectAsState()
    val saved = entry.key in savedKeys
    val context = LocalContext.current
    var saving by remember(entry.key) { mutableStateOf(false) }
    val onClick = {
        if (!saving) {
            saving = true
            viewModel.setSaved(entry, !saved)
            ToastUtils.show(context, if (saved) "已取消首页收藏" else "已收藏到首页")
            saving = false
        }
    }

    Surface(
        // 不要在外层用 .clip()：它会裁剪掉 Surface 内部的聚焦放大，导致漂浮被裁切
        modifier = modifier.touchClickable(onClick = onClick),
        onClick = onClick,
        enabled = !saving,
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small),
        colors =
            focusInvertedColors(
                containerColor =
                    if (saved) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                contentColor =
                    if (saved) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    if (saved) {
                        Border(
                            border = androidx.compose.foundation.BorderStroke(
                                2.dp,
                                MaterialTheme.colorScheme.border,
                            ),
                            shape = MaterialTheme.shapes.small,
                        )
                    } else {
                        Border.None
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (saved) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (saved) "已收藏" else "收藏到首页",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 首页推荐流中的收藏卡片。
 *
 * 复用 [SmallVideoCard] 的现有视觉样式，混入首页第一批内容；
 * 点击后直接恢复对应入口（视频详情 / 番剧剧集列表 / 指定类型的搜索结果），
 * 不需要重新搜索或手动切换 Tab。
 */
@Composable
fun QuickEntryCard(
    entry: QuickEntry,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    SmallVideoCard(
        modifier = modifier,
        data =
            VideoCardData(
                avid = entry.aid,
                title = entry.title,
                cover = entry.cover,
                upName =
                    when (entry.type) {
                        QuickEntryType.SEASON -> "番剧 · 剧集列表"
                        QuickEntryType.SEARCH -> "搜索"
                        QuickEntryType.UP -> "UP 主"
                        else -> "视频"
                    },
            ),
        onClick = {
            when (entry.type) {
                QuickEntryType.VIDEO -> navController.navigate(VideoDetailRoute(aid = entry.aid))
                QuickEntryType.SEASON ->
                    navController.navigate(PgcFeatureRoute(seasonId = entry.seasonId))
                QuickEntryType.UP ->
                    navController.navigate(UserSpaceRoute(mid = entry.mid, name = entry.title))
                else ->
                    navController.navigate(
                        SearchResultRoute(keyword = entry.keyword, searchType = entry.searchType),
                    )
            }
        },
    )
}
