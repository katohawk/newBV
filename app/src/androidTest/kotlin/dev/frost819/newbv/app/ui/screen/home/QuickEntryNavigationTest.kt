package dev.frost819.newbv.app.ui.screen.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.frost819.newbv.app.ui.component.focusSaverItem
import dev.frost819.newbv.app.ui.component.rememberFocusSaver
import dev.frost819.newbv.app.ui.component.videocard.SmallVideoCard
import dev.frost819.newbv.app.ui.component.videocard.VideoCardData
import dev.frost819.newbv.app.ui.navigation.HomeRoute
import dev.frost819.newbv.app.ui.navigation.VideoPlayerRoute
import dev.frost819.newbv.app.viewmodel.quickentry.QuickEntryUiEffect
import dev.frost819.newbv.data.quickentry.QuickEntry
import dev.frost819.newbv.data.quickentry.QuickEntryType
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 收藏只导航一次，传递续播上下文，返回后恢复原卡片焦点。 */
@RunWith(AndroidJUnit4::class)
class QuickEntryNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun favorite_resume_route_and_return_focus() {
        val effects = MutableSharedFlow<QuickEntryUiEffect>(extraBufferCapacity = 1)
        val entry = QuickEntry(QuickEntryType.SEASON, "测试收藏", seasonId = 100)
        var controller: androidx.navigation.NavHostController? = null
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                controller = nav
                NavHost(navController = nav, startDestination = HomeRoute) {
                    composable<HomeRoute> {
                        val saver = rememberFocusSaver()
                        saver.RestoreFocus()
                        CollectQuickEntryEffects(effects, nav)
                        Box(Modifier.width(300.dp)) {
                            SmallVideoCard(
                                data = VideoCardData(avid = 2, title = entry.title, cover = "", upName = ""),
                                modifier = Modifier.focusSaverItem(saver, "favorite:${entry.key}").testTag("favorite"),
                                onClick = {
                                    effects.tryEmit(QuickEntryUiEffect.PlayEpisode(2, 20, "第二集", "", 2, 100, 1, 123))
                                },
                            )
                        }
                        LaunchedEffect(Unit) {
                            if (saver.savedKeyValue().isEmpty()) {
                                saver
                                    .focusRequesterFor(
                                        "favorite:${entry.key}",
                                    ).requestFocus()
                            }
                        }
                    }
                    composable<VideoPlayerRoute> {
                        Text("播放器", Modifier.testTag("player"))
                    }
                }
            }
        }
        compose.onNodeWithTag("favorite").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val nav = requireNotNull(controller)
            val route = requireNotNull(nav.currentBackStackEntry).toRoute<VideoPlayerRoute>()
            assertEquals(20L, route.cid)
            assertEquals(100, route.seasonId)
            assertEquals(123, route.startPosition)
            nav.popBackStack()
        }
        compose.waitForIdle()
        // 标签在卡片外层 Column，实际可聚焦节点是内层 Surface。
        val focusedCard = isFocused() and hasAnyAncestor(hasTestTag("favorite"))
        compose.waitUntil(5_000) { compose.onAllNodes(focusedCard).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(focusedCard).assertExists()
    }
}
