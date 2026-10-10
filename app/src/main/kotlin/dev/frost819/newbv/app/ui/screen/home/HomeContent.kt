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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import dev.frost819.newbv.app.ui.component.FocusSaver
import dev.frost819.newbv.app.ui.component.HomeTabItem
import dev.frost819.newbv.app.ui.component.TopNav
import dev.frost819.newbv.app.viewmodel.home.HomeViewModel
import dev.frost819.newbv.app.viewmodel.personal.PersonalViewModel
import dev.frost819.newbv.biliapi.entity.user.ToViewItem
import dev.frost819.newbv.data.datastore.HomeTopNavItem
import dev.frost819.newbv.data.datastore.PersonalTopNavItem
import dev.frost819.newbv.data.datastore.Prefs
import kotlinx.coroutines.delay
import androidx.compose.material3.Scaffold as Material3Scaffold

/**
 * 按实际稍后再看分组选择首张卡片；空列表不请求卡片焦点。
 *
 * @param items 当前账户已加载的稍后再看列表。
 */
internal fun firstToViewItemKey(items: List<ToViewItem>): String? =
    when {
        items.any { it.progress != -1 } -> "toview_unwatched_0"
        items.isNotEmpty() -> "toview_watched_0"
        else -> null
    }

/**
 * 首页内容（TopNav + 4 个子 Tab）。
 *
 * Tab 顺序固定为：推荐、热门、历史、稍后再看。
 * Tab 切换使用 [AnimatedContent] 横向滑动并恢复纵向位置；推荐最左侧进入侧栏，最后一页右侧循环到推荐。
 * 首页四个 Tab 都保留缓存，不因切换自动刷新；点击标题或菜单键可手动刷新。
 *
 * @param navFocusRequester 顶部 Tab 的焦点请求器（由 MainScreen 传入）。
 * @param navController 导航控制器（跳转详情页等）。
 * @param focusSaver 焦点恢复器（由 MainScreen 共享传入）。
 * @param onFocusLeftNav 推荐列表最左侧按左键时聚焦当前侧栏项。
 * @param isActive 当前内容是否仍为主页面选中的导航项。
 */
@Composable
fun HomeContent(
    navFocusRequester: FocusRequester,
    navController: NavController,
    focusSaver: FocusSaver,
    onFocusLeftNav: () -> Unit,
    isActive: Boolean = true,
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
    val tabContentState = rememberSaveableStateHolder()
    val tabFocusKeys = rememberSaveable { hashMapOf<HomeTopNavItem, String>() }
    var pendingTabFocus by remember { mutableStateOf<HomeTopNavItem?>(null) }
    val selectedTabFocusRequester = remember { FocusRequester() }
    var focusOnContent by remember { mutableStateOf(false) }
    // 顶部 Tab 区域是否持有焦点（冷启动默认聚焦 TopNav）
    var navHasFocus by remember { mutableStateOf(false) }
    // 冷启动默认把光标落到第一个 Tab 的第一个视频卡片上；
    // 落焦成功或用户已主动移动焦点后置 false，避免后续抢占焦点
    var pendingInitialFocus by rememberSaveable { mutableStateOf(true) }
    val uiState by viewModel.uiState.collectAsState()
    // 与个人页共享同一 ViewModel 实例；首页切换只加载尚未加载的数据。
    val personalViewModel: PersonalViewModel = hiltViewModel()
    val personalState by personalViewModel.uiState.collectAsState()
    val accountKey =
        when (selectedTab) {
            HomeTopNavItem.History, HomeTopNavItem.ToView -> personalState.currentUid to personalState.isLogin
            else -> uiState.currentUid to uiState.isLogin
        }
    LaunchedEffect(selectedTab, accountKey, isActive) {
        if (isActive) {
            when (selectedTab) {
                HomeTopNavItem.History -> personalViewModel.ensureLoaded(PersonalTopNavItem.History)
                HomeTopNavItem.ToView -> personalViewModel.ensureLoaded(PersonalTopNavItem.ToView)
                else -> viewModel.ensureLoaded(selectedTab)
            }
        }
    }

    val tabItems = remember { homeTabs.map { HomeTabItem(it) } }
    val switchTabAtBoundary: (Int) -> Unit = { step ->
        if (isActive && pendingTabFocus == null) {
            pendingInitialFocus = false
            tabFocusKeys[selectedTab] = focusSaver.savedKeyValue()
            if (step < 0 && selectedTab == homeTabs.first()) {
                onFocusLeftNav()
            } else {
                val next = homeTabs[(homeTabs.indexOf(selectedTab) + step + homeTabs.size) % homeTabs.size]
                pendingTabFocus = next
                selectedTab = next
            }
        }
    }

    val firstSelectedKey =
        when (selectedTab) {
            HomeTopNavItem.Recommend, HomeTopNavItem.Dynamics -> firstRecommendItemKey(uiState)
            HomeTopNavItem.Popular -> if (uiState.popularItems.isNotEmpty()) "popular_0" else null
            HomeTopNavItem.History -> if (personalState.historyItems.isNotEmpty()) "history_0" else null
            HomeTopNavItem.ToView -> firstToViewItemKey(personalViewModel.toViewItems)
        }
    val selectedLoading =
        when (selectedTab) {
            HomeTopNavItem.Recommend, HomeTopNavItem.Dynamics -> uiState.recommendLoading
            HomeTopNavItem.Popular -> uiState.popularLoading
            HomeTopNavItem.History -> personalState.historyLoading
            HomeTopNavItem.ToView -> personalState.toViewLoading
        }
    LaunchedEffect(selectedTab, pendingTabFocus, firstSelectedKey, selectedLoading, isActive) {
        if (!isActive) {
            pendingTabFocus = null
            return@LaunchedEffect
        }
        if (pendingTabFocus != selectedTab) return@LaunchedEffect
        // 等切换后的 LazyGrid 挂载，并从该 Tab 自己保存的滚动位置恢复焦点。
        delay(50)
        val savedKey = tabFocusKeys[selectedTab]
        repeat(30) {
            val focused =
                savedKey?.let { key ->
                    runCatching { focusSaver.focusRequesterFor(key).requestFocus() }.getOrDefault(false)
                } ?: false
            if (focused) {
                pendingTabFocus = null
                return@LaunchedEffect
            }
            if (savedKey == null &&
                firstSelectedKey != null &&
                runCatching { focusSaver.focusRequesterFor(firstSelectedKey).requestFocus() }.getOrDefault(false)
            ) {
                pendingTabFocus = null
                return@LaunchedEffect
            }
            delay(16)
        }
        if (firstSelectedKey != null) {
            runCatching { focusSaver.focusRequesterFor(firstSelectedKey).requestFocus() }
            pendingTabFocus = null
        } else {
            // 空列表或加载失败仍有可操作的焦点；数据到达后会再次进入列表。
            runCatching { selectedTabFocusRequester.requestFocus() }
            if (!selectedLoading) pendingTabFocus = null
        }
    }

    // 首个 Tab 的第一个卡片对应的焦点 key（各子页面的 focusSaverItem 命名不同）
    val firstTabInitialFocusKey =
        when (firstTab) {
            HomeTopNavItem.Recommend, HomeTopNavItem.Dynamics -> firstRecommendItemKey(uiState)
            HomeTopNavItem.Popular -> "popular_0"
            HomeTopNavItem.History -> "history_0"
            HomeTopNavItem.ToView -> firstToViewItemKey(personalViewModel.toViewItems)
        }

    // 冷启动：MainScreen 会先聚焦 TopNav；等首屏数据加载出第一张卡片后，
    // 把焦点移到第一个视频上。若用户已把焦点移入内容区/左侧栏，或手动切换了
    // Tab，则放弃本次自动落焦，不与用户操作抢占焦点。
    if (pendingInitialFocus && isActive) {
        LaunchedEffect(firstTab, firstTabInitialFocusKey) {
            // 等 TopNav 至少获得过一次焦点再判断"用户移走了焦点"，
            // 避免冷启动时 MainScreen 尚未聚焦 TopNav 导致的竞态误判
            var navFocusSeen = false
            var attempts = 0
            while (attempts < 100) {
                delay(200)
                attempts++
                if (focusOnContent || selectedTab != firstTab) break
                if (!navFocusSeen) {
                    if (navHasFocus) navFocusSeen = true
                    continue
                }
                if (!navHasFocus) break
                val key = firstTabInitialFocusKey ?: continue
                val focused =
                    runCatching {
                        focusSaver.focusRequesterFor(key).requestFocus()
                    }.getOrDefault(false)
                if (focused) break
            }
            pendingInitialFocus = false
        }
    }

    Material3Scaffold(
        topBar = {
            TopNav(
                modifier =
                    Modifier
                        .focusRequester(navFocusRequester)
                        .onFocusChanged { navHasFocus = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                pendingTabFocus = null
                            }
                            false
                        },
                items = tabItems,
                selectedIndex = tabItems.indexOf(HomeTabItem(selectedTab)),
                isLargePadding = !focusOnContent,
                selectedTabFocusRequester = selectedTabFocusRequester,
                onSelectedChanged = selectTab@{ nav ->
                    val tab = (nav as HomeTabItem).item
                    // 切换时旧网格销毁会临时自动聚焦旧标题，不能让它撤销目标 Tab。
                    if (pendingTabFocus != null && tab != selectedTab) return@selectTab
                    if (tab != selectedTab) {
                        val key = focusSaver.savedKeyValue()
                        val prefix =
                            when (selectedTab) {
                                HomeTopNavItem.Recommend, HomeTopNavItem.Dynamics -> "rcmd_"
                                HomeTopNavItem.Popular -> "popular_"
                                HomeTopNavItem.History -> "history_"
                                HomeTopNavItem.ToView -> "toview_"
                            }
                        if (key.startsWith(prefix)) tabFocusKeys[selectedTab] = key
                        pendingTabFocus = null
                    }
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
                tabContentState.SaveableStateProvider(screen) {
                    when (screen) {
                        HomeTopNavItem.Recommend ->
                            RecommendScreen(
                                viewModel = viewModel,
                                navController = navController,
                                focusSaver = focusSaver,
                                onFocusTopNav = { navFocusRequester.requestFocus() },
                                onTabBoundary = switchTabAtBoundary,
                            )
                        HomeTopNavItem.Popular ->
                            PopularScreen(
                                viewModel = viewModel,
                                navController = navController,
                                focusSaver = focusSaver,
                                onTabBoundary = switchTabAtBoundary,
                            )
                        HomeTopNavItem.History ->
                            dev.frost819.newbv.app.ui.screen.personal.HistoryScreen(
                                viewModel = personalViewModel,
                                navController = navController,
                                focusSaver = focusSaver,
                                onTabBoundary = switchTabAtBoundary,
                            )
                        HomeTopNavItem.ToView ->
                            dev.frost819.newbv.app.ui.screen.personal.ToViewScreen(
                                viewModel = personalViewModel,
                                navController = navController,
                                focusSaver = focusSaver,
                                onTabBoundary = switchTabAtBoundary,
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
}
