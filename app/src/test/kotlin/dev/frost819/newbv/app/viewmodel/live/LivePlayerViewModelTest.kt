package dev.frost819.newbv.app.viewmodel.live

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.kuaishou.akdanmaku.ui.DanmakuPlayer
import dev.frost819.newbv.biliapi.repositories.LivePlayInfo
import dev.frost819.newbv.biliapi.repositories.LivePlayLine
import dev.frost819.newbv.biliapi.repositories.LiveRepository
import dev.frost819.newbv.player.AbstractVideoPlayer
import dev.frost819.newbv.player.VideoPlayerListener
import dev.frost819.newbv.player.impl.exo.ExoMediaPlayer
import dev.frost819.newbv.player.impl.exo.ExoPlayerFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [LivePlayerViewModel] 的单元测试。
 *
 * 验证手动切线路逻辑：重新拉取流信息、越界 clamp、切画质重置线路、失败进错误态。
 * 通过反射设置 `_uiState` 与 `videoPlayer`，避免触发 `loadLive` 的 WebSocket 连接。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LivePlayerViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var liveRepository: LiveRepository
    private lateinit var exoPlayerFactory: ExoPlayerFactory
    private lateinit var viewModel: LivePlayerViewModel
    private lateinit var mockPlayer: AbstractVideoPlayer

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        liveRepository = mockk(relaxed = true)
        exoPlayerFactory = mockk(relaxed = true)
        mockPlayer = mockk(relaxed = true)
        viewModel = LivePlayerViewModel(liveRepository, exoPlayerFactory)
        setVideoPlayer(mockPlayer)
    }

    @AfterEach
    fun tearDown() {
        runCatching { viewModel.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `live danmaku drops hidden messages and retains a finite time window`() {
        // Given
        val danmaku = mockk<DanmakuPlayer>(relaxed = true)
        val prepare =
            LivePlayerViewModel::class.java
                .getDeclaredMethod(
                    "activeDanmakuPosition",
                    DanmakuPlayer::class.java,
                ).apply {
                    isAccessible = true
                }
        updateUiState { it.copy(playerState = LivePlayerState.Playing, isBuffering = false) }
        every { danmaku.isRenderingEnabled } returns false

        // When
        assertThat(prepare.invoke(viewModel, danmaku)).isNull()
        every { danmaku.isRenderingEnabled } returns true
        updateUiState { it.copy(playerState = LivePlayerState.Paused) }
        assertThat(prepare.invoke(viewModel, danmaku)).isNull()

        // Then
        verify(exactly = 0) { danmaku.getCurrentTimeMs() }
        updateUiState { it.copy(playerState = LivePlayerState.Playing) }
        every { danmaku.getCurrentTimeMs() } returns 5_000L
        assertThat(prepare.invoke(viewModel, danmaku)).isEqualTo(5_000L)

        every { danmaku.getCurrentTimeMs() } returns 22_000L
        every { danmaku.getConfig() } returns null
        assertThat(prepare.invoke(viewModel, danmaku)).isEqualTo(22_000L)
        assertThat(prepare.invoke(viewModel, danmaku)).isEqualTo(22_000L)
        verify(exactly = 1) { danmaku.retainData(11_000L, Long.MAX_VALUE) }
    }

    @Test
    fun `live decode error exits buffering and retains original message`() {
        // Given
        val player = mockk<ExoMediaPlayer>(relaxed = true)
        val listener = slot<VideoPlayerListener>()
        every { exoPlayerFactory.create(any(), any()) } returns player
        every { player.setPlayerEventListener(capture(listener)) } returns Unit
        updateUiState { it.copy(isBuffering = true) }
        viewModel.initVideoPlayer(mockk<Context>())

        // When
        listener.captured.onVideoDecodeUnsupported(IllegalStateException("unsupported video"))

        // Then
        assertThat(viewModel.uiState.value.playerState).isEqualTo(LivePlayerState.Error)
        assertThat(viewModel.uiState.value.isBuffering).isFalse()
        assertThat(viewModel.uiState.value.errorMessage).isEqualTo("unsupported video")
        verify(exactly = 1) { exoPlayerFactory.create(any(), any()) }
        verify(exactly = 0) { player.initPlayer() }
    }

    @Test
    fun `changeLine reloads play info and plays target line`() =
        runTest(testDispatcher) {
            // Given
            updateUiState { it.copy(realRoomId = 123, currentQuality = 10000) }
            coEvery { liveRepository.getLivePlayInfo(123, 10000) } returns
                LivePlayInfo(
                    currentQn = 10000,
                    qualities = listOf(10000 to "原画"),
                    lines = listOf(LivePlayLine(1, "url-1"), LivePlayLine(2, "url-2")),
                )

            // When
            viewModel.changeLine(2)
            advanceUntilIdle()

            // Then
            assertThat(viewModel.uiState.value.currentLine).isEqualTo(2)
            assertThat(viewModel.uiState.value.availableLines).hasSize(2)
            verify { mockPlayer.playUrl("url-2") }
        }

    @Test
    fun `changeLine clamps to first line when order is out of range`() =
        runTest(testDispatcher) {
            // Given
            updateUiState { it.copy(realRoomId = 123, currentQuality = 10000) }
            coEvery { liveRepository.getLivePlayInfo(123, 10000) } returns
                LivePlayInfo(
                    currentQn = 10000,
                    qualities = emptyList(),
                    lines = listOf(LivePlayLine(1, "url-1")),
                )

            // When
            viewModel.changeLine(5)
            advanceUntilIdle()

            // Then
            assertThat(viewModel.uiState.value.currentLine).isEqualTo(1)
            verify { mockPlayer.playUrl("url-1") }
        }

    @Test
    fun `changeQuality resets line to first and reloads`() =
        runTest(testDispatcher) {
            // Given
            updateUiState { it.copy(realRoomId = 123, currentQuality = 10000, currentLine = 3) }
            coEvery { liveRepository.getLivePlayInfo(123, 400) } returns
                LivePlayInfo(
                    currentQn = 400,
                    qualities = listOf(400 to "蓝光"),
                    lines = listOf(LivePlayLine(1, "url-1"), LivePlayLine(2, "url-2")),
                )

            // When
            viewModel.changeQuality(400)
            advanceUntilIdle()

            // Then
            assertThat(viewModel.uiState.value.currentQuality).isEqualTo(400)
            assertThat(viewModel.uiState.value.currentLine).isEqualTo(1)
        }

    @Test
    fun `changeLine sets error state when reload fails`() =
        runTest(testDispatcher) {
            // Given
            updateUiState { it.copy(realRoomId = 123, currentQuality = 10000) }
            coEvery { liveRepository.getLivePlayInfo(123, 10000) } throws RuntimeException("boom")

            // When
            viewModel.changeLine(2)
            advanceUntilIdle()

            // Then
            assertThat(viewModel.uiState.value.playerState).isEqualTo(LivePlayerState.Error)
            assertThat(viewModel.uiState.value.errorMessage).isEqualTo("boom")
        }

    @Test
    fun `changeLine does nothing when realRoomId is zero`() =
        runTest(testDispatcher) {
            // When
            viewModel.changeLine(2)
            advanceUntilIdle()

            // Then
            coVerify(exactly = 0) { liveRepository.getLivePlayInfo(any(), any()) }
        }

    // ── Helpers ──────────────────────────────────────────────

    private fun setVideoPlayer(player: AbstractVideoPlayer?) {
        val field = LivePlayerViewModel::class.java.getDeclaredField("videoPlayer${'$'}delegate")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val delegate = field.get(viewModel) as MutableState<AbstractVideoPlayer?>
        delegate.value = player
    }

    private fun updateUiState(transform: (LivePlayerUiState) -> LivePlayerUiState) {
        val field = LivePlayerViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(viewModel) as MutableStateFlow<LivePlayerUiState>
        state.value = transform(state.value)
    }
}
