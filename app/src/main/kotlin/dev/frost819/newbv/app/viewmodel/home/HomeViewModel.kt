package dev.frost819.newbv.app.viewmodel.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.app.data.AccountRepositoryImpl
import dev.frost819.newbv.biliapi.entity.home.RecommendPage
import dev.frost819.newbv.biliapi.entity.rank.PopularVideoPage
import dev.frost819.newbv.biliapi.entity.ugc.UgcItem
import dev.frost819.newbv.biliapi.repositories.RecommendVideoRepository
import dev.frost819.newbv.biliapi.repositories.UserRepository
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.HomeTopNavItem
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.data.quickentry.QuickEntry
import dev.frost819.newbv.data.quickentry.QuickEntryRepository
import dev.frost819.newbv.data.quickentry.QuickEntryType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import dev.frost819.newbv.biliapi.entity.ApiType as BiliApiType
import dev.frost819.newbv.data.datastore.ApiType as DataApiType

/** 网络请求超时时间（毫秒）。 */
private const val LOAD_TIMEOUT_MS = 10_000L

/**
 * 首页 Tab 状态。
 */
data class HomeUiState(
    val recommendItems: List<UgcItem> = emptyList(),
    /** 初始加载的推荐条数；收藏只与这批内容去重，不影响后续分页。 */
    val recommendFirstBatchSize: Int = 0,
    val recommendLoading: Boolean = false,
    val recommendHasMore: Boolean = true,
    val recommendError: Boolean = false,
    val popularItems: List<UgcItem> = emptyList(),
    val popularLoading: Boolean = false,
    val popularHasMore: Boolean = true,
    val popularError: Boolean = false,
    /** 本地保存的首页快捷收藏（最近收藏在前）。 */
    val quickEntries: List<QuickEntry> = emptyList(),
    val isLogin: Boolean = false,
    val currentUid: Long = 0L,
)

/**
 * 首页 ViewModel。
 *
 * 按可见 Tab 管理推荐、热门的数据加载、分页、刷新。
 * 使用 [StateFlow] 暴露状态，UI 通过 [uiState] 观察。
 *
 * @property recommendVideoRepository 推荐/热门数据仓库。
 * @property userRepository 收藏 UP 主的头像数据仓库。
 * @property accountRepository 账户仓库（监听登录状态变化）。
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val recommendVideoRepository: RecommendVideoRepository,
        private val userRepository: UserRepository,
        private val accountRepository: AccountRepositoryImpl,
        private val quickEntryRepository: QuickEntryRepository,
    ) : ViewModel() {
        private val logger = Loggers.get("HomeViewModel")

        /** 将 data 层 ApiType 映射为 bili-api 层 ApiType。 */
        private fun prefApiType(): BiliApiType =
            when (Prefs.apiType) {
                DataApiType.Web -> BiliApiType.Web
                DataApiType.App -> BiliApiType.App
            }

        private val _uiState = MutableStateFlow(HomeUiState())

        /** 当前账户的首页列表及加载状态。 */
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        private var recommendNextPage = RecommendPage()
        private var popularNextPage = PopularVideoPage()
        private var recommendJob: Job? = null
        private var popularJob: Job? = null
        private var recommendGeneration = 0
        private var popularGeneration = 0
        private var recommendLoaded = false
        private var popularLoaded = false

        init {
            val account = accountRepository.uiState.value
            _uiState.update { it.copy(isLogin = account.isLogin, currentUid = account.uid) }

            viewModelScope.launch {
                val avatars = mutableMapOf<Long, String>()
                quickEntryRepository.entries.collectLatest { entries ->
                    _uiState.update { it.copy(quickEntries = entries) }
                    // 旧收藏可能来自未携带头像的入口；先展示卡片，再补图，不阻塞推荐流。
                    entries.filter { it.type == QuickEntryType.UP && it.cover.isBlank() }.forEach { entry ->
                        val face =
                            runCatching {
                                avatars[entry.mid] ?: withTimeout(LOAD_TIMEOUT_MS) {
                                    userRepository.getUserInfo(entry.mid).face
                                }
                            }.getOrElse { error ->
                                if (error is CancellationException && error !is TimeoutCancellationException) {
                                    throw error
                                }
                                logger.error(error) { "Failed to load favorite UP avatar" }
                                ""
                            }
                        if (face.isNotBlank()) {
                            avatars[entry.mid] = face
                            _uiState.update { state ->
                                state.copy(
                                    quickEntries =
                                        state.quickEntries.map {
                                            if (it.key == entry.key) it.copy(cover = face) else it
                                        },
                                )
                            }
                        }
                    }
                }
            }

            viewModelScope.launch {
                accountRepository.uiState
                    .map { it.uid to it.isLogin }
                    .distinctUntilChanged()
                    .collect { (uid, isLogin) ->
                        val current = _uiState.value
                        if (uid != current.currentUid || isLogin != current.isLogin) {
                            invalidateRecommend()
                            invalidatePopular()
                            _uiState.update { it.copy(currentUid = uid, isLogin = isLogin) }
                        }
                    }
            }
        }

        /**
         * 加载推荐视频。
         *
         * 列表为空时视为首次加载，连续请求直到 >= 24 条或达到 3 次上限；
         * 列表非空时视为加载更多，只请求一页。
         *
         * 每页请求使用独立的 [LOAD_TIMEOUT_MS] 超时：首次补齐首屏时某一页超时
         * 不应导致已加载的数据被标记为失败（避免"部分列表 + 报错"）。仅当列表
         * 最终为空时才置 [HomeUiState.recommendError]。
         */
        fun loadRecommend() {
            val current = _uiState.value
            if (current.recommendLoading || !current.recommendHasMore) return
            val generation = recommendGeneration
            _uiState.update { it.copy(recommendLoading = true, recommendError = false) }
            recommendJob =
                viewModelScope.launch {
                    // 首次加载（列表为空）需要连续请求补齐首屏；已有数据时每次只追加一页，
                    val isFirstLoad = current.recommendItems.isEmpty()
                    val maxLoadCount = if (isFirstLoad) 3 else 1
                    var loadCount = 0
                    var failed = false
                    while (loadCount < maxLoadCount) {
                        val data =
                            try {
                                withTimeout(LOAD_TIMEOUT_MS) {
                                    recommendVideoRepository.getRecommendVideos(
                                        page = recommendNextPage,
                                        preferApiType = prefApiType(),
                                    )
                                }
                            } catch (error: TimeoutCancellationException) {
                                logger.error(error) { "Load recommend videos timeout" }
                                failed = true
                                break
                            } catch (error: CancellationException) {
                                // 非超时的取消（如 ViewModel cleared）必须重新抛出，否则破坏取消机制
                                throw error
                            } catch (error: Throwable) {
                                logger.error(error) { "Failed to load recommend videos" }
                                failed = true
                                break
                            }

                        if (generation != recommendGeneration) return@launch
                        recommendNextPage = data.nextPage
                        if (data.items.isEmpty()) {
                            _uiState.update { it.copy(recommendHasMore = false) }
                            break
                        }
                        _uiState.update {
                            it.copy(recommendItems = it.recommendItems + data.items)
                        }
                        loadCount++
                        if (!isFirstLoad || _uiState.value.recommendItems.size >= 24) break
                    }

                    if (generation != recommendGeneration) return@launch
                    recommendLoaded = !failed || _uiState.value.recommendItems.isNotEmpty()
                    // 记录初始批大小，供首页收藏去重使用（只影响第一批展示）
                    if (isFirstLoad) {
                        _uiState.update { it.copy(recommendFirstBatchSize = it.recommendItems.size) }
                    }

                    // 首屏补齐失败时，只要已拿到部分数据就静默停止（避免"部分列表 + 报错"）；
                    // 加载更多失败则照常报错，让底部提示可重试
                    val hasItems = _uiState.value.recommendItems.isNotEmpty()
                    val showError = failed && (!hasItems || !isFirstLoad)
                    _uiState.update { it.copy(recommendLoading = false, recommendError = showError) }
                }
        }

        /**
         * 清空推荐数据并重新加载。
         */
        fun refreshRecommend() {
            invalidateRecommend()
            loadRecommend()
        }

        private fun invalidateRecommend() {
            recommendGeneration++
            recommendJob?.cancel()
            recommendLoaded = false
            recommendNextPage = RecommendPage()
            _uiState.update {
                it.copy(
                    recommendItems = emptyList(),
                    recommendFirstBatchSize = 0,
                    recommendLoading = false,
                    recommendHasMore = true,
                    recommendError = false,
                )
            }
        }

        /**
         * 加载更多热门视频。
         *
         * 超过 [LOAD_TIMEOUT_MS] 未返回时标记为加载失败。
         */
        fun loadPopular() {
            val current = _uiState.value
            if (current.popularLoading || !current.popularHasMore) return
            val generation = popularGeneration
            _uiState.update { it.copy(popularLoading = true, popularError = false) }
            popularJob =
                viewModelScope.launch {
                    runCatching {
                        val data =
                            withTimeout(LOAD_TIMEOUT_MS) {
                                recommendVideoRepository.getPopularVideos(
                                    page = popularNextPage,
                                    preferApiType = prefApiType(),
                                )
                            }
                        if (generation != popularGeneration) return@launch
                        popularLoaded = true
                        popularNextPage = data.nextPage
                        _uiState.update {
                            it.copy(
                                popularItems = it.popularItems + data.list,
                                popularHasMore = !data.noMore,
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException && error !is TimeoutCancellationException) {
                            throw error
                        }
                        if (generation != popularGeneration) return@onFailure
                        logger.error(error) { "Failed to load popular videos" }
                        _uiState.update { it.copy(popularError = true) }
                    }

                    if (generation == popularGeneration) {
                        _uiState.update { it.copy(popularLoading = false) }
                    }
                }
        }

        /**
         * 清空热门数据并重新加载。
         */
        fun refreshPopular() {
            invalidatePopular()
            loadPopular()
        }

        private fun invalidatePopular() {
            popularGeneration++
            popularJob?.cancel()
            popularLoaded = false
            popularNextPage = PopularVideoPage()
            _uiState.update {
                it.copy(popularItems = emptyList(), popularLoading = false, popularHasMore = true, popularError = false)
            }
        }

        /**
         * 首次进入可见 Tab 时加载；成功空列表也会缓存，失败由显式刷新重试。
         *
         * @param tab 当前可见的首页 Tab。
         */
        fun ensureLoaded(tab: HomeTopNavItem) {
            when (tab) {
                HomeTopNavItem.Recommend, HomeTopNavItem.Dynamics ->
                    if (!recommendLoaded && !_uiState.value.recommendError) loadRecommend()
                HomeTopNavItem.Popular -> if (!popularLoaded && !_uiState.value.popularError) loadPopular()
                HomeTopNavItem.History, HomeTopNavItem.ToView -> Unit
            }
        }

        /**
         * 刷新当前可见 Tab；历史和稍后再看由 PersonalViewModel 管理。
         *
         * @param tab 用户请求刷新的首页 Tab。
         */
        fun refresh(tab: HomeTopNavItem) {
            when (tab) {
                HomeTopNavItem.Recommend, HomeTopNavItem.Dynamics -> refreshRecommend()
                HomeTopNavItem.Popular -> refreshPopular()
                HomeTopNavItem.History, HomeTopNavItem.ToView -> Unit
            }
        }

        /**
         * 加载当前 Tab 下一页，已加载完毕时跳过。
         *
         * @param tab 需要分页的首页 Tab。
         */
        fun loadMore(tab: HomeTopNavItem) {
            when (tab) {
                HomeTopNavItem.Recommend, HomeTopNavItem.Dynamics -> loadRecommend()
                HomeTopNavItem.Popular -> loadPopular()
                HomeTopNavItem.History, HomeTopNavItem.ToView -> Unit
            }
        }
    }
