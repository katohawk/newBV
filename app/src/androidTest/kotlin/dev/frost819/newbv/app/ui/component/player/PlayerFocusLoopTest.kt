package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.entity.player.VideoListItem
import dev.frost819.newbv.app.ui.component.player.menu.MenuNavList
import dev.frost819.newbv.app.ui.component.player.menu.PlaySpeedItem
import dev.frost819.newbv.app.ui.component.player.menu.PlaySpeedMenuList
import dev.frost819.newbv.app.ui.component.player.menu.component.CheckBoxMenuList
import dev.frost819.newbv.app.ui.component.player.menu.component.MenuListItem
import dev.frost819.newbv.app.ui.component.player.menu.component.PlayerThreeLevelMenu
import dev.frost819.newbv.app.ui.component.player.menu.component.RadioMenuList
import dev.frost819.newbv.app.ui.component.player.menu.component.StepLessMenuItem
import dev.frost819.newbv.app.viewmodel.player.LocalMenuFocusStateData
import dev.frost819.newbv.app.viewmodel.player.MenuFocusState
import dev.frost819.newbv.app.viewmodel.player.MenuFocusStateData
import dev.frost819.newbv.biliapi.entity.video.Dimension
import dev.frost819.newbv.biliapi.entity.video.VideoPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证各类播放器列表的循环、离屏滚动、单项及数值边界。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerFocusLoopTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun content(block: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme { Box(Modifier.size(600.dp, 360.dp)) { block() } }
            }
        }
    }

    private fun wrap(
        first: String,
        last: String,
        previous: Key = Key.DirectionUp,
        next: Key = Key.DirectionDown,
    ) {
        composeRule.onNodeWithText(first).requestFocus().performKeyInput { pressKey(previous) }
        composeRule.onNodeWithText(last).assertIsFocused().performKeyInput { pressKey(next) }
        composeRule.onNodeWithText(first).assertIsFocused()
    }

    @Test
    fun radio_long_list_wraps_without_selecting_or_leaving_column() {
        var changes = 0
        var exits = 0
        content {
            RadioMenuList(
                items = List(80) { "值$it" },
                onSelectedChanged = { changes++ },
                onFocusBackToParent = { exits++ },
            )
        }
        wrap("值0", "值79")
        composeRule.runOnIdle {
            assertThat(changes).isEqualTo(0)
            assertThat(exits).isEqualTo(0)
        }
        composeRule.onNodeWithText("值0").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { assertThat(exits).isEqualTo(1) }
    }

    @Test
    fun checkbox_wraps_without_toggling() {
        var changes = 0
        content {
            CheckBoxMenuList(
                items = listOf("顶部", "滚动", "底部"),
                onSelectedChanged = { changes++ },
                onFocusBackToParent = {},
            )
        }
        wrap("顶部", "底部")
        composeRule.runOnIdle { assertThat(changes).isEqualTo(0) }
    }

    @Test
    fun navigation_wraps_between_first_and_last_tab() {
        content {
            MenuNavList(
                items = listOf("倍速", "画质", "弹幕", "字幕", "遮挡"),
                selected = "倍速",
                isFocusing = true,
                label = { it },
                icon = { Icons.Outlined.Image },
                onSelectedChanged = {},
            )
        }
        wrap("倍速", "遮挡")
    }

    @Test
    fun categories_wrap_without_entering_value_panel() {
        content {
            CompositionLocalProvider(LocalMenuFocusStateData provides MenuFocusStateData(MenuFocusState.Menu)) {
                PlayerThreeLevelMenu(
                    categories = List(30) { "分类$it" },
                    categoryLabel = { it },
                    onFocusStateChange = {},
                ) { _, _, _ -> }
            }
        }
        wrap("分类0", "分类29")
    }

    @Test
    fun speed_wraps_without_changing_playback_speed() {
        var changes = 0
        content {
            PlaySpeedMenuList(currentSelectedPlaySpeedItem = PlaySpeedItem.X2, onPlaySpeedChange = {
                changes++
            }, onFocusStateChange = {})
        }
        wrap("2.0x", "0.5x")
        composeRule.runOnIdle { assertThat(changes).isEqualTo(0) }
    }

    @Test
    fun single_item_stays_focused_in_both_directions() {
        content { RadioMenuList(items = listOf("唯一"), onSelectedChanged = {}, onFocusBackToParent = {}) }
        wrap("唯一", "唯一")
    }

    @Test
    fun horizontal_list_scrolls_and_wraps_in_both_directions() {
        content {
            val state = rememberLazyListState()
            val items = List(30) { "横向$it" }
            val loop = rememberPlayerFocusLoop(items.size, horizontal = true, scrollToItem = { state.scrollToItem(it) })
            LazyRow(state = state) {
                itemsIndexed(items) { index, label ->
                    MenuListItem(
                        modifier = loop(index == 0, index == items.lastIndex),
                        text = label,
                        selected = false,
                        onClick = {},
                    )
                }
            }
        }
        wrap("横向0", "横向29", Key.DirectionLeft, Key.DirectionRight)
    }

    @Test
    fun episodes_wrap_to_last_expanded_child_without_playing() {
        var changes = 0
        val videos =
            listOf(
                VideoListItem(aid = 1, cid = 1, title = "首集"),
                VideoListItem(
                    aid = 2,
                    cid = 2,
                    title = "末集",
                    ugcPages =
                        listOf(
                            VideoPage(
                                cid = 3,
                                index = 1,
                                title = "最后分P",
                                duration = 60,
                                dimension = Dimension(1920, 1080),
                            ),
                        ),
                ),
            )
        content { VideoListController(show = true, currentCid = 3, videoList = videos, onPlayNewVideo = { changes++ }) }
        wrap("首集", "最后分P")
        composeRule.runOnIdle { assertThat(changes).isEqualTo(0) }
    }

    @Test
    fun interaction_buttons_wrap_without_triggering_actions() {
        var actions = 0
        content {
            VideoInteractionDialog(
                actionState = null,
                onLike = { actions++ },
                onCoin = { actions++ },
                onFavorite = { actions++ },
                onOneClickTriple = { actions++ },
                onDismiss = {},
            )
        }
        wrap("点赞", "收藏", Key.DirectionLeft, Key.DirectionRight)
        composeRule.runOnIdle { assertThat(actions).isEqualTo(0) }
    }

    @Test
    fun numeric_adjustment_still_stops_at_maximum() {
        var result = 255f
        content {
            StepLessMenuItem(value = 255f, text = "255", step = 1f, range = 0f..255f, onValueChange = {
                result =
                    it
            }, onFocusBackToParent = {})
        }
        composeRule.onNodeWithText("255").requestFocus().performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.runOnIdle { assertThat(result).isEqualTo(255f) }
    }

    private fun largeCollection() =
        VideoListItem(
            aid = 9,
            cid = 1000,
            title = "500P合集",
            ugcPages =
                (1..500).map { index ->
                    VideoPage(
                        cid = index.toLong(),
                        index = index,
                        title = "分P $index",
                        duration = 60,
                        dimension = Dimension(1920, 1080),
                    )
                },
        )

    @Test
    fun five_hundred_parts_mount_only_nearby_rows_and_wrap_to_last_child() {
        var changes = 0
        val focusedParents = mutableListOf<Long>()
        content {
            VideoListController(
                show = true,
                currentCid = 1,
                videoList = listOf(largeCollection()),
                onPlayNewVideo = { changes++ },
                onVideoFocused = { focusedParents.add(it) },
            )
        }
        composeRule.onNodeWithText("分P 1").assertIsFocused()
        composeRule.onNodeWithText("分P 250").assertDoesNotExist()
        composeRule.onNodeWithText("分P 500").assertDoesNotExist()
        wrap("500P合集", "分P 500")
        composeRule.runOnIdle {
            assertThat(changes).isEqualTo(0)
            assertThat(focusedParents).isNotEmpty()
            assertThat(focusedParents.toSet()).containsExactly(9L)
        }
    }

    @Test
    fun collapsing_current_large_collection_restores_parent_and_child_click_keeps_aid() {
        var played: VideoListItem? = null
        content {
            VideoListController(
                show = true,
                currentCid = 1,
                videoList = listOf(largeCollection()),
                onPlayNewVideo = { played = it },
            )
        }
        composeRule.onNodeWithText("500P合集").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithText("500P合集").assertIsFocused()
        composeRule.onNodeWithText("分P 1").assertDoesNotExist()
        composeRule.runOnIdle { assertThat(played).isNull() }
        composeRule.onNodeWithText("500P合集").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithText("分P 1").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
        }
        composeRule.runOnIdle {
            assertThat(played?.aid).isEqualTo(9L)
            assertThat(played?.cid).isEqualTo(2L)
        }
    }

    @Test
    fun reopening_large_collection_restores_far_current_part() {
        var show by mutableStateOf(true)
        content {
            VideoListController(
                show = show,
                currentCid = 400,
                videoList = listOf(largeCollection()),
                onPlayNewVideo = {},
            )
        }
        composeRule.onNodeWithText("分P 400").assertIsFocused()
        composeRule.onNodeWithText("分P 1").assertDoesNotExist()
        composeRule.onNodeWithText("分P 500").assertDoesNotExist()
        composeRule.runOnIdle { show = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { show = true }
        composeRule.onNodeWithText("分P 400").assertIsFocused()
    }
}
