package dev.frost819.newbv.app.ui.screen.search

import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.frost819.newbv.biliapi.repositories.SearchType
import dev.frost819.newbv.app.ui.navigation.SearchResultRoute
import dev.frost819.newbv.app.viewmodel.search.SearchResultViewModel

/**
 * 搜索结果页导航注册。
 *
 * [SearchResultRoute] 对应全屏 [SearchResultContent]，keyword 通过路由参数传递；
 * 可选 [SearchResultRoute.searchType] 指定初始搜索类型（用于收藏的搜索入口直达）。
 */
fun NavGraphBuilder.searchResultScreen(navController: NavController) {
    composable<SearchResultRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<SearchResultRoute>()
        val viewModel: SearchResultViewModel = hiltViewModel()
        val initialSearchType =
            remember(route.searchType) {
                route.searchType?.let { name -> SearchType.entries.firstOrNull { it.name == name } }
            }
        SearchResultContent(
            viewModel = viewModel,
            keyword = route.keyword,
            initialSearchType = initialSearchType,
            navController = navController,
        )
    }
}
