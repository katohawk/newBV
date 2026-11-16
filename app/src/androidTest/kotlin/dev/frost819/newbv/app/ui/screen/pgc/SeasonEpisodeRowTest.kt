package dev.frost819.newbv.app.ui.screen.pgc

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.frost819.newbv.app.ui.component.rememberFocusSaver
import dev.frost819.newbv.biliapi.entity.video.season.Episode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证历史入口进入自动重播路径，手动点击最后一集仍保留明确选择。 */
@RunWith(AndroidJUnit4::class)
class SeasonEpisodeRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun history_uses_automatic_restart_but_episode_card_keeps_explicit_selection() {
        val episodes =
            (1..2).map { index ->
                Episode(
                    id = index,
                    aid = index.toLong(),
                    bvid = "",
                    cid = index * 10L,
                    title = "第${index}集",
                    longTitle = "",
                    cover = "",
                    duration = 600,
                    dimension = null,
                )
            }
        composeRule.setContent {
            var playing by remember { mutableStateOf("") }
            MaterialTheme {
                Column {
                    SeasonEpisodeRow(
                        title = "正片",
                        episodes = episodes,
                        lastPlayedCid = episodes.last().cid,
                        onClick = { playing = it.title },
                        onResume = { playing = episodes.first().title },
                        onShowListDialog = {},
                        focusSaver = rememberFocusSaver(),
                        rowKey = "episodes",
                    )
                    Text("正在播放：$playing")
                }
            }
        }

        composeRule.onNodeWithText("上次播放到：第2集").performClick()
        composeRule.onNodeWithText("正在播放：第1集").assertIsDisplayed()

        composeRule
            .onNode(
                hasClickAction() and
                    (hasText("上次看到") or hasAnyDescendant(hasText("上次看到"))),
            ).performClick()
        composeRule.onNodeWithText("正在播放：第2集").assertIsDisplayed()
    }
}
