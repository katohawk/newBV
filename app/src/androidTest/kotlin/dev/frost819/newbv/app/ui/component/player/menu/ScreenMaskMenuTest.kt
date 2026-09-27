package dev.frost819.newbv.app.ui.component.player.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.viewmodel.player.LocalMenuFocusStateData
import dev.frost819.newbv.app.viewmodel.player.MenuFocusState
import dev.frost819.newbv.app.viewmodel.player.MenuFocusStateData
import dev.frost819.newbv.data.datastore.ScreenMaskConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证预设颜色和遥控器 RGB 调整，覆盖通道隔离、边界与实时预览。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ScreenMaskMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var config by mutableStateOf(ScreenMaskConfig(color = 0x123456L, alpha = 0.4f))

    private fun showMenu() {
        composeRule.setContent {
            var focusState by remember { mutableStateOf(MenuFocusState.Menu) }
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalMenuFocusStateData provides MenuFocusStateData(focusState),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        ScreenMaskMenu(
                            config = config,
                            onChange = { config = it },
                            onEdit = {},
                            onFocusStateChange = { focusState = it },
                        )
                    }
                }
            }
        }
    }

    private fun selectCategory(label: String) {
        composeRule.onNodeWithText(label).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun adjust(
        value: Int,
        key: Key,
    ) {
        composeRule.onNodeWithText("$value / 255").requestFocus().performKeyInput { pressKey(key) }
    }

    @Test
    fun rgb_keys_update_only_selected_channel_and_preview_while_disabled() {
        // Given
        showMenu()
        // When / Then
        selectCategory("自定义 · 红 (R)")
        adjust(0x12, Key.DirectionUp)
        composeRule.runOnIdle { assertThat(config.color).isEqualTo(0x133456L) }
        composeRule.onNodeWithText("#133456").assertExists()
        selectCategory("自定义 · 绿 (G)")
        adjust(0x34, Key.DirectionUp)
        composeRule.runOnIdle { assertThat(config.color).isEqualTo(0x133556L) }
        selectCategory("自定义 · 蓝 (B)")
        adjust(0x56, Key.DirectionDown)
        composeRule.runOnIdle {
            assertThat(config.color).isEqualTo(0x133555L)
            assertThat(config.alpha).isEqualTo(0.4f)
            assertThat(config.enabled).isFalse()
        }
        composeRule.onNodeWithText("#133555").assertExists()
    }

    @Test
    fun rgb_channels_stop_at_zero_and_255_without_overflow() {
        // Given
        config = config.copy(color = 0xFF00FFL)
        showMenu()
        // When
        selectCategory("自定义 · 红 (R)")
        adjust(255, Key.DirectionUp)
        selectCategory("自定义 · 绿 (G)")
        adjust(0, Key.DirectionDown)
        selectCategory("自定义 · 蓝 (B)")
        adjust(255, Key.DirectionUp)
        // Then
        composeRule.runOnIdle { assertThat(config.color).isEqualTo(0xFF00FFL) }
    }

    @Test
    fun custom_color_can_enter_presets_and_select_ivory_and_purple_with_remote() {
        // Given
        showMenu()
        selectCategory("颜色")
        composeRule.onNodeWithText("#123456").assertExists()
        // When: a custom RGB value is not a preset; entering must not replace it with black.
        composeRule.onNodeWithText("颜色").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.runOnIdle { assertThat(config.color).isEqualTo(0x123456L) }
        composeRule
            .onNodeWithText("米白")
            .performScrollTo()
            .requestFocus()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        // Then
        composeRule.runOnIdle { assertThat(config.color).isEqualTo(0xFFF8E7L) }
        composeRule.onNodeWithText("#FFF8E7").assertExists()
        composeRule
            .onNodeWithText("紫色")
            .performScrollTo()
            .requestFocus()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle {
            assertThat(config.color).isEqualTo(0x800080L)
            assertThat(config.alpha).isEqualTo(0.4f)
        }
        // 重新进入长预设列表时，当前选中的末尾颜色必须已经滚动到可见区域。
        selectCategory("画面遮挡")
        selectCategory("颜色")
        composeRule.onNodeWithText("紫色").assertIsDisplayed()
        composeRule.onNodeWithText("颜色").performKeyInput { pressKey(Key.DirectionLeft) }
    }
}
