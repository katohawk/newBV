package dev.frost819.newbv.player

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.player.impl.exo.ExoMediaPlayer
import org.junit.Rule
import org.junit.Test

/** 验证原生播放器的留白及加载遮罩为纯黑，不继承应用主题颜色。 */
@OptIn(UnstableApi::class)
class BvVideoPlayerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun playerBackgroundAndShutterAreBlack() {
        lateinit var player: ExoMediaPlayer
        composeRule.runOnUiThread {
            player = ExoMediaPlayer(composeRule.activity, VideoPlayerOptions())
        }
        try {
            composeRule.setContent { BvVideoPlayer(videoPlayer = player) }
            composeRule.runOnIdle {
                val view = descendants(composeRule.activity.window.decorView).filterIsInstance<PlayerView>().single()
                assertThat((view.background as ColorDrawable).color).isEqualTo(Color.BLACK)
                val shutter = view.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)
                assertThat((shutter.background as ColorDrawable).color).isEqualTo(Color.BLACK)
            }
        } finally {
            composeRule.runOnUiThread { player.release() }
        }
    }

    private fun descendants(view: View): Sequence<View> =
        sequence {
            yield(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
            }
        }
}
