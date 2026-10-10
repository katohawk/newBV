package dev.frost819.newbv.app.ui.component.videocard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.tv.material3.MaterialTheme as TvMaterialTheme

/**
 * [SmallVideoCard] 的插桩测试。
 *
 * 验证卡片显示内容（标题、UP 主名、播放数、时长）。
 * 点击与长按交互因 TV Material3 alpha 版本限制暂不纳入。
 */
@RunWith(AndroidJUnit4::class)
class SmallVideoCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val fakeData =
        VideoCardData(
            avid = 12345L,
            title = "测试视频标题",
            cover = "http://example.com/cover.jpg",
            upName = "测试UP主",
            upMid = 100L,
            playString = "1.0万",
            danmakuString = "500",
            timeString = "02:00",
            pubTime = "2024-01-01",
        )

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            TvMaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun titles_reserve_two_lines_and_ellipsis_overflow() {
        val titles = listOf("短标题", "第一行\n第二行", "超长视频标题".repeat(30))
        setContent {
            Column {
                titles.forEachIndexed { index, title ->
                    SmallVideoCard(
                        modifier = Modifier.width(200.dp).testTag("title_card_$index"),
                        data = fakeData.copy(title = title),
                        onClick = {},
                    )
                }
            }
        }

        val heights =
            titles.indices.map { index ->
                composeRule
                    .onNodeWithTag("title_card_$index", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot.height
            }
        assertThat(heights[0]).isEqualTo(heights[1])
        assertThat(heights[2]).isEqualTo(heights[1])

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(titles.last(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertThat(layouts.single().lineCount).isEqualTo(2)
        assertThat(layouts.single().isLineEllipsized(1)).isTrue()
    }

    @Test
    fun season_titles_reserve_two_lines_and_ellipsis_overflow() {
        val titles = listOf("短标题", "第一行\n第二行", "超长番剧标题".repeat(30))
        setContent {
            Row {
                titles.forEachIndexed { index, title ->
                    SeasonCard(
                        modifier = Modifier.width(120.dp).testTag("title_card_$index"),
                        data = SeasonCardData(seasonId = 1, title = title, cover = ""),
                        onClick = {},
                        quickEntry = null,
                    )
                }
            }
        }

        val heights =
            titles.indices.map { index ->
                composeRule
                    .onNodeWithTag("title_card_$index", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot.height
            }
        assertThat(heights[0]).isEqualTo(heights[1])
        assertThat(heights[2]).isEqualTo(heights[1])

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(titles.last(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertThat(layouts.single().lineCount).isEqualTo(2)
        assertThat(layouts.single().isLineEllipsized(1)).isTrue()
    }

    @Test
    fun displays_title() {
        setContent {
            Box(modifier = Modifier.width(300.dp)) {
                SmallVideoCard(data = fakeData, onClick = {})
            }
        }
        composeRule.onNodeWithText("测试视频标题").assertIsDisplayed()
    }

    @Test
    fun displays_upName() {
        setContent {
            Box(modifier = Modifier.width(300.dp)) {
                SmallVideoCard(data = fakeData, onClick = {})
            }
        }
        composeRule.onNodeWithText("测试UP主").assertIsDisplayed()
    }

    @Test
    fun displays_playCount() {
        setContent {
            Box(modifier = Modifier.width(300.dp)) {
                SmallVideoCard(data = fakeData, onClick = {})
            }
        }
        composeRule.onNodeWithText("1.0万").assertIsDisplayed()
    }

    @Test
    fun displays_duration() {
        setContent {
            Box(modifier = Modifier.width(300.dp)) {
                SmallVideoCard(data = fakeData, onClick = {})
            }
        }
        composeRule.onNodeWithText("02:00").assertIsDisplayed()
    }

    @Test
    fun displays_allFieldsTogether() {
        setContent {
            Box(modifier = Modifier.width(300.dp)) {
                SmallVideoCard(data = fakeData, onClick = {})
            }
        }
        composeRule.onNodeWithText("测试视频标题").assertIsDisplayed()
        composeRule.onNodeWithText("测试UP主").assertIsDisplayed()
        composeRule.onNodeWithText("1.0万").assertIsDisplayed()
        composeRule.onNodeWithText("02:00").assertIsDisplayed()
    }
}
