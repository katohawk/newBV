package dev.frost819.newbv.app.ui.component.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.frost819.newbv.app.entity.player.VideoListItem
import dev.frost819.newbv.biliapi.entity.video.VideoPage
import dev.frost819.newbv.core.focus.touchClickable
import dev.frost819.newbv.core.theme.BVTheme

private sealed interface EpisodeRow {
    val video: VideoListItem
    val key: String

    data class Parent(
        override val video: VideoListItem,
    ) : EpisodeRow {
        override val key: String = "parent:${video.cid}"
    }

    data class Child(
        override val video: VideoListItem,
        val page: VideoPage,
    ) : EpisodeRow {
        override val key: String = "child:${video.cid}:${page.cid}"
    }
}

// 分P铺进同一 LazyColumn，展开数百P时只组合屏幕附近的行。
private fun episodeRows(
    videos: List<VideoListItem>,
    expanded: Set<Long>,
): List<EpisodeRow> =
    buildList {
        videos.forEach { video ->
            add(EpisodeRow.Parent(video))
            if (video.cid in expanded) video.ugcPages?.forEach { add(EpisodeRow.Child(video, it)) }
        }
    }

/**
 * 分集列表覆盖层。
 *
 * 父视频与展开分P共用一个 LazyColumn，保持首尾焦点循环；
 * 打开面板或切集时定位当前播放项，展开/折叠不会触发播放。
 *
 * @param show 是否显示面板。
 * @param currentCid 当前播放视频的 CID。
 * @param videoList 视频及其子分P列表。
 * @param onPlayNewVideo 点击分集后播放新视频。
 * @param onVideoFocused 父视频获得焦点时按需补齐其分P数据。
 */
@Composable
fun VideoListController(
    modifier: Modifier = Modifier,
    show: Boolean,
    currentCid: Long,
    videoList: List<VideoListItem>,
    onPlayNewVideo: (VideoListItem) -> Unit,
    onVideoFocused: (Long) -> Unit = {},
) {
    val listState = rememberLazyListState()
    var expanded by remember(videoList.map { it.cid }) { mutableStateOf(emptySet<Long>()) }
    val rows = remember(videoList, expanded) { episodeRows(videoList, expanded) }
    val loop = rememberPlayerFocusLoop(rows.size, scrollToItem = { listState.scrollToItem(it) })
    val selectedRequester = remember { FocusRequester() }
    val selectedRow =
        rows.firstOrNull { it is EpisodeRow.Child && it.page.cid == currentCid }
            ?: rows.firstOrNull {
                it.video.cid == currentCid ||
                    it.video.ugcPages?.any { page -> page.cid == currentCid } == true
            }

    LaunchedEffect(show, currentCid, videoList) {
        if (show) {
            videoList.firstOrNull { it.ugcPages?.any { page -> page.cid == currentCid } == true }?.let {
                expanded = expanded + it.cid
            }
        }
    }

    // 目标子行由展开集合生成后再定位，不能把父项下标误用为子P下标。
    LaunchedEffect(show, currentCid, selectedRow?.key) {
        if (show && selectedRow != null) {
            val index = rows.indexOfFirst { it.key == selectedRow.key }
            listState.scrollToItem(index)
            withFrameNanos { }
            runCatching { selectedRequester.requestFocus() }
        }
    }

    AnimatedVisibility(visible = show, enter = expandHorizontally(), exit = shrinkHorizontally()) {
        Surface(
            modifier = modifier,
            colors = SurfaceDefaults.colors(containerColor = Color.Black.copy(alpha = 0.5f)),
        ) {
            Box(
                modifier = Modifier.width(300.dp).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 60.dp),
                ) {
                    itemsIndexed(items = rows, key = { _, row -> row.key }) { index, row ->
                        val focusModifier =
                            if (row.key ==
                                selectedRow?.key
                            ) {
                                Modifier.focusRequester(selectedRequester)
                            } else {
                                Modifier
                            }
                        val rowModifier =
                            Modifier
                                .padding(horizontal = 16.dp)
                                .then(loop(index == 0, index == rows.lastIndex))
                                .then(focusModifier)
                        when (row) {
                            is EpisodeRow.Parent -> {
                                val hasPages = !row.video.ugcPages.isNullOrEmpty()
                                val isExpanded = row.video.cid in expanded
                                val childSelected = row.video.ugcPages?.any { it.cid == currentCid } == true
                                PlayerListItem(
                                    modifier = rowModifier,
                                    text = row.video.title,
                                    selected = row.video.cid == currentCid && !childSelected,
                                    textAlign = TextAlign.Start,
                                    onFocus = { onVideoFocused(row.video.aid) },
                                    trailingContent =
                                        if (hasPages) {
                                            {
                                                Icon(
                                                    imageVector =
                                                        if (isExpanded) {
                                                            Icons.Default.KeyboardArrowUp
                                                        } else {
                                                            Icons.Default.KeyboardArrowDown
                                                        },
                                                    contentDescription = null,
                                                    tint = Color.White.copy(alpha = 0.7f),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    onClick = {
                                        if (hasPages) {
                                            expanded =
                                                if (isExpanded) expanded - row.video.cid else expanded + row.video.cid
                                        } else if (row.video.cid != currentCid) {
                                            onPlayNewVideo(row.video)
                                        }
                                    },
                                )
                            }
                            is EpisodeRow.Child ->
                                PlayerListItem(
                                    modifier = rowModifier.padding(start = 16.dp),
                                    text = row.page.title,
                                    selected = row.page.cid == currentCid,
                                    textAlign = TextAlign.Start,
                                    onClick = {
                                        if (row.page.cid !=
                                            currentCid
                                        ) {
                                            onPlayNewVideo(row.video.copy(cid = row.page.cid))
                                        }
                                    },
                                )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 播放器列表项。
 *
 * 通用可聚焦列表项，支持选中状态、尾部图标和自定义对齐。
 *
 * @param modifier 修饰符
 * @param text 文本内容
 * @param selected 是否选中
 * @param textAlign 文本对齐方式
 * @param trailingContent 尾部内容（如展开/折叠图标）
 * @param onFocus 获得焦点回调
 * @param onClick 点击回调
 */
@Composable
fun PlayerListItem(
    modifier: Modifier = Modifier,
    text: String,
    selected: Boolean,
    textAlign: TextAlign = TextAlign.Center,
    trailingContent: (@Composable () -> Unit)? = null,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.hasFocus) onFocus() }
                .touchClickable(onClick = onClick),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    } else {
                        Color.Transparent
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = text,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailingContent?.invoke()
        }
    }
}

// region Previews

@Preview(showBackground = true)
@Composable
private fun VideoListControllerPreview() {
    val sampleList =
        listOf(
            VideoListItem(
                aid = 1,
                cid = 101,
                title = "第一集",
                ugcPages =
                    listOf(
                        VideoPage(
                            cid = 201,
                            index = 1,
                            title = "P1 上半",
                            duration = 600,
                            dimension =
                                dev.frost819.newbv.biliapi.entity.video
                                    .Dimension(1920, 1080),
                        ),
                        VideoPage(
                            cid = 202,
                            index = 2,
                            title = "P1 下半",
                            duration = 600,
                            dimension =
                                dev.frost819.newbv.biliapi.entity.video
                                    .Dimension(1920, 1080),
                        ),
                    ),
            ),
            VideoListItem(aid = 2, cid = 102, title = "第二集"),
        )

    BVTheme {
        VideoListController(
            show = true,
            currentCid = 201L,
            videoList = sampleList,
            onPlayNewVideo = {},
        )
    }
}

// endregion
