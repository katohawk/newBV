package dev.frost819.newbv.app.ui.screen.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import dev.frost819.newbv.app.ui.component.FocusSaver
import dev.frost819.newbv.app.ui.component.HomeTabItem
import dev.frost819.newbv.app.ui.component.TopNav
import dev.frost819.newbv.app.viewmodel.home.HomeViewModel
import dev.frost819.newbv.app.viewmodel.personal.PersonalViewModel
import dev.frost819.newbv.data.datastore.HomeTopNavItem
import dev.frost819.newbv.data.datastore.PersonalTopNavItem
import dev.frost819.newbv.data.datastore.Prefs
import androidx.compose.material3.Scaffold as Material3Scaffold

/**
 * 首页内容（TopNav + 4 个子 Tab）。
 *
 * Tab 顺序固定为：推荐、热门、历史、稍后再看。
 * Tab 切换使用 [AnimatedContent] 横向滑动；滑动焦点切换不刷新，
 * 仅点击 Tab 按钮时刷新一次该 Tab。菜单键同样刷新当前 Tab。
 *
 * @param navFocusRequester 顶部 Tab 的焦点请求器（由 MainScreen 传入）。
 * @param navController 导航控制器（跳转详情页等）。
 * @param focusSaver 焦点恢复器（由 MainScreen 共享传入）。
 */
@Composable
fun HomeContent(
    navFocusRequester: FocusRequester,
    navController: NavController,
    focusSaver: FocusSaver,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val homeTabs =
        remember {
            listOf(
                HomeTopNavItem.Recommend,
                HomeTopNavItem.Popular,
                HomeTopNavItem.History,
                HomeTopNavItem.ToView,
            )
        }
    val firstTab =
        remember {
            Prefs.firstHomeTopNavItem.takeIf { it in homeTabs } ?: HomeTopNavItem.Recommend
        }
    var selectedTab by rememberSaveable { mutableStateOf(firstTab) }
    var focusOnContent by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()
    // 与个人页共享同一 ViewModel 实例（同一导航目的地作用域），数据只加载一次
    val personalViewModel: PersonalViewModel = hiltViewModel()

    val tabItems = remember { homeTabs.map { HomeTabItem(it) } }

    Material3Scaffold(
        topBar = {
            TopNav(
                modifier = Modifier.focusRequester(navFocusRequester),
                items = tabItems,
                selectedIndex = tabItems.indexOf(HomeTabItem(selectedTab)),
                isLargePadding = !focusOnContent,
                onSelectedChanged = { nav ->
                    val tab = (nav as HomeTabItem).item
                    selectedTab = tab
                },
                onClick = { nav ->
                    val tab = (nav as HomeTabItem).item
                    // 仅显式点击 Tab 按钮时刷新一次
                    when (tab) {
                        HomeTopNavItem.History ->
                            personalViewModel.refresh(PersonalTopNavItem.History)
                        HomeTopNavItem.ToView ->
                            personalViewModel.refresh(PersonalTopNavItem.ToView)
                        else -> viewModel.refresh(tab)
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .onFocusChanged { focusOnContent = it.hasFocus }
                    .onKeyEvent { event ->
                        if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                            when (selectedTab) {
                                HomeTopNavItem.History ->
                                    personalViewModel.refresh(PersonalTopNavItem.History)
                                HomeTopNavItem.ToView ->
                                    personalViewModel.refresh(PersonalTopNavItem.ToView)
                                else -> viewModel.refresh(selectedTab)
                            }
                            navFocusRequester.requestFocus()
                            return@onKeyEvent true
                        }
                        false
                    },
        ) {
            AnimatedContent(
                targetState = selectedTab,
                label = "home-animated-content",
                transitionSpec = {
                    val coefficient = 10
                    if (tabItems.indexOf(HomeTabItem(targetState)) <
                        tabItems.indexOf(HomeTabItem(initialState))
                    ) {
                        fadeIn() + slideInHorizontally { -it / coefficient } togetherWith
                            fadeOut() + slideOutHorizontally { it / coefficient }
                    } else {
                        fadeIn() + slideInHorizontally { it / coefficient } togetherWith
                            fadeOut() + slideOutHorizontally { -it / coefficient }
                    }
                },
            ) { screen ->
                when (screen) {
                    HomeTopNavItem.Recommend ->
                        RecommendScreen(
                            viewModel = viewModel,
                            navController = navController,
                            focusSaver = focusSaver,
                        )
                    HomeTopNavItem.Popular ->
                        PopularScreen(
                            viewModel = viewModel,
                            navController = navController,
                            focusSaver = focusSaver,
                        )
                    HomeTopNavItem.History ->
                        dev.frost819.newbv.app.ui.screen.personal.HistoryScreen(
                            viewModel = personalViewModel,
                            navController = navController,
                            focusSaver = focusSaver,
                        )
                    HomeTopNavItem.ToView ->
                        dev.frost819.newbv.app.ui.screen.personal.ToViewScreen(
                            viewModel = personalViewModel,
                            navController = navController,
                            focusSaver = focusSaver,
                        )
                    // 动态 Tab 已从首页移除；保留分支以穷尽枚举（偏好兼容旧数据）
                    HomeTopNavItem.Dynamics ->
                        PopularScreen(
                            viewModel = viewModel,
                            navController = navController,
                            focusSaver = focusSaver,
                        )
                }
            }
        }
    }
}
