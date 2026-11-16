package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.ui.component.videocard.VideoCardData
import dev.frost819.newbv.app.ui.state.player.PlayerState
import dev.frost819.newbv.app.ui.state.player.PlayerUiState
import dev.frost819.newbv.app.ui.state.player.SeekerState
import dev.frost819.newbv.app.util.VideoShotImageCache
import dev.frost819.newbv.data.datastore.ScreenMaskConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证真实播放器控制器的功能/菜单键入口、焦点恢复和推荐切播边界。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RelatedVideosNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val state = mutableStateOf(PlayerUiState())
    private val selected = mutableListOf<Long>()
    private val selectedSeasons = mutableListOf<Int?>()
    private var playbackChanges = 0
    private var seeks = 0
    private var exits = 0

    private fun videos(count: Int = 20) =
        List(count) { index ->
            VideoCardData(
                avid = index + 1L,
                cid = index + 101L,
                title = "推荐视频${index + 1}",
                cover = "",
                upName = "测试UP",
                timeString = "05:00",
            )
        }

    private fun content(
        pgc: Boolean = false,
        items: List<VideoCardData> = videos(),
    ) {
        state.value = PlayerUiState(aid = 100, playerState = PlayerState.Playing, relatedVideos = items)
        val seeker = mutableStateOf(SeekerState(totalDuration = 600_000L, currentTime = 120_000L))
        val cache = VideoShotImageCache()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                VideoPlayerController(
                    modifier = Modifier.testTag("player_root"),
                    isPgc = pgc,
                    isLooping = false,
                    videoShotCache = cache,
                    uiState = state.value,
                    seekerState = seeker,
                    onPlay = { playbackChanges++ },
                    onPause = { playbackChanges++ },
                    onExit = { exits++ },
                    onGoTime = { seeks++ },
                    onBackToStart = {},
                    onCancelSkipToNextEp = {},
                    onPlayNewVideo = {},
                    onPlayPrevious = {},
                    onPlayNext = {},
                    onToggleLoop = {},
                    onToggleSubtitle = {},
                    onGoToUpPage = {},
                    onGoToVideoDetail = {},
                    onShowInteraction = {},
                    onShowComments = {},
                    onMediaProfileSettingChange = {},
                    onAspectRatioChange = {},
                    onPlaySpeedChange = {},
                    onDanmakuSettingChange = {},
                    onSubtitleChange = {},
                    onSubtitleSettingChange = {},
                    onRelatedVideoClicked = {
                        selected += it.avid
                        selectedSeasons += it.seasonId
                    },
                    onRetryRelatedVideos = {
                        state.value = state.value.copy(relatedLoading = true, relatedError = false)
                    },
                    onToggleDanmaku = {},
                    onShowShortcutTip = {},
                    screenMask = ScreenMaskConfig(),
                    isMaskEditing = false,
                    onScreenMaskChange = {},
                    onEditScreenMask = {},
                ) {
                    Box(Modifier.fillMaxSize().background(Color.DarkGray))
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun press(key: Key) {
        // Dialog 与 Activity 各自保存焦点；按键必须发给当前可见的窗口。
        val panelVisible = composeRule.onAllNodesWithTag("related_videos").fetchSemanticsNodes().isNotEmpty()
        val target =
            if (panelVisible) {
                isFocused() and hasAnyAncestor(hasTestTag("related_videos"))
            } else {
                isFocused()
            }
        composeRule.onNode(target).performKeyInput { pressKey(key) }
        composeRule.waitForIdle()
    }

    private fun openButtons() {
        press(Key.DirectionDown)
        composeRule.onNodeWithTag("related_videos").assertDoesNotExist()
        press(Key.DirectionDown)
        composeRule.onNodeWithContentDescription("播放/暂停").assertIsFocused()
    }

    private fun focusedCard(aid: Long) =
        composeRule.onNode(
            isFocused() and hasAnyAncestor(hasTestTag("related_${aid}_0")),
        )

    @Test
    fun menu_key_opens_panel_and_wraps_without_seeking_then_confirm_plays_once() {
        content()
        press(Key.Menu)
        focusedCard(1).assertIsFocused()
        press(Key.DirectionLeft)
        focusedCard(20).assertIsFocused()
        press(Key.DirectionRight)
        focusedCard(1).assertIsFocused()
        // 推荐栏不使用控制栏的五秒自动隐藏计时器。
        composeRule.mainClock.advanceTimeBy(6_000L)
        composeRule.onNodeWithTag("related_videos").assertIsDisplayed()
        composeRule.runOnIdle {
            assertThat(selected).isEmpty()
            assertThat(seeks).isEqualTo(0)
            assertThat(playbackChanges).isEqualTo(0)
        }
        press(Key.DirectionCenter)
        composeRule.onNodeWithTag("related_videos").assertDoesNotExist()
        composeRule.runOnIdle { assertThat(selected).containsExactly(1L) }
    }

    @Test
    fun up_restores_origin_button_and_reopening_restores_card() {
        content()
        openButtons()
        press(Key.DirectionRight)
        composeRule.onNodeWithContentDescription("弹幕开关").assertIsFocused()
        press(Key.Menu)
        press(Key.DirectionRight)
        focusedCard(2).assertIsFocused()
        press(Key.DirectionUp)
        composeRule.onNodeWithContentDescription("弹幕开关").assertIsFocused()
        press(Key.Menu)
        focusedCard(2).assertIsFocused()
        press(Key.Back)
        composeRule.onNodeWithTag("related_videos").assertDoesNotExist()
        composeRule.runOnIdle {
            assertThat(exits).isEqualTo(0)
            assertThat(playbackChanges).isEqualTo(0)
        }
    }

    @Test
    fun empty_list_can_return_and_late_data_receives_focus() {
        content(items = emptyList())
        press(Key.Menu)
        composeRule.onNodeWithText("暂无相关推荐").assertIsDisplayed()
        composeRule.onNodeWithText("返回").assertIsFocused()
        press(Key.DirectionUp)
        composeRule.onNodeWithContentDescription("播放/暂停").assertIsFocused()
        press(Key.Menu)
        composeRule.runOnIdle { state.value = state.value.copy(relatedVideos = videos(1)) }
        focusedCard(1).assertIsFocused()
        press(Key.DirectionLeft)
        press(Key.DirectionRight)
        focusedCard(1).assertIsFocused()
        composeRule.runOnIdle { assertThat(selected).isEmpty() }
    }

    @Test
    fun changing_current_video_closes_panel_and_resets_selection() {
        content()
        press(Key.Menu)
        press(Key.DirectionRight)
        focusedCard(2).assertIsFocused()
        composeRule.runOnIdle { state.value = state.value.copy(aid = 200) }
        composeRule.onNodeWithTag("related_videos").assertDoesNotExist()
        press(Key.Menu)
        focusedCard(1).assertIsFocused()
        press(Key.DirectionCenter)
        composeRule.runOnIdle { assertThat(selected).containsExactly(1L) }
    }

    @Test
    fun existing_related_button_opens_same_panel_and_media_key_still_works() {
        content()
        press(Key.DirectionDown)
        composeRule.onNodeWithContentDescription("相关视频").performClick()
        focusedCard(1).assertIsFocused()
        press(Key.MediaPlayPause)
        composeRule.runOnIdle { assertThat(playbackChanges).isEqualTo(1) }
        composeRule.onNodeWithTag("related_videos").assertIsDisplayed()
    }

    @Test
    fun pgc_menu_opens_distinct_season_cards_and_selection_keeps_season_id() {
        val seasons = videos(2).mapIndexed { index, card -> card.copy(avid = 0, cid = null, seasonId = index + 100) }
        content(pgc = true, items = seasons)
        press(Key.Menu)
        composeRule.onNode(isFocused() and hasAnyAncestor(hasTestTag("related_season_100"))).assertIsFocused()
        press(Key.DirectionRight)
        composeRule.onNode(isFocused() and hasAnyAncestor(hasTestTag("related_season_101"))).assertIsFocused()
        press(Key.DirectionCenter)
        composeRule.onNodeWithTag("related_videos").assertDoesNotExist()
        composeRule.runOnIdle {
            assertThat(selectedSeasons).containsExactly(101)
            assertThat(playbackChanges).isEqualTo(0)
        }
    }

    @Test
    fun pgc_recommendation_error_can_retry_without_interrupting_video() {
        content(pgc = true, items = emptyList())
        composeRule.runOnIdle { state.value = state.value.copy(relatedError = true) }
        press(Key.Menu)
        composeRule.onNodeWithText("相关推荐加载失败").assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("加载中…").assertIsDisplayed()
        composeRule.runOnIdle {
            assertThat(playbackChanges).isEqualTo(0)
            state.value = state.value.copy(relatedVideos = videos(1), relatedLoading = false)
        }
        focusedCard(1).assertIsFocused()
        press(Key.Back)
        composeRule.onNodeWithTag("related_videos").assertDoesNotExist()
    }

    @Test
    fun function_keys_toggle_panel_and_restore_card_without_changing_playback() {
        content()
        for (key in listOf(Key.Menu, Key(763))) {
            press(key)
            focusedCard(1).assertIsFocused()
            press(Key.DirectionRight)
            focusedCard(2).assertIsFocused()
            press(key)
            composeRule.onNodeWithTag("related_videos").assertDoesNotExist()
            press(key)
            focusedCard(2).assertIsFocused()
            press(Key.DirectionLeft)
            press(Key.Back)
        }
        composeRule.runOnIdle {
            assertThat(selected).isEmpty()
            assertThat(playbackChanges).isEqualTo(0)
            assertThat(exits).isEqualTo(0)
        }
    }

    @Test
    fun down_from_buttons_no_longer_opens_panel_and_settings_button_still_works() {
        content()
        openButtons()
        press(Key.DirectionDown)
        composeRule.onNodeWithTag("related_videos").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("打开设置").performClick()
        composeRule.onNodeWithText("画质").assertIsDisplayed()
        composeRule.onNodeWithTag("related_videos").assertDoesNotExist()
    }
}
