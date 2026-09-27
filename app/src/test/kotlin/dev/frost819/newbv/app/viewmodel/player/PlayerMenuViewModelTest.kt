package dev.frost819.newbv.app.viewmodel.player

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.data.datastore.ScreenMaskConfig
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [PlayerMenuViewModel] 的单元测试。
 *
 * 验证菜单导航状态、选择项切换逻辑。
 * 不依赖外部数据源，纯 UI 状态管理。
 */
class PlayerMenuViewModelTest {
    private lateinit var viewModel: PlayerMenuViewModel

    @BeforeEach
    fun setUp() {
        viewModel = PlayerMenuViewModel()
    }

    @Test
    fun `mask editor keeps draft in memory and saves only on finish`() {
        mockkObject(Prefs)
        try {
            val stored = ScreenMaskConfig(enabled = true)
            every { Prefs.screenMask } returns stored
            every { Prefs.screenMask = any() } just Runs
            val menu = PlayerMenuViewModel()
            assertThat(menu.screenMask.value).isEqualTo(stored)
            val draft = stored.adjusted(-0.01f, 0f, resize = false)
            menu.updateScreenMask(draft, persist = false)
            verify(exactly = 0) { Prefs.screenMask = any() }
            assertThat(menu.screenMask.value).isEqualTo(draft)
            menu.saveScreenMask()
            verify(exactly = 1) { Prefs.screenMask = draft }
            menu.updateScreenMask(draft.copy(alpha = 0.5f))
            verify(exactly = 1) { Prefs.screenMask = draft.copy(alpha = 0.5f) }
        } finally {
            unmockkObject(Prefs)
        }
    }

    @Test
    fun `initial state has PlaySpeed as selected nav item`() {
        val state = viewModel.menuState.value
        assertThat(state.selectedNavItem).isEqualTo(VideoPlayerMenuNavItem.PlaySpeed)
        assertThat(state.selectedPictureItem).isNull()
        assertThat(state.selectedDanmakuItem).isNull()
        assertThat(state.selectedClosedCaptionItem).isNull()
    }

    @Test
    fun `selectNavItem updates selected nav item and clears sub-items`() {
        viewModel.selectNavItem(VideoPlayerMenuNavItem.Picture)
        assertThat(viewModel.menuState.value.selectedNavItem)
            .isEqualTo(VideoPlayerMenuNavItem.Picture)

        viewModel.selectNavItem(VideoPlayerMenuNavItem.Danmaku)
        assertThat(viewModel.menuState.value.selectedNavItem)
            .isEqualTo(VideoPlayerMenuNavItem.Danmaku)
    }

    @Test
    fun `selectPictureItem sets and clears picture sub-item`() {
        viewModel.selectPictureItem(VideoPlayerPictureMenuItem.Codec)
        assertThat(viewModel.menuState.value.selectedPictureItem)
            .isEqualTo(VideoPlayerPictureMenuItem.Codec)

        viewModel.selectPictureItem(null)
        assertThat(viewModel.menuState.value.selectedPictureItem).isNull()
    }

    @Test
    fun `selectDanmakuItem sets and clears danmaku sub-item`() {
        viewModel.selectDanmakuItem(VideoPlayerDanmakuMenuItem.Size)
        assertThat(viewModel.menuState.value.selectedDanmakuItem)
            .isEqualTo(VideoPlayerDanmakuMenuItem.Size)

        viewModel.selectDanmakuItem(null)
        assertThat(viewModel.menuState.value.selectedDanmakuItem).isNull()
    }

    @Test
    fun `selectClosedCaptionItem sets and clears cc sub-item`() {
        viewModel.selectClosedCaptionItem(VideoPlayerClosedCaptionMenuItem.Opacity)
        assertThat(viewModel.menuState.value.selectedClosedCaptionItem)
            .isEqualTo(VideoPlayerClosedCaptionMenuItem.Opacity)

        viewModel.selectClosedCaptionItem(null)
        assertThat(viewModel.menuState.value.selectedClosedCaptionItem).isNull()
    }

    @Test
    fun `clearSelection clears all sub-items but keeps nav item`() {
        viewModel.selectNavItem(VideoPlayerMenuNavItem.Picture)
        viewModel.selectPictureItem(VideoPlayerPictureMenuItem.Resolution)
        viewModel.selectDanmakuItem(VideoPlayerDanmakuMenuItem.Size)
        viewModel.selectClosedCaptionItem(VideoPlayerClosedCaptionMenuItem.Size)

        viewModel.clearSelection()

        val state = viewModel.menuState.value
        assertThat(state.selectedNavItem).isEqualTo(VideoPlayerMenuNavItem.Picture)
        assertThat(state.selectedPictureItem).isNull()
        assertThat(state.selectedDanmakuItem).isNull()
        assertThat(state.selectedClosedCaptionItem).isNull()
    }

    @Test
    fun `all nav items have display names and icons`() {
        VideoPlayerMenuNavItem.entries.forEach { item ->
            assertThat(item.displayName).isNotEmpty()
            assertThat(item.icon).isNotNull()
        }
    }

    @Test
    fun `all picture menu items have display names`() {
        VideoPlayerPictureMenuItem.entries.forEach { item ->
            assertThat(item.displayName).isNotEmpty()
        }
    }

    @Test
    fun `all danmaku menu items have display names`() {
        VideoPlayerDanmakuMenuItem.entries.forEach { item ->
            assertThat(item.displayName).isNotEmpty()
        }
    }

    @Test
    fun `all closed caption menu items have display names`() {
        VideoPlayerClosedCaptionMenuItem.entries.forEach { item ->
            assertThat(item.displayName).isNotEmpty()
        }
    }

    @Test
    fun `MenuFocusState has three states`() {
        assertThat(MenuFocusState.entries).hasSize(3)
        assertThat(MenuFocusState.entries).containsExactly(
            MenuFocusState.MenuNav,
            MenuFocusState.Menu,
            MenuFocusState.Items,
        )
    }
}
