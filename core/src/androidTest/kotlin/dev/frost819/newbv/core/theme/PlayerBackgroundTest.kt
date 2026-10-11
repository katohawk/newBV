package dev.frost819.newbv.core.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** 检查嵌套播放器主题的画面外留白，不只检查原生视频 View 的背景。 */
class PlayerBackgroundTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun wideMovieHasBlackTopAndBottomBars() = checkBars(2.4f, vertical = true)

    @Test
    fun narrowVideoHasBlackLeftAndRightBars() = checkBars(0.75f, vertical = false)

    private fun checkBars(
        ratio: Float,
        vertical: Boolean,
    ) {
        composeRule.setContent {
            BVTheme(themeMode = ThemeMode.Light) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).testTag("viewport")) {
                    BVTheme(themeMode = ThemeMode.Dark, backgroundColor = Color.Black) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.aspectRatio(ratio).background(Color.Red))
                        }
                    }
                }
            }
        }
        val pixels = composeRule.onNodeWithTag("viewport").captureToImage().toPixelMap()
        val midX = pixels.width / 2
        val midY = pixels.height / 2
        assertThat(pixels[midX, midY]).isEqualTo(Color.Red)
        if (vertical) {
            assertThat(pixels[midX, 1]).isEqualTo(Color.Black)
            assertThat(pixels[midX, pixels.height - 2]).isEqualTo(Color.Black)
        } else {
            assertThat(pixels[1, midY]).isEqualTo(Color.Black)
            assertThat(pixels[pixels.width - 2, midY]).isEqualTo(Color.Black)
        }
    }
}
