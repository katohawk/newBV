package dev.frost819.newbv.app.viewmodel.quickentry

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.data.VideoInfoRepository
import dev.frost819.newbv.biliapi.entity.user.HistoryData
import dev.frost819.newbv.biliapi.entity.user.HistoryItem
import dev.frost819.newbv.biliapi.entity.user.HistoryItemType
import dev.frost819.newbv.biliapi.entity.video.Dimension
import dev.frost819.newbv.biliapi.entity.video.season.Episode
import dev.frost819.newbv.biliapi.entity.video.season.SeasonDetail
import dev.frost819.newbv.biliapi.entity.video.season.Section
import dev.frost819.newbv.biliapi.repositories.HistoryRepository
import dev.frost819.newbv.biliapi.repositories.VideoDetailRepository
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.data.quickentry.QuickEntry
import dev.frost819.newbv.data.quickentry.QuickEntryRepository
import dev.frost819.newbv.data.quickentry.QuickEntryType
import dev.frost819.newbv.data.repository.PlaybackProgress
import dev.frost819.newbv.data.repository.PlaybackProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** 收藏续播的真实优先级、超时和重复点击回归测试。 */
class QuickEntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val detailRepository = mockk<VideoDetailRepository>()
    private val history = mockk<HistoryRepository>(relaxed = true)
    private val progress = mockk<PlaybackProgressRepository>()
    private val info = mockk<VideoInfoRepository>(relaxed = true)
    private val entries = mockk<QuickEntryRepository>()
    private var vm: QuickEntryViewModel? = null
    private val entry = QuickEntry(QuickEntryType.SEASON, "番剧", seasonId = 100)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(Prefs)
        every { Prefs.uid } returns 7L
        every { Prefs.apiType } returns dev.frost819.newbv.data.datastore.ApiType.Web
        every { entries.entries } returns MutableStateFlow(emptyList())
        coEvery { progress.get(any(), any(), any()) } returns null
    }

    @AfterEach
    fun cleanup() {
        vm?.viewModelScope?.cancel()
        unmockkObject(Prefs)
        Dispatchers.resetMain()
    }

    private fun create(): QuickEntryViewModel =
        QuickEntryViewModel(entries, progress, detailRepository, history, info).also { vm = it }

    private fun episode(id: Int) =
        Episode(
            id = id,
            aid = id.toLong(),
            cid = id * 10L,
            epid = null,
            title = "第$id 集",
            longTitle = "",
            cover = "",
            duration = 1200,
            dimension = Dimension(1920, 1080),
            bvid = "BV$id",
        )

    private fun detail(serverEpisode: Int? = 1) =
        SeasonDetail(
            title = "番剧",
            styles = emptyList(),
            cover = "",
            description = "",
            subType = 1,
            seasonId = 100,
            userStatus =
                SeasonDetail.UserStatus(
                    false,
                    false,
                    serverEpisode?.let { SeasonDetail.UserStatus.Progress(it, "$it", 88) },
                ),
            publish = SeasonDetail.Publish(true, ""),
            newEpDesc = "",
            episodes = listOf(episode(1), episode(2), episode(3)),
        )

    @Test
    fun `local latest episode wins over stale server including App id fallback`() =
        runTest(dispatcher) {
            // Given
            coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } returns detail()
            coEvery { progress.get(7L, 0L, 100) } returns PlaybackProgress(2, 20, 2, 100, 123)
            val model = create()
            model.uiEffect.test {
                // When
                model.playSeason(entry)
                // Then
                val event = awaitItem() as QuickEntryUiEffect.PlayEpisode
                assertThat(event.cid).isEqualTo(20)
                assertThat(event.epid).isEqualTo(2)
                assertThat(event.seasonId).isEqualTo(100)
                assertThat(event.subType).isEqualTo(1)
                assertThat(event.startPosition).isEqualTo(123)
                coVerify(exactly = 0) { history.getHistories(any(), any()) }
            }
        }

    @Test
    fun `finished middle episode restarts same episode instead of advancing`() =
        runTest(dispatcher) {
            coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } returns detail()
            coEvery { progress.get(any(), any(), any()) } returns PlaybackProgress(2, 20, 2, 100, -1)
            val model = create()
            model.uiEffect.test {
                model.playSeason(entry)
                val event = awaitItem() as QuickEntryUiEffect.PlayEpisode
                assertThat(event.cid).isEqualTo(20)
                assertThat(event.startPosition).isEqualTo(0)
            }
        }

    @ParameterizedTest
    @ValueSource(ints = [123, -1])
    fun `local final episode restarts first episode at zero`(position: Int) =
        runTest(dispatcher) {
            // Given: 本机末集记录优先于服务端中间集记录，附加分集不影响正片末集判断。
            val season = detail(serverEpisode = 2).copy(sections = listOf(Section(1, "特别篇", listOf(episode(4)))))
            coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } returns season
            coEvery { progress.get(7L, 0L, 100) } returns PlaybackProgress(3, 30, 3, 100, position)
            val model = create()
            model.uiEffect.test {
                // When
                model.playSeason(entry)

                // Then
                val event = awaitItem() as QuickEntryUiEffect.PlayEpisode
                assertThat(event.aid).isEqualTo(1L)
                assertThat(event.cid).isEqualTo(10L)
                assertThat(event.epid).isEqualTo(1)
                assertThat(event.startPosition).isEqualTo(0)
                verify { info.updateVideoList(match { it.map { video -> video.cid } == listOf(10L, 20L, 30L, 40L) }) }
                coVerify(exactly = 0) { history.getHistories(any(), any()) }
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `server final episode restarts first episode for Web and App episode ids`(hasEpid: Boolean) =
        runTest(dispatcher) {
            // Given
            val season = detail(serverEpisode = if (hasEpid) 1003 else 3)
            coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } returns
                season.copy(episodes = season.episodes.map { it.copy(epid = if (hasEpid) 1000 + it.id else null) })
            val model = create()
            model.uiEffect.test {
                // When
                model.playSeason(entry)

                // Then
                val event = awaitItem() as QuickEntryUiEffect.PlayEpisode
                assertThat(event.aid).isEqualTo(1L)
                assertThat(event.cid).isEqualTo(10L)
                assertThat(event.epid).isEqualTo(if (hasEpid) 1001 else 1)
                assertThat(event.startPosition).isEqualTo(0)
            }
        }

    @Test
    fun `recent history final episode restarts first episode at zero`() =
        runTest(dispatcher) {
            // Given: App 历史可能只有 aid，没有 epid 或 cid。
            coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } returns detail(null)
            val lastWatched = mockk<HistoryItem>()
            every { lastWatched.type } returns HistoryItemType.Pgc
            every { lastWatched.oid } returns 3L
            every { lastWatched.progress } returns 456
            coEvery { history.getHistories(any(), any()) } returns HistoryData(0, listOf(lastWatched))
            val model = create()
            model.uiEffect.test {
                // When
                model.playSeason(entry)

                // Then
                val event = awaitItem() as QuickEntryUiEffect.PlayEpisode
                assertThat(event.aid).isEqualTo(1L)
                assertThat(event.cid).isEqualTo(10L)
                assertThat(event.epid).isEqualTo(1)
                assertThat(event.startPosition).isEqualTo(0)
            }
        }

    @Test
    fun `special episode keeps its saved position`() =
        runTest(dispatcher) {
            // Given
            val season = detail().copy(sections = listOf(Section(1, "特别篇", listOf(episode(4)))))
            coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } returns season
            coEvery { progress.get(7L, 0L, 100) } returns PlaybackProgress(4, 40, 4, 100, 123)
            val model = create()
            model.uiEffect.test {
                // When
                model.playSeason(entry)

                // Then
                val event = awaitItem() as QuickEntryUiEffect.PlayEpisode
                assertThat(event.cid).isEqualTo(40L)
                assertThat(event.startPosition).isEqualTo(123)
            }
        }

    @Test
    fun `single episode season keeps its saved position`() =
        runTest(dispatcher) {
            // Given
            coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } returns
                detail().copy(episodes = listOf(episode(1)))
            val model = create()
            model.uiEffect.test {
                // When
                model.playSeason(entry)

                // Then
                val event = awaitItem() as QuickEntryUiEffect.PlayEpisode
                assertThat(event.cid).isEqualTo(10L)
                assertThat(event.startPosition).isEqualTo(88)
            }
        }

    @Test
    fun `invalid local episode falls back to server position`() =
        runTest(dispatcher) {
            coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } returns detail()
            coEvery { progress.get(any(), any(), any()) } returns PlaybackProgress(9, 90, 9, 100, 44)
            val model = create()
            model.uiEffect.test {
                model.playSeason(entry)
                val event = awaitItem() as QuickEntryUiEffect.PlayEpisode
                assertThat(event.cid).isEqualTo(10)
                assertThat(event.startPosition).isEqualTo(88)
            }
        }

    @Test
    fun `one history page without match goes to detail not first episode`() =
        runTest(dispatcher) {
            coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } returns detail(null)
            val model = create()
            model.uiEffect.test {
                model.playSeason(entry)
                assertThat(awaitItem()).isEqualTo(QuickEntryUiEffect.NavigateToSeasonDetail(100))
            }
        }

    @Test
    fun `timeout goes to detail whereas ordinary cancellation emits nothing`() =
        runTest(dispatcher) {
            coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } coAnswers {
                delay(20_000)
                detail()
            }
            val model = create()
            model.uiEffect.test {
                model.playSeason(entry)
                advanceUntilIdle()
                assertThat(awaitItem()).isEqualTo(QuickEntryUiEffect.NavigateToSeasonDetail(100))
                coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } throws CancellationException()
                model.playSeason(entry)
                advanceUntilIdle()
                expectNoEvents()
            }
        }

    @Test
    fun `new click cancels old request and navigates only once`() =
        runTest(dispatcher) {
            var calls = 0
            coEvery { detailRepository.getPgcVideoDetail(any(), any(), any()) } coAnswers {
                calls++
                if (calls == 1) delay(5000)
                detail()
            }
            val model = create()
            model.uiEffect.test {
                model.playSeason(entry)
                runCurrent()
                model.playSeason(entry)
                advanceUntilIdle()
                assertThat(awaitItem()).isInstanceOf(QuickEntryUiEffect.PlayEpisode::class.java)
                expectNoEvents()
            }
        }
}
