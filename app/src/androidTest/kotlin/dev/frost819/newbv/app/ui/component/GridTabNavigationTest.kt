package dev.frost819.newbv.app.ui.component

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.ui.screen.main.LeftNaviContent
import dev.frost819.newbv.data.datastore.HomeTopNavItem
import dev.frost819.newbv.data.datastore.LeftNaviItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.tv.material3.MaterialTheme as TvMaterialTheme

/** 验证行内方向导航、完整/不完整行的 Tab 边界及操作按钮的隔离。 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class GridTabNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun grid(
        focusedIndex: Int,
        steps: MutableList<Int>,
        narrowButton: Boolean = false,
    ) {
        val requester = FocusRequester()
        composeRule.setContent {
            TvLazyVerticalGrid(
                modifier = Modifier.fillMaxSize(),
                columns = GridCells.Fixed(4),
                onHorizontalBoundary = { steps.add(it) },
            ) {
                items((0..5).toList()) { index ->
                    Box(Modifier.height(80.dp)) {
                        Box(
                            modifier =
                                (if (narrowButton) Modifier.width(24.dp) else Modifier.fillMaxSize())
                                    .height(60.dp)
                                    .testTag("item_$index")
                                    .then(if (index == focusedIndex) Modifier.focusRequester(requester) else Modifier)
                                    .focusable(),
                        )
                    }
                }
            }
        }
        composeRule.runOnIdle { requester.requestFocus() }
        composeRule.onNodeWithTag("item_$focusedIndex").assertIsFocused()
    }

    @Test
    fun left_boundary_can_focus_selected_sidebar_without_changing_page() {
        val sidebarRequester = FocusRequester()
        val cardRequester = FocusRequester()
        var changedItem: LeftNaviItem? = null
        composeRule.setContent {
            TvMaterialTheme {
                Row(Modifier.fillMaxSize()) {
                    LeftNaviContent(
                        selectedItem = LeftNaviItem.Home,
                        selectedItemFocusRequester = sidebarRequester,
                        onLeftNaviItemChanged = { changedItem = it },
                        onOpenSettings = {},
                        onShowUserPanel = {},
                        onFocusToContent = { false },
                        onLogin = {},
                        focusSaver = rememberFocusSaver(),
                    )
                    TvLazyVerticalGrid(
                        modifier = Modifier.fillMaxSize(),
                        columns = GridCells.Fixed(4),
                        onHorizontalBoundary = { sidebarRequester.requestFocus() },
                    ) {
                        item {
                            Box(
                                Modifier
                                    .height(80.dp)
                                    .fillMaxSize()
                                    .testTag("first_card")
                                    .focusRequester(cardRequester)
                                    .focusable(),
                            )
                        }
                    }
                }
            }
        }
        composeRule.runOnIdle { cardRequester.requestFocus() }
        composeRule.onNodeWithTag("first_card").assertIsFocused()
        composeRule.onNodeWithTag("first_card").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.runOnIdle {
            assertThat(sidebarRequester.captureFocus()).isTrue()
            sidebarRequester.freeFocus()
            assertThat(changedItem).isNull()
        }
    }

    @Test
    fun automatic_title_focus_does_not_override_controlled_tab_selection() {
        val navRequester = FocusRequester()
        val selectedRequester = FocusRequester()
        composeRule.setContent {
            TvMaterialTheme {
                TopNav(
                    modifier = Modifier.focusRequester(navRequester),
                    items = listOf(HomeTabItem(HomeTopNavItem.Recommend), HomeTabItem(HomeTopNavItem.Popular)),
                    selectedIndex = 1,
                    isLargePadding = true,
                    selectedTabFocusRequester = selectedRequester,
                )
            }
        }
        composeRule.runOnIdle { navRequester.requestFocus() }
        composeRule.onNodeWithText("热门").assertIsSelected()
        composeRule.runOnIdle { selectedRequester.requestFocus() }
        composeRule.onNodeWithText("热门").assertIsFocused()
    }

    @Test
    fun row_interior_moves_focus_without_switching_tab() {
        val steps = mutableListOf<Int>()
        grid(1, steps)
        composeRule.onNodeWithTag("item_1").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("item_2").assertIsFocused()
        composeRule.runOnIdle { assertThat(steps).isEmpty() }
    }

    @Test
    fun full_row_right_edge_switches_forward() {
        val steps = mutableListOf<Int>()
        grid(3, steps)
        composeRule.onNodeWithTag("item_3").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { assertThat(steps).containsExactly(1) }
    }

    @Test
    fun partial_row_right_edge_switches_forward() {
        val steps = mutableListOf<Int>()
        grid(5, steps)
        composeRule.onNodeWithTag("item_5").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { assertThat(steps).containsExactly(1) }
    }

    @Test
    fun left_edge_switches_backward() {
        val steps = mutableListOf<Int>()
        grid(4, steps)
        composeRule.onNodeWithTag("item_4").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.runOnIdle { assertThat(steps).containsExactly(-1) }
    }

    @Test
    fun card_action_buttons_do_not_switch_tabs() {
        val steps = mutableListOf<Int>()
        grid(3, steps, narrowButton = true)
        composeRule.onNodeWithTag("item_3").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { assertThat(steps).isEmpty() }
    }
}
