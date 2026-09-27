package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.data.datastore.ScreenMaskConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 实际 Compose 布局中的遮挡显示、宽高比和遥控器编辑测试。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ScreenMaskTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disabled_hasNoDrawingNode() {
        composeRule.setContent { ScreenMaskOverlay(ScreenMaskConfig(), editing = false) }
        composeRule.onNodeWithTag("screen_mask").assertDoesNotExist()
    }

    @Test
    fun editor_moves_resizes_and_back_finishes_without_changing_enabled() {
        var config by mutableStateOf(ScreenMaskConfig())
        var editing by mutableStateOf(true)
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                ScreenMaskOverlay(config, editing)
                if (editing) {
                    ScreenMaskEditor(config, onChange = { config = it }, onFinish = { editing = false })
                }
            }
        }
        composeRule.onNodeWithTag("screen_mask").assertExists()
        composeRule.onNodeWithTag("screen_mask_editor").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.runOnIdle { assertThat(config.xRatio).isWithin(0.00001f).of(0.04f) }
        composeRule.onNodeWithTag("screen_mask_editor").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithText("画面遮挡 · 缩放模式", substring = true).assertExists()
        composeRule.onNodeWithTag("screen_mask_editor").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.runOnIdle { assertThat(config.heightRatio).isWithin(0.00001f).of(0.14f) }
        composeRule.onNodeWithTag("screen_mask_editor").performKeyInput { pressKey(Key.Back) }
        composeRule.onNodeWithTag("screen_mask_editor").assertDoesNotExist()
        composeRule.onNodeWithTag("screen_mask").assertDoesNotExist()
        composeRule.runOnIdle { assertThat(config.enabled).isFalse() }
    }

    @Test
    fun mask_uses_letterboxed_video_coordinates_and_later_layers_stay_visible() {
        composeRule.setContent {
            Box(Modifier.size(320.dp, 180.dp).background(Color.White).testTag("frame"), Alignment.Center) {
                Box(Modifier.aspectRatio(4f / 3f).background(Color.Blue)) {
                    ScreenMaskOverlay(ScreenMaskConfig(enabled = true), editing = false)
                }
                // 模拟上层弹幕/外挂字幕，确认它们仍能覆盖遮挡颜色。
                Box(Modifier.align(Alignment.BottomCenter).size(8.dp, 24.dp).background(Color.Red))
            }
        }
        val pixels = composeRule.onNodeWithTag("frame").captureToImage().toPixelMap()

        fun pixel(
            x: Float,
            y: Float,
        ) = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
        assertThat(pixel(0.05f, 0.85f)).isEqualTo(Color.White)
        assertThat(pixel(0.2f, 0.85f)).isEqualTo(Color.Black)
        assertThat(pixel(0.2f, 0.5f)).isEqualTo(Color.Blue)
        assertThat(pixel(0.5f, 0.9f)).isEqualTo(Color.Red)
    }
}
