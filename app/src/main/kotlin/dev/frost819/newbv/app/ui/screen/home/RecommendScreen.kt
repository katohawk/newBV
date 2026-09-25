package dev.frost819.newbv.app.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import dev.frost819.newbv.app.ui.component.FocusSaver
import dev.frost819.newbv.app.ui.component.ListFooterTip
import dev.frost819.newbv.app.ui.component.TvLazyVerticalGrid
import dev.frost819.newbv.app.ui.component.buttons.QuickEntryCard
import dev.frost819.newbv.app.ui.component.focusSaverItem
import dev.frost819.newbv.app.ui.component.videocard.SmallVideoCard
import dev.frost819.newbv.app.ui.component.videocard.VideoCardData
import dev.frost819.newbv.app.ui.navigation.UserSpaceRoute
import dev.frost819.newbv.app.ui.navigation.navigateFromVideoCard
import dev.frost819.newbv.app.util.formatHourMinSec
import dev.frost819.newbv.app.util.toWanString
import dev.frost819.newbv.app.viewmodel.common.CollectWatchLaterEffects
import dev.frost819.newbv.app.viewmodel.common.WatchLaterViewModel
import dev.frost819.newbv.app.viewmodel.home.HomeViewModel
import dev.frost819.newbv.data.quickentry.mergeQuickEntries
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * 推荐视频列表页。
 *
 * 4 列网格 + 无限滚动，距离底部 20 条时触发加载更多。
 * 支持从详情页返回后恢复焦点到之前点击的卡片。
 */
@Composable
fun RecommendScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel,
    navController: NavController,
    focusSaver: FocusSaver,
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val watchLaterViewModel: WatchLaterViewModel = hiltViewModel()

    CollectWatchLaterEffects(watchLaterViewModel)

    // 收藏插入第一批推荐之前，与第一批内容去重；后续分页不受影响
    val feed =
        remember(state.recommendItems, state.quickEntries, state.recommendFirstBatchSize) {
            mergeQuickEntries(
                favorites = state.quickEntries,
                recommendationKeys = state.recommendItems.map { "video:${it.aid}" },
                firstBatchSize = state.recommendFirstBatchSize,
            )
        }

    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
        }.distinctUntilChanged()
            .filter { index ->
                index != null && index >= state.recommendItems.size - 20
            }.collect {
                viewModel.loadRecommend()
            }
    }

    TvLazyVerticalGrid(
        modifier = modifier,
        state = gridState,
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = feed,
            key = { index, _ -> index },
        ) { index, feedItem ->
            val favorite = feedItem.favorite
            if (favorite != null) {
                QuickEntryCard(
                    entry = favorite,
                    navController = navController,
                    modifier = Modifier.focusSaverItem(focusSaver, "rcmd_$index"),
                )
            } else {
                val item = state.recommendItems[feedItem.recommendationIndex]
                val cardData =
                    remember(item) {
                        VideoCardData(
                            avid = item.aid,
                            bvid = item.bvid,
                            title = item.title,
                            cover = item.cover,
                            playString = item.play.takeIf { it != -1 }.toWanString(),
                            danmakuString = item.danmaku.takeIf { it != -1 }.toWanString(),
                            timeString = (item.duration * 1000L).formatHourMinSec(),
                            upName = item.author,
                            upMid = item.authorMid,
                            pubTime = item.pubTime,
                        )
                    }
                SmallVideoCard(
                    modifier = Modifier.focusSaverItem(focusSaver, "rcmd_$index"),
                    data = cardData,
                    onClick = {
                        navController.navigateFromVideoCard(cardData)
                    },
                    onGoToDetailPage = {
                        navController.navigateFromVideoCard(cardData, forceDetail = true)
                    },
                    onGoToUpPage =
                        item.authorMid?.let { mid ->
                            { navController.navigate(UserSpaceRoute(mid = mid, name = item.author)) }
                        },
                    onAddWatchLater = { watchLaterViewModel.addToView(aid = item.aid) },
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            ListFooterTip(
                isLoading = state.recommendLoading,
                isError = state.recommendError,
                hasMore = state.recommendHasMore,
                itemsIsEmpty = state.recommendItems.isEmpty(),
            )
        }
    }
}
