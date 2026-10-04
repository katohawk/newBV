package dev.frost819.newbv.app.viewmodel.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.data.AccountRepositoryImpl
import dev.frost819.newbv.app.data.AccountUiState
import dev.frost819.newbv.biliapi.entity.home.RecommendData
import dev.frost819.newbv.biliapi.entity.home.RecommendPage
import dev.frost819.newbv.biliapi.entity.rank.PopularVideoData
import dev.frost819.newbv.biliapi.entity.rank.PopularVideoPage
import dev.frost819.newbv.biliapi.entity.ugc.UgcItem
import dev.frost819.newbv.biliapi.repositories.RecommendVideoRepository
import dev.frost819.newbv.biliapi.repositories.UserRepository
import dev.frost819.newbv.data.datastore.HomeTopNavItem
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.data.quickentry.QuickEntry
import dev.frost819.newbv.data.quickentry.QuickEntryRepository
import dev.frost819.newbv.data.quickentry.QuickEntryType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.io.IOException

/**
 * [HomeViewModel] 的单元测试。
 *
 * 验证可见推荐/热门按需加载、分页、刷新及账户失效。
 * 使用 MockK mock [RecommendVideoRepository] 和 [UserRepository]。
 * Prefs 初始化一次，每个测试前 clear 重置。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HomeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var recommendRepo: RecommendVideoRepository
    private lateinit var userRepo: UserRepository
    private lateinit var accountRepo: AccountRepositoryImpl
    private lateinit var quickEntryRepo: QuickEntryRepository
    private lateinit var viewModel: HomeViewModel

    companion object {
        private lateinit var testDataStore: DataStore<Preferences>

        @JvmStatic
        @BeforeAll
        fun initPrefs() {
            Prefs.resetForTesting()
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val file = File.createTempFile("test_home_vm", ".preferences_pb")
            file.deleteOnExit()
            testDataStore =
                PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = { file },
                )
            Prefs.init(testDataStore)
        }

        @JvmStatic
        @AfterAll
        fun cleanup() {
            // Leave Prefs initialized to avoid async write exceptions
        }
    }

    private fun fakeUgcItem(aid: Long) =
        UgcItem(
            aid = aid,
            title = "video $aid",
            cover = "http://example.com/cover.jpg",
            author = "up",
            authorMid = 100L,
            play = 10000,
            danmaku = 500,
            duration = 120,
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        runBlocking { Prefs.clear() }

        recommendRepo = mockk()
        userRepo = mockk()
        accountRepo = mockk()
        quickEntryRepo = mockk()
        every { accountRepo.uiState } returns MutableStateFlow(AccountUiState())
        every { quickEntryRepo.entries } returns MutableStateFlow(emptyList())

        coEvery { recommendRepo.getRecommendVideos(any(), any()) } returns
            RecommendData(
                items = listOf(fakeUgcItem(1), fakeUgcItem(2)),
                nextPage = RecommendPage(),
            )
        coEvery { recommendRepo.getPopularVideos(any(), any()) } returns
            PopularVideoData(
                list = listOf(fakeUgcItem(3), fakeUgcItem(4)),
                nextPage = PopularVideoPage(),
                noMore = false,
            )
    }

    private fun createViewModel(autoLoad: Boolean = true) =
        HomeViewModel(recommendRepo, userRepo, accountRepo, quickEntryRepo).also {
            if (autoLoad) {
                it.ensureLoaded(HomeTopNavItem.Recommend)
                it.ensureLoaded(HomeTopNavItem.Popular)
            }
        }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `missing UP avatar is loaded without changing favorites order and cached`() =
        runTest {
            // Given
            val up = QuickEntry(QuickEntryType.UP, "UP", mid = 123)
            val video = QuickEntry(QuickEntryType.VIDEO, "视频", aid = 1)
            val entries = MutableStateFlow(listOf(up, video))
            every { quickEntryRepo.entries } returns entries
            coEvery { userRepo.getUserInfo(123) } returns
                mockk { every { face } returns "https://example.com/avatar.jpg" }

            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then
            assertThat(viewModel.uiState.value.quickEntries)
                .containsExactly(up.copy(cover = "https://example.com/avatar.jpg"), video)
                .inOrder()
            entries.value = listOf(video, up)
            advanceUntilIdle()
            val lastCover =
                viewModel.uiState.value.quickEntries
                    .last()
                    .cover
            assertThat(lastCover).isEqualTo("https://example.com/avatar.jpg")
            coVerify(exactly = 1) { userRepo.getUserInfo(123) }
        }

    @Test
    fun `avatar failure keeps favorite and existing covers need no request`() =
        runTest {
            // Given
            val missing = QuickEntry(QuickEntryType.UP, "UP", mid = 123)
            val existing = QuickEntry(QuickEntryType.UP, "已有头像", cover = "https://example.com/face.jpg", mid = 456)
            every { quickEntryRepo.entries } returns MutableStateFlow(listOf(missing, existing))
            coEvery { userRepo.getUserInfo(123) } throws IOException("offline")

            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then
            assertThat(viewModel.uiState.value.quickEntries).containsExactly(missing, existing).inOrder()
            coVerify(exactly = 0) { userRepo.getUserInfo(456) }
            assertThat(viewModel.uiState.value.recommendItems).isNotEmpty()
        }

    @Test
    fun `removing favorite cancels pending avatar without restoring removed card`() =
        runTest {
            // Given
            val up = QuickEntry(QuickEntryType.UP, "UP", mid = 123)
            val entries = MutableStateFlow(listOf(up))
            every { quickEntryRepo.entries } returns entries
            coEvery { userRepo.getUserInfo(123) } coAnswers { kotlinx.coroutines.awaitCancellation() }
            viewModel = createViewModel()
            runCurrent()

            // When
            entries.value = emptyList()
            advanceUntilIdle()

            // Then
            assertThat(viewModel.uiState.value.quickEntries).isEmpty()
            coVerify(exactly = 1) { userRepo.getUserInfo(123) }
        }

    @Test
    fun `visible tabs load recommend and popular`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.recommendItems).isNotEmpty()
            assertThat(state.popularItems).isNotEmpty()
        }

    @Test
    fun `refreshRecommend clears and reloads`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            coEvery { recommendRepo.getRecommendVideos(any(), any()) } returns
                RecommendData(
                    items = listOf(fakeUgcItem(100)),
                    nextPage = RecommendPage(),
                )

            viewModel.refreshRecommend()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // loadRecommend loops until 24 items or 3 loads, so 3 × 1 = 3 items
            assertThat(state.recommendItems).isNotEmpty()
            assertThat(state.recommendItems[0].aid).isEqualTo(100)
        }

    @Test
    fun `refreshPopular clears and reloads`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            coEvery { recommendRepo.getPopularVideos(any(), any()) } returns
                PopularVideoData(
                    list = listOf(fakeUgcItem(200)),
                    nextPage = PopularVideoPage(),
                    noMore = true,
                )

            viewModel.refreshPopular()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.popularItems).hasSize(1)
            assertThat(state.popularItems[0].aid).isEqualTo(200)
            assertThat(state.popularHasMore).isFalse()
        }

    @Test
    fun `loadRecommend on error sets loading false and error true`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            coEvery { recommendRepo.getRecommendVideos(any(), any()) } throws RuntimeException("Network error")

            viewModel.refreshRecommend()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.recommendLoading).isFalse()
            assertThat(state.recommendError).isTrue()
        }

    @Test
    fun `loadPopular on error sets loading false and error true`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            coEvery { recommendRepo.getPopularVideos(any(), any()) } throws RuntimeException("Network error")

            viewModel.refreshPopular()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.popularLoading).isFalse()
            assertThat(state.popularError).isTrue()
        }

    @Test
    fun `loadRecommend on timeout sets error true`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            coEvery { recommendRepo.getRecommendVideos(any(), any()) } throws IOException("timeout")

            viewModel.refreshRecommend()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.recommendLoading).isFalse()
            assertThat(state.recommendError).isTrue()
        }

    @Test
    fun `loadPopular on timeout sets error true`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            coEvery { recommendRepo.getPopularVideos(any(), any()) } throws IOException("timeout")

            viewModel.refreshPopular()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.popularLoading).isFalse()
            assertThat(state.popularError).isTrue()
        }

    @Test
    fun `refreshRecommend clears error`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            coEvery { recommendRepo.getRecommendVideos(any(), any()) } throws RuntimeException("error")
            viewModel.refreshRecommend()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.recommendError).isTrue()

            coEvery { recommendRepo.getRecommendVideos(any(), any()) } returns
                RecommendData(
                    items = listOf(fakeUgcItem(1)),
                    nextPage = RecommendPage(),
                )
            viewModel.refreshRecommend()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.recommendError).isFalse()
            assertThat(viewModel.uiState.value.recommendItems).isNotEmpty()
        }

    @Test
    fun `refresh dispatches correct tab`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            coEvery { recommendRepo.getRecommendVideos(any(), any()) } returns
                RecommendData(
                    items = listOf(fakeUgcItem(999)),
                    nextPage = RecommendPage(),
                )

            viewModel.refresh(dev.frost819.newbv.data.datastore.HomeTopNavItem.Recommend)
            advanceUntilIdle()

            assertThat(
                viewModel.uiState.value.recommendItems[0]
                    .aid,
            ).isEqualTo(999)
        }

    @Test
    fun `loadMore dispatches correct tab`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            coEvery { recommendRepo.getPopularVideos(any(), any()) } returns
                PopularVideoData(
                    list = listOf(fakeUgcItem(888)),
                    nextPage = PopularVideoPage(),
                    noMore = false,
                )

            viewModel.loadMore(dev.frost819.newbv.data.datastore.HomeTopNavItem.Popular)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.popularItems.any { it.aid == 888L }).isTrue()
        }

    @Test
    fun `loadRecommend loops until 24 items`() =
        runTest(testDispatcher) {
            coEvery { recommendRepo.getRecommendVideos(any(), any()) } returns
                RecommendData(
                    items = (1..10).map { fakeUgcItem(it.toLong()) },
                    nextPage = RecommendPage(),
                )
            viewModel = createViewModel()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.recommendItems.size).isAtLeast(20)
        }

    @Test
    fun `loadRecommend with existing items appends exactly one more page`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            // 首次加载：列表为空，最多补齐 3 页（每页 2 条），共 6 条
            assertThat(viewModel.uiState.value.recommendItems).hasSize(6)
            coVerify(exactly = 3) { recommendRepo.getRecommendVideos(any(), any()) }

            viewModel.loadRecommend()
            advanceUntilIdle()

            // 已有数据时「加载更多」只追加一页，而非空转（issue #286）
            assertThat(viewModel.uiState.value.recommendItems).hasSize(8)
            coVerify(exactly = 4) { recommendRepo.getRecommendVideos(any(), any()) }
        }

    @Test
    fun `loadRecommend sets hasMore false when page is empty`() =
        runTest(testDispatcher) {
            coEvery { recommendRepo.getRecommendVideos(any(), any()) } returns
                RecommendData(
                    items = listOf(fakeUgcItem(1)),
                    nextPage = RecommendPage(),
                )
            viewModel = createViewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.recommendHasMore).isTrue()

            coEvery { recommendRepo.getRecommendVideos(any(), any()) } returns
                RecommendData(
                    items = emptyList(),
                    nextPage = RecommendPage(),
                )
            viewModel.loadRecommend()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.recommendHasMore).isFalse()
        }

    @Test
    fun `loadRecommend keeps partial data and no error when a later page fails`() =
        runTest(testDispatcher) {
            coEvery { recommendRepo.getRecommendVideos(any(), any()) } returns
                RecommendData(
                    items = listOf(fakeUgcItem(1)),
                    nextPage = RecommendPage(),
                ) andThenThrows RuntimeException("boom")

            viewModel = createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // 第 2 页失败：保留第 1 页数据，不整块报错
            assertThat(state.recommendItems).hasSize(1)
            assertThat(state.recommendError).isFalse()
            assertThat(state.recommendLoading).isFalse()
        }

    @Test
    fun `loadRecommend load-more failure sets error but keeps items`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.recommendItems).isNotEmpty()

            coEvery { recommendRepo.getRecommendVideos(any(), any()) } throws RuntimeException("boom")
            viewModel.loadRecommend()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.recommendItems).isNotEmpty()
            assertThat(state.recommendError).isTrue()
        }

    @Test
    fun `refresh dispatches Popular tab`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            coEvery { recommendRepo.getPopularVideos(any(), any()) } returns
                PopularVideoData(
                    list = listOf(fakeUgcItem(777)),
                    nextPage = PopularVideoPage(),
                    noMore = true,
                )

            viewModel.refresh(dev.frost819.newbv.data.datastore.HomeTopNavItem.Popular)
            advanceUntilIdle()

            assertThat(
                viewModel.uiState.value.popularItems[0]
                    .aid,
            ).isEqualTo(777)
            assertThat(viewModel.uiState.value.popularHasMore).isFalse()
        }

    @Test
    fun `refreshPopular clears error`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            advanceUntilIdle()

            coEvery { recommendRepo.getPopularVideos(any(), any()) } throws RuntimeException("error")
            viewModel.refreshPopular()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.popularError).isTrue()

            coEvery { recommendRepo.getPopularVideos(any(), any()) } returns
                PopularVideoData(
                    list = listOf(fakeUgcItem(1)),
                    nextPage = PopularVideoPage(),
                    noMore = false,
                )
            viewModel.refreshPopular()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.popularError).isFalse()
            assertThat(viewModel.uiState.value.popularItems).isNotEmpty()
        }

    @Test
    fun `construction requests no feed and first visible tab loads only itself`() =
        runTest(testDispatcher) {
            // Given
            viewModel = createViewModel(autoLoad = false)
            advanceUntilIdle()
            coVerify(exactly = 0) { recommendRepo.getRecommendVideos(any(), any()) }
            coVerify(exactly = 0) { recommendRepo.getPopularVideos(any(), any()) }

            // When
            viewModel.ensureLoaded(HomeTopNavItem.Popular)
            advanceUntilIdle()
            viewModel.ensureLoaded(HomeTopNavItem.Popular)
            advanceUntilIdle()

            // Then
            coVerify(exactly = 1) { recommendRepo.getPopularVideos(any(), any()) }
            coVerify(exactly = 0) { recommendRepo.getRecommendVideos(any(), any()) }
            coVerify(exactly = 0) { userRepo.getDynamicVideos(any(), any(), any(), any()) }
        }

    @Test
    fun `successful empty feed is cached and exhausted pages do not reload`() =
        runTest(testDispatcher) {
            // Given
            coEvery { recommendRepo.getRecommendVideos(any(), any()) } returns
                RecommendData(emptyList(), RecommendPage())
            viewModel = createViewModel(autoLoad = false)

            // When
            viewModel.ensureLoaded(HomeTopNavItem.Recommend)
            advanceUntilIdle()
            viewModel.ensureLoaded(HomeTopNavItem.Recommend)
            viewModel.loadRecommend()
            advanceUntilIdle()

            // Then
            coVerify(exactly = 1) { recommendRepo.getRecommendVideos(any(), any()) }
            assertThat(viewModel.uiState.value.recommendHasMore).isFalse()
        }

    @Test
    fun `failed visible tab waits for explicit refresh`() =
        runTest(testDispatcher) {
            // Given
            coEvery { recommendRepo.getPopularVideos(any(), any()) } throws IOException("offline")
            viewModel = createViewModel(autoLoad = false)
            viewModel.ensureLoaded(HomeTopNavItem.Popular)
            advanceUntilIdle()

            // When
            viewModel.ensureLoaded(HomeTopNavItem.Popular)
            advanceUntilIdle()

            // Then
            coVerify(exactly = 1) { recommendRepo.getPopularVideos(any(), any()) }
            assertThat(viewModel.uiState.value.popularError).isTrue()
        }

    @Test
    fun `refresh during pending recommend replaces the request and ignores its late result`() =
        runTest(testDispatcher) {
            // Given
            val pending = CompletableDeferred<RecommendData>()
            var first = true
            coEvery { recommendRepo.getRecommendVideos(any(), any()) } coAnswers {
                if (first) {
                    first = false
                    withContext(NonCancellable) { pending.await() }
                } else {
                    RecommendData((100L..123L).map(::fakeUgcItem), RecommendPage())
                }
            }
            viewModel = createViewModel(autoLoad = false)
            viewModel.ensureLoaded(HomeTopNavItem.Recommend)
            runCurrent()

            // When
            viewModel.refreshRecommend()
            runCurrent()
            pending.complete(RecommendData((1L..24L).map(::fakeUgcItem), RecommendPage()))
            advanceUntilIdle()

            // Then
            assertThat(
                viewModel.uiState.value.recommendItems
                    .map { it.aid },
            ).containsExactlyElementsIn(100L..123L).inOrder()
            assertThat(viewModel.uiState.value.recommendLoading).isFalse()
            coVerify(exactly = 2) { recommendRepo.getRecommendVideos(any(), any()) }
        }

    @Test
    fun `account changes invalidate cached tabs without requesting hidden pages`() =
        runTest(testDispatcher) {
            // Given
            val accounts = MutableStateFlow(AccountUiState(isLogin = true, uid = 1))
            every { accountRepo.uiState } returns accounts
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            accounts.value = AccountUiState(isLogin = true, uid = 2)
            advanceUntilIdle()

            // Then
            assertThat(viewModel.uiState.value.currentUid).isEqualTo(2)
            assertThat(viewModel.uiState.value.recommendItems).isEmpty()
            assertThat(viewModel.uiState.value.popularItems).isEmpty()
            coVerify(exactly = 3) { recommendRepo.getRecommendVideos(any(), any()) }
            coVerify(exactly = 1) { recommendRepo.getPopularVideos(any(), any()) }
            viewModel.ensureLoaded(HomeTopNavItem.Popular)
            advanceUntilIdle()
            coVerify(exactly = 2) { recommendRepo.getPopularVideos(any(), any()) }
            coVerify(exactly = 3) { recommendRepo.getRecommendVideos(any(), any()) }
        }

    @Test
    fun `returning to same uid still rejects the request from its previous generation`() =
        runTest(testDispatcher) {
            // Given
            val accounts = MutableStateFlow(AccountUiState(isLogin = true, uid = 1))
            every { accountRepo.uiState } returns accounts
            val pending = CompletableDeferred<PopularVideoData>()
            var first = true
            coEvery { recommendRepo.getPopularVideos(any(), any()) } coAnswers {
                if (first) {
                    first = false
                    withContext(NonCancellable) { pending.await() }
                } else {
                    PopularVideoData(listOf(fakeUgcItem(100)), PopularVideoPage(), noMore = true)
                }
            }
            viewModel = createViewModel(autoLoad = false)
            viewModel.ensureLoaded(HomeTopNavItem.Popular)
            runCurrent()

            // When
            accounts.value = AccountUiState(isLogin = true, uid = 2)
            runCurrent()
            accounts.value = AccountUiState(isLogin = true, uid = 1)
            runCurrent()
            viewModel.ensureLoaded(HomeTopNavItem.Popular)
            runCurrent()
            pending.complete(PopularVideoData(listOf(fakeUgcItem(1)), PopularVideoPage(), noMore = false))
            advanceUntilIdle()

            // Then
            assertThat(
                viewModel.uiState.value.popularItems
                    .map { it.aid },
            ).containsExactly(100L)
            assertThat(viewModel.uiState.value.popularHasMore).isFalse()
            assertThat(viewModel.uiState.value.popularLoading).isFalse()
        }
}
