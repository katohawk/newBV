package dev.frost819.newbv.app.ui.component.player

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.frost819.newbv.R
import dev.frost819.newbv.app.ui.component.focusSaverItem
import dev.frost819.newbv.app.ui.component.rememberFocusSaver
import dev.frost819.newbv.app.ui.component.videocard.SmallVideoCard
import dev.frost819.newbv.app.ui.component.videocard.VideoCardData
import dev.frost819.newbv.app.ui.component.videocard.isPgc
import dev.frost819.newbv.core.focus.touchClickable
import dev.frost819.newbv.core.theme.BVTheme

/**
 * 播放器底部相关视频栏；独立窗口限制焦点，浏览封面不会改变播放状态。
 *
 * @param modifier 底部面板修饰符。
 * @param show 是否显示；同一视频隐藏再打开时保留滚动位置和卡片焦点。
 * @param relatedVideos 当前视频的相关推荐，沿用接口返回顺序。
 * @param onVideoClicked 确认卡片后的播放或番剧导航回调。
 * @param onDismiss 返回键关闭面板，回到播放画面。
 * @param onReturnToControls 上键返回此前的功能按钮。
 * @param onPlayerKeyEvent 转交媒体键和菜单键，保持播放器快捷控制。
 * @param isLoading 推荐请求正在进行。
 * @param isError 推荐加载失败。
 * @param onRetry 重试推荐请求。
 */
@Composable
fun RelatedVideosController(
    modifier: Modifier = Modifier,
    show: Boolean,
    relatedVideos: List<VideoCardData>,
    onVideoClicked: (VideoCardData) -> Unit,
    onDismiss: () -> Unit = {},
    onReturnToControls: () -> Unit = {},
    onPlayerKeyEvent: (KeyEvent) -> Boolean = { false },
    isLoading: Boolean = false,
    isError: Boolean = false,
    onRetry: () -> Unit = {},
) {
    val videos =
        remember(relatedVideos) {
            relatedVideos.filter { it.avid > 0 || it.isPgc }.distinctBy { it.relatedFocusKey() }
        }
    val keys = remember(videos) { videos.map { it.relatedFocusKey() } }
    val listState = rememberLazyListState()
    val focusSaver = rememberFocusSaver()
    val emptyFocus = remember { FocusRequester() }
    val loop = rememberPlayerFocusLoop(videos.size, horizontal = true, scrollToItem = { listState.scrollToItem(it) })
    val background =
        remember {
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))
        }

    if (!show) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            // Dialog 默认压暗整个窗口；这里只用底部渐变衬托卡片，保留上方视频的亮度。
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.let {
                WindowInsetsControllerCompat(it, it.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose { }
        }
        focusSaver.RestoreFocus()
        LaunchedEffect(keys, isError) {
            if (keys.isEmpty()) {
                withFrameNanos { }
                emptyFocus.requestFocus()
            } else {
                val index = keys.indexOf(focusSaver.savedKeyValue()).coerceAtLeast(0)
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    listState.scrollToItem(index)
                }
                // LazyRow 的离屏卡片需要先挂载再请求焦点。
                withFrameNanos { }
                focusSaver.focusRequesterFor(keys[index]).requestFocus()
            }
        }
        Box(
            modifier =
                Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionUp -> {
                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                onReturnToControls()
                            }
                            true
                        }
                        Key.DirectionDown -> true
                        Key.Back -> {
                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                onDismiss()
                            }
                            true
                        }
                        else -> onPlayerKeyEvent(event)
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier =
                    modifier
                        .testTag("related_videos")
                        .fillMaxWidth()
                        .background(background)
                        .padding(vertical = 12.dp),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    text = stringResource(R.string.related_videos_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                if (videos.isEmpty()) {
                    Text(
                        text =
                            stringResource(
                                when {
                                    isLoading -> R.string.loading
                                    isError -> R.string.related_videos_error
                                    else -> R.string.related_videos_empty
                                },
                            ),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isError) {
                            Button(
                                onClick = onRetry,
                                modifier = Modifier.focusRequester(emptyFocus).touchClickable(onClick = onRetry),
                            ) {
                                Text(stringResource(R.string.related_videos_retry))
                            }
                        }
                        Button(
                            onClick = onDismiss,
                            modifier =
                                (if (isError) Modifier else Modifier.focusRequester(emptyFocus))
                                    .touchClickable(onClick = onDismiss),
                        ) { Text(stringResource(R.string.related_videos_back)) }
                    }
                } else {
                    LazyRow(
                        state = listState,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        itemsIndexed(videos, key = { _, video -> video.relatedFocusKey() }) { index, video ->
                            SmallVideoCard(
                                modifier =
                                    Modifier
                                        .width(240.dp)
                                        .testTag(video.relatedFocusKey())
                                        .focusSaverItem(focusSaver, video.relatedFocusKey())
                                        .then(loop(index == 0, index == videos.lastIndex)),
                                data = video,
                                onClick = { onVideoClicked(video) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun VideoCardData.relatedFocusKey(): String =
    if ((seasonId ?: 0) > 0) "related_season_$seasonId" else "related_${avid}_${epid ?: 0}"

// region Previews

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun RelatedVideosControllerPreview() {
    val sampleVideos =
        listOf(
            VideoCardData(
                avid = 1,
                cid = 101,
                title = "相关视频标题 1",
                cover = "",
                upName = "UP主A",
                playString = "1.2万播放",
                danmakuString = "300弹幕",
                timeString = "10:30",
            ),
            VideoCardData(
                avid = 2,
                cid = 102,
                title = "相关视频标题 2",
                cover = "",
                upName = "UP主B",
                playString = "5000播放",
                danmakuString = "100弹幕",
                timeString = "5:20",
            ),
        )

    BVTheme {
        RelatedVideosController(
            show = true,
            relatedVideos = sampleVideos,
            onVideoClicked = {},
        )
    }
}

// endregion
