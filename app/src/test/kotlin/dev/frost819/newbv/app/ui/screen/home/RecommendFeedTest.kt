package dev.frost819.newbv.app.ui.screen.home

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.viewmodel.home.HomeUiState
import dev.frost819.newbv.biliapi.entity.ugc.UgcItem
import dev.frost819.newbv.biliapi.entity.user.ToViewItem
import dev.frost819.newbv.biliapi.entity.user.ToViewItemType
import dev.frost819.newbv.data.quickentry.QuickEntry
import dev.frost819.newbv.data.quickentry.QuickEntryType
import dev.frost819.newbv.data.quickentry.mergeQuickEntries
import org.junit.jupiter.api.Test

/** 验证收藏合并后实际分页边界、卡片身份和操作后的焦点落点。 */
class RecommendFeedTest {
    private fun toView(progress: Int) =
        ToViewItem(
            oid = 1,
            bvid = "BV1",
            cid = 10,
            kid = 0,
            epid = null,
            seasonId = null,
            title = "video",
            cover = "",
            author = "",
            mid = 1,
            duration = 60,
            progress = progress,
            type = ToViewItemType.Archive,
        )

    @Test
    fun `initial watch later focus chooses unwatched group when it exists`() {
        // Given / When / Then
        assertThat(firstToViewItemKey(listOf(toView(-1), toView(10)))).isEqualTo("toview_unwatched_0")
    }

    @Test
    fun `initial watch later focus chooses watched group when all are finished`() {
        // Given / When / Then
        assertThat(firstToViewItemKey(listOf(toView(-1)))).isEqualTo("toview_watched_0")
    }

    @Test
    fun `initial watch later focus skips empty list`() {
        // Given / When / Then
        assertThat(firstToViewItemKey(emptyList())).isNull()
    }

    private fun video(aid: Long) =
        UgcItem(
            aid = aid,
            title = "video $aid",
            cover = "",
            author = "",
            authorMid = null,
            play = 0,
            danmaku = 0,
            duration = 0,
        )

    private fun feed(state: HomeUiState) =
        mergeQuickEntries(
            favorites = state.quickEntries,
            recommendationKeys = state.recommendItems.map { "video:${it.aid}" },
            firstBatchSize = state.recommendFirstBatchSize,
        )

    @Test
    fun `forty favorites do not trigger recommendation pagination at first screen`() {
        // Given
        val state =
            HomeUiState(
                recommendItems = (1L..24L).map(::video),
                recommendFirstBatchSize = 24,
                quickEntries = (101L..140L).map { QuickEntry(QuickEntryType.VIDEO, "favorite", aid = it) },
            )
        val feedSize = feed(state).size

        // When / Then
        assertThat(feedSize).isEqualTo(64)
        assertThat(shouldLoadRecommendPage(11, feedSize, state)).isFalse()
        assertThat(shouldLoadRecommendPage(43, feedSize, state)).isFalse()
        assertThat(shouldLoadRecommendPage(44, feedSize, state)).isTrue()
    }

    @Test
    fun `loading error exhausted and empty feeds suppress automatic requests`() {
        // Given
        val state = HomeUiState(recommendItems = listOf(video(1)))

        // When / Then
        assertThat(shouldLoadRecommendPage(0, 1, state)).isTrue()
        assertThat(shouldLoadRecommendPage(null, 1, state)).isFalse()
        assertThat(shouldLoadRecommendPage(0, 1, state.copy(recommendLoading = true))).isFalse()
        assertThat(shouldLoadRecommendPage(0, 1, state.copy(recommendError = true))).isFalse()
        assertThat(shouldLoadRecommendPage(0, 1, state.copy(recommendHasMore = false))).isFalse()
        assertThat(shouldLoadRecommendPage(0, 40, HomeUiState())).isFalse()
    }

    @Test
    fun `new page and changed favorites use their current merged lengths`() {
        // Given
        val state = HomeUiState(recommendItems = (1L..24L).map(::video))

        // When / Then
        assertThat(shouldLoadRecommendPage(11, feed(state).size, state)).isTrue()
        val appended = state.copy(recommendItems = (1L..48L).map(::video))
        assertThat(shouldLoadRecommendPage(11, feed(appended).size, appended)).isFalse()
        val favorites =
            state.copy(
                quickEntries = (101L..140L).map { QuickEntry(QuickEntryType.VIDEO, "favorite", aid = it) },
            )
        assertThat(shouldLoadRecommendPage(11, feed(favorites).size, favorites)).isFalse()
    }

    @Test
    fun `insertion and pinning preserve recommendations and favorite identities`() {
        // Given
        val a = QuickEntry(QuickEntryType.VIDEO, "a", aid = 101)
        val b = QuickEntry(QuickEntryType.VIDEO, "b", aid = 102)
        val state = HomeUiState(recommendItems = listOf(video(1), video(2)), quickEntries = listOf(a))
        val originalKeys = feed(state).map { recommendItemKey(it, state) }

        // When
        val inserted = state.copy(quickEntries = listOf(b, a))
        val insertedKeys = feed(inserted).map { recommendItemKey(it, inserted) }
        val pinned = inserted.copy(quickEntries = listOf(a, b))
        val pinnedKeys = feed(pinned).map { recommendItemKey(it, pinned) }

        // Then
        assertThat(insertedKeys.drop(1)).containsExactlyElementsIn(originalKeys).inOrder()
        assertThat(pinnedKeys).containsExactlyElementsIn(insertedKeys)
        assertThat(firstRecommendItemKey(pinned)).isEqualTo(originalKeys.first())
        assertThat(firstRecommendItemKey(state.copy(quickEntries = emptyList()))).isEqualTo("rcmd_video_1_0")
        assertThat(firstRecommendItemKey(HomeUiState())).isNull()
    }

    @Test
    fun `duplicate recommendations and favorite of same aid have distinct keys`() {
        // Given
        val state =
            HomeUiState(
                recommendItems = listOf(video(1), video(1), video(1)),
                recommendFirstBatchSize = 1,
                quickEntries = listOf(QuickEntry(QuickEntryType.VIDEO, "favorite", aid = 1)),
            )

        // When
        val keys = feed(state).map { recommendItemKey(it, state) }

        // Then
        assertThat(keys).hasSize(3)
        assertThat(keys.toSet()).hasSize(3)
        assertThat(firstRecommendItemKey(state)).isEqualTo(keys.first())
    }

    @Test
    fun `deleted first middle last and sole favorites choose existing neighbor`() {
        // Given / When / Then
        assertThat(favoriteFocusTargetIndex(listOf("b", "c"), "a", 0, removed = true)).isEqualTo(0)
        assertThat(favoriteFocusTargetIndex(listOf("a", "c"), "b", 1, removed = true)).isEqualTo(1)
        assertThat(favoriteFocusTargetIndex(listOf("a", "b"), "c", 2, removed = true)).isEqualTo(1)
        assertThat(favoriteFocusTargetIndex(emptyList(), "a", 0, removed = true)).isEqualTo(-1)
        assertThat(favoriteFocusTargetIndex(listOf("a", "b"), "a", 0, removed = true)).isNull()
    }

    @Test
    fun `pin waits until favorite has reached first position`() {
        // Given / When / Then
        assertThat(favoriteFocusTargetIndex(listOf("b", "a"), "a", 1, removed = false)).isNull()
        assertThat(favoriteFocusTargetIndex(listOf("a", "b"), "a", 1, removed = false)).isEqualTo(0)
    }
}
