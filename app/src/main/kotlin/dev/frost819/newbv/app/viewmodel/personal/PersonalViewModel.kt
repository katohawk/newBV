package dev.frost819.newbv.app.viewmodel.personal

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.app.data.AccountRepositoryImpl
import dev.frost819.newbv.biliapi.entity.FavoriteFolderData
import dev.frost819.newbv.biliapi.entity.FavoriteFolderMetadata
import dev.frost819.newbv.biliapi.entity.FavoriteItem
import dev.frost819.newbv.biliapi.entity.season.FollowingSeason
import dev.frost819.newbv.biliapi.entity.season.FollowingSeasonStatus
import dev.frost819.newbv.biliapi.entity.season.FollowingSeasonType
import dev.frost819.newbv.biliapi.entity.user.HistoryItem
import dev.frost819.newbv.biliapi.entity.user.ToViewItem
import dev.frost819.newbv.biliapi.repositories.FavoriteRepository
import dev.frost819.newbv.biliapi.repositories.HistoryRepository
import dev.frost819.newbv.biliapi.repositories.SeasonRepository
import dev.frost819.newbv.biliapi.repositories.ToViewRepository
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.PersonalTopNavItem
import dev.frost819.newbv.data.datastore.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** PersonalViewModel 的 UI 效果（一次性事件）。 */
sealed interface PersonalUiEffect {
    data class ShowToast(
        val message: String,
    ) : PersonalUiEffect
}

/**
 * 个人页 UI 状态。
 *
 * 管理稍后再看、历史、收藏、追番四个 Tab 的数据。
 *
 * @property toViewLoading 稍后再看加载中。
 * @property toViewError 稍后再看加载失败。
 * @property historyItems 历史列表。
 * @property historyLoading 历史加载中。
 * @property historyError 历史加载失败。
 * @property historyHasMore 历史是否还有更多。
 * @property favoriteFolders 收藏夹列表。
 * @property favoriteItems 当前收藏夹视频列表。
 * @property favoriteLoading 收藏加载中。
 * @property favoriteError 收藏加载失败。
 * @property favoriteHasMore 收藏夹是否还有更多。
 * @property currentFolderId 当前选中的收藏夹 ID。
 * @property followingSeasons 追番列表。
 * @property followingLoading 追番加载中。
 * @property followingError 追番加载失败。
 * @property followingHasMore 追番是否还有更多。
 * @property followingType 追番类型筛选。
 * @property followingStatus 追番状态筛选。
 * @property isLogin 是否已登录。
 */
data class PersonalUiState(
    val toViewLoading: Boolean = false,
    val toViewError: Boolean = false,
    val historyItems: List<HistoryItem> = emptyList(),
    val historyLoading: Boolean = false,
    val historyError: Boolean = false,
    val historyHasMore: Boolean = true,
    val favoriteFolders: List<FavoriteFolderMetadata> = emptyList(),
    val favoriteItems: List<FavoriteItem> = emptyList(),
    val favoriteLoading: Boolean = false,
    val favoriteError: Boolean = false,
    val favoriteHasMore: Boolean = true,
    val currentFolderId: Long = -1L,
    val followingSeasons: List<FollowingSeason> = emptyList(),
    val followingLoading: Boolean = false,
    val followingError: Boolean = false,
    val followingHasMore: Boolean = true,
    val followingType: FollowingSeasonType = FollowingSeasonType.Bangumi,
    val followingStatus: FollowingSeasonStatus = FollowingSeasonStatus.All,
    val isLogin: Boolean = false,
    /** 当前账户 UID；变化时可见页面重新按需加载。 */
    val currentUid: Long = 0L,
)

/**
 * 个人页 ViewModel。
 *
 * 管理稍后再看、历史、收藏、追番四个 Tab 的数据加载、分页、刷新。
 * 使用 [StateFlow] 暴露状态，UI 通过 [uiState] 观察。
 *
 * @property toViewRepository 稍后再看仓库。
 * @property historyRepository 历史仓库。
 * @property favoriteRepository 收藏仓库。
 * @property seasonRepository 追番仓库。
 */
@HiltViewModel
class PersonalViewModel
    @Inject
    constructor(
        private val toViewRepository: ToViewRepository,
        private val historyRepository: HistoryRepository,
        private val favoriteRepository: FavoriteRepository,
        private val seasonRepository: SeasonRepository,
        private val accountRepository: AccountRepositoryImpl,
    ) : ViewModel() {
        private val logger = Loggers.get("PersonalViewModel")
        private val _effect = MutableSharedFlow<PersonalUiEffect>()

        /** 删除稍后再看等操作的一次性提示。 */
        val effect = _effect.asSharedFlow()

        private fun prefApiType(): BiliApiType =
            when (Prefs.apiType) {
                DataApiType.Web -> BiliApiType.Web
                DataApiType.App -> BiliApiType.App
            }

        private val _uiState =
            MutableStateFlow(
                PersonalUiState(
                    isLogin = accountRepository.uiState.value.isLogin,
                    currentUid = accountRepository.uiState.value.uid,
                ),
            )

        /** 当前账户的个人列表及加载状态。 */
        val uiState: StateFlow<PersonalUiState> = _uiState.asStateFlow()

        /** 原地更新的稍后再看列表，删除后保持相邻卡片的焦点。 */
        val toViewItems = mutableStateListOf<ToViewItem>()

        private val jobs = mutableMapOf<PersonalTopNavItem, Job>()
        private val generations = PersonalTopNavItem.entries.associateWith { 0 }.toMutableMap()
        private val loadedTabs = mutableSetOf<PersonalTopNavItem>()
        private var historyCursor = 0L
        private var favoritePageNumber = 1
        private var followingPageNumber = 1

        init {
            viewModelScope.launch {
                accountRepository.uiState
                    .map { it.uid to it.isLogin }
                    .distinctUntilChanged()
                    .collect { (uid, isLogin) ->
                        val current = _uiState.value
                        if (uid != current.currentUid || isLogin != current.isLogin) {
                            PersonalTopNavItem.entries.forEach { invalidate(it) }
                            _uiState.update { it.copy(currentUid = uid, isLogin = isLogin) }
                        }
                    }
            }
        }

        /**
         * 首次显示当前 Tab 才加载；缓存空结果，失败等待显式重试。
         *
         * @param tab 当前可见的个人页 Tab。
         */
        fun ensureLoaded(tab: PersonalTopNavItem) {
            val state = _uiState.value
            val error =
                when (tab) {
                    PersonalTopNavItem.ToView -> state.toViewError
                    PersonalTopNavItem.History -> state.historyError
                    PersonalTopNavItem.Favorite -> state.favoriteError
                    PersonalTopNavItem.FollowingSeason -> state.followingError
                }
            if (tab in loadedTabs || error) return
            when (tab) {
                PersonalTopNavItem.ToView -> loadToView()
                PersonalTopNavItem.History -> loadHistory()
                PersonalTopNavItem.Favorite -> loadFavoriteFolders()
                PersonalTopNavItem.FollowingSeason -> loadFollowingSeasons()
            }
        }

        private fun isCurrent(
            tab: PersonalTopNavItem,
            generation: Int,
        ): Boolean = generations[tab] == generation

        // 每个 Tab 单独取消和递增代次，旧账户/旧筛选的晚响应不能修改新请求状态。
        private fun load(
            tab: PersonalTopNavItem,
            hasMore: Boolean = true,
            request: suspend (Int) -> Unit,
        ) {
            if (!_uiState.value.isLogin || jobs[tab]?.isActive == true || !hasMore) return
            val generation = generations.getValue(tab)
            updateLoading(tab, loading = true)
            jobs[tab] =
                viewModelScope.launch {
                    try {
                        request(generation)
                        if (isCurrent(tab, generation)) loadedTabs.add(tab)
                    } catch (error: TimeoutCancellationException) {
                        recordLoadError(tab, generation, error)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        recordLoadError(tab, generation, error)
                    } finally {
                        if (isCurrent(tab, generation)) {
                            updateLoading(tab, loading = false, error = hasError(tab))
                        }
                    }
                }
        }

        private fun recordLoadError(
            tab: PersonalTopNavItem,
            generation: Int,
            error: Exception,
        ) {
            if (isCurrent(tab, generation)) {
                logger.error(error) { "Failed to load $tab" }
                updateLoading(tab, loading = false, error = true)
            }
        }

        private fun hasError(tab: PersonalTopNavItem): Boolean =
            when (tab) {
                PersonalTopNavItem.ToView -> _uiState.value.toViewError
                PersonalTopNavItem.History -> _uiState.value.historyError
                PersonalTopNavItem.Favorite -> _uiState.value.favoriteError
                PersonalTopNavItem.FollowingSeason -> _uiState.value.followingError
            }

        private fun updateLoading(
            tab: PersonalTopNavItem,
            loading: Boolean,
            error: Boolean = false,
        ) {
            _uiState.update {
                when (tab) {
                    PersonalTopNavItem.ToView -> it.copy(toViewLoading = loading, toViewError = error)
                    PersonalTopNavItem.History -> it.copy(historyLoading = loading, historyError = error)
                    PersonalTopNavItem.Favorite -> it.copy(favoriteLoading = loading, favoriteError = error)
                    PersonalTopNavItem.FollowingSeason -> it.copy(followingLoading = loading, followingError = error)
                }
            }
        }

        private fun invalidate(
            tab: PersonalTopNavItem,
            keepFolders: Boolean = false,
            preserveItems: Boolean = false,
        ) {
            generations[tab] = generations.getValue(tab) + 1
            jobs.remove(tab)?.cancel()
            loadedTabs.remove(tab)
            when (tab) {
                PersonalTopNavItem.ToView -> {
                    if (!preserveItems) toViewItems.clear()
                    _uiState.update { it.copy(toViewLoading = false, toViewError = false) }
                }
                PersonalTopNavItem.History -> {
                    if (!preserveItems) historyCursor = 0L
                    _uiState.update {
                        it.copy(
                            historyItems = if (preserveItems) it.historyItems else emptyList(),
                            historyLoading = false,
                            historyHasMore = if (preserveItems) it.historyHasMore else true,
                            historyError = false,
                        )
                    }
                }
                PersonalTopNavItem.Favorite -> {
                    favoritePageNumber = 1
                    _uiState.update {
                        it.copy(
                            favoriteFolders = if (keepFolders) it.favoriteFolders else emptyList(),
                            favoriteItems = emptyList(),
                            favoriteLoading = false,
                            favoriteHasMore = true,
                            favoriteError = false,
                            currentFolderId = -1L,
                        )
                    }
                }
                PersonalTopNavItem.FollowingSeason -> {
                    followingPageNumber = 1
                    _uiState.update {
                        it.copy(
                            followingSeasons = emptyList(),
                            followingLoading = false,
                            followingHasMore = true,
                            followingError = false,
                        )
                    }
                }
            }
        }

        /** 加载完整稍后再看列表；该接口不提供分页。 */
        fun loadToView() {
            load(PersonalTopNavItem.ToView) { generation ->
                val data =
                    withTimeout(
                        LOAD_TIMEOUT_MS,
                    ) { toViewRepository.getToView(cursor = 0, preferApiType = prefApiType()) }
                if (!isCurrent(PersonalTopNavItem.ToView, generation)) return@load
                toViewItems.clear()
                toViewItems.addAll(data.data)
            }
        }

        /** 删除指定视频或全部已看视频，成功后原地更新当前账户列表。 */
        fun delToView(
            aid: Long,
            viewed: Boolean = false,
        ) {
            if (!_uiState.value.isLogin) return
            val generation = generations.getValue(PersonalTopNavItem.ToView)
            viewModelScope.launch {
                runCatching {
                    withTimeout(LOAD_TIMEOUT_MS) {
                        toViewRepository.delToView(aid = aid, viewed = viewed, preferApiType = prefApiType())
                    }
                    if (!isCurrent(PersonalTopNavItem.ToView, generation)) return@launch
                    toViewItems.removeAll { it.oid == aid }
                    _effect.emit(PersonalUiEffect.ShowToast("已移除稍后再看"))
                }.onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    if (!isCurrent(PersonalTopNavItem.ToView, generation)) return@onFailure
                    logger.error(error) { "Failed to delete toview $aid" }
                    _effect.emit(PersonalUiEffect.ShowToast("移除失败: ${error.message ?: "未知错误"}"))
                }
            }
        }

        /** 取消在途请求并刷新稍后再看；[preserveItems] 在自动刷新期间保留旧列表与滚动位置。 */
        fun refreshToView(preserveItems: Boolean = false) {
            invalidate(PersonalTopNavItem.ToView, preserveItems = preserveItems)
            loadToView()
        }

        /** 按 cursor 加载下一页历史；末页后跳过。 */
        fun loadHistory() {
            loadHistoryPage()
        }

        private fun loadHistoryPage(replaceCount: Int? = null) {
            load(PersonalTopNavItem.History, replaceCount != null || _uiState.value.historyHasMore) { generation ->
                val (items, cursor) =
                    withTimeout(LOAD_TIMEOUT_MS) {
                        var data =
                            historyRepository.getHistories(
                                cursor = if (replaceCount != null) 0L else historyCursor,
                                preferApiType = prefApiType(),
                            )
                        val items = data.data.toMutableList()
                        // 自动刷新补齐已浏览的页数，避免替换成第一页后把深处的焦点挤回顶部。
                        while (replaceCount != null &&
                            items.size < replaceCount &&
                            data.cursor != 0L &&
                            data.data.isNotEmpty()
                        ) {
                            val previousCursor = data.cursor
                            data =
                                historyRepository.getHistories(cursor = previousCursor, preferApiType = prefApiType())
                            items.addAll(data.data)
                            if (data.cursor == previousCursor) break
                        }
                        items to data.cursor
                    }
                if (!isCurrent(PersonalTopNavItem.History, generation)) return@load
                historyCursor = cursor
                _uiState.update {
                    it.copy(
                        historyItems = if (replaceCount != null) items else it.historyItems + items,
                        historyHasMore = cursor != 0L,
                    )
                }
            }
        }

        /** 取消在途请求并刷新历史；[preserveItems] 保留旧列表，刷新至原已加载的条数以保持纵向位置。 */
        fun refreshHistory(preserveItems: Boolean = false) {
            val replaceCount =
                if (preserveItems) {
                    _uiState.value.historyItems.size
                        .coerceAtLeast(1)
                } else {
                    null
                }
            invalidate(PersonalTopNavItem.History, preserveItems = preserveItems)
            loadHistoryPage(replaceCount)
        }

        /** 加载收藏夹目录，并在同一任务内加载首个收藏夹第一页。 */
        fun loadFavoriteFolders() {
            val uid = _uiState.value.currentUid
            load(PersonalTopNavItem.Favorite) { generation ->
                val folders =
                    withTimeout(LOAD_TIMEOUT_MS) {
                        favoriteRepository.getAllFavoriteFolderMetadataList(mid = uid, preferApiType = prefApiType())
                    }
                if (!isCurrent(PersonalTopNavItem.Favorite, generation)) return@load
                _uiState.update { it.copy(favoriteFolders = folders) }
                folders.firstOrNull()?.let { folder ->
                    favoritePageNumber = 1
                    _uiState.update { it.copy(currentFolderId = folder.id) }
                    loadFavoritePage(folder.id, generation)
                }
            }
        }

        /** 加载指定收藏夹下一页；切夹或强制刷新先取消原任务。 */
        fun loadFavoriteItems(
            folderId: Long,
            forceRefresh: Boolean = false,
        ) {
            if (forceRefresh || folderId != _uiState.value.currentFolderId) {
                invalidate(PersonalTopNavItem.Favorite, keepFolders = true)
                _uiState.update { it.copy(currentFolderId = folderId) }
            }
            load(PersonalTopNavItem.Favorite, _uiState.value.favoriteHasMore) { generation ->
                loadFavoritePage(folderId, generation)
            }
        }

        private suspend fun loadFavoritePage(
            folderId: Long,
            generation: Int,
        ) {
            val data: FavoriteFolderData =
                withTimeout(LOAD_TIMEOUT_MS) {
                    favoriteRepository.getFavoriteFolderData(
                        mediaId = folderId,
                        pageNumber = favoritePageNumber,
                        preferApiType = prefApiType(),
                    )
                }
            if (!isCurrent(PersonalTopNavItem.Favorite, generation)) return
            favoritePageNumber++
            val videos = data.medias.filter { it.type == dev.frost819.newbv.biliapi.entity.FavoriteItemType.Video }
            _uiState.update { it.copy(favoriteItems = it.favoriteItems + videos, favoriteHasMore = data.hasMore) }
        }

        /** 刷新收藏目录及首个收藏夹。 */
        fun refreshFavorite() {
            invalidate(PersonalTopNavItem.Favorite)
            loadFavoriteFolders()
        }

        /** 按当前类型和观看状态加载下一页追番。 */
        fun loadFollowingSeasons() {
            val current = _uiState.value
            load(PersonalTopNavItem.FollowingSeason, current.followingHasMore) { generation ->
                val data =
                    withTimeout(LOAD_TIMEOUT_MS) {
                        seasonRepository.getFollowingSeasons(
                            type = current.followingType,
                            status = current.followingStatus,
                            pageNumber = followingPageNumber,
                            preferApiType = prefApiType(),
                        )
                    }
                if (!isCurrent(PersonalTopNavItem.FollowingSeason, generation)) return@load
                followingPageNumber++
                _uiState.update {
                    val items = it.followingSeasons + data.list
                    it.copy(followingSeasons = items, followingHasMore = items.size < data.total)
                }
            }
        }

        /** 取消旧筛选请求，并加载新类型/观看状态的第一页。 */
        fun setFollowingFilter(
            type: FollowingSeasonType,
            status: FollowingSeasonStatus,
        ) {
            invalidate(PersonalTopNavItem.FollowingSeason)
            _uiState.update { it.copy(followingType = type, followingStatus = status) }
            loadFollowingSeasons()
        }

        /** 刷新当前筛选的追番第一页。 */
        fun refreshFollowingSeasons() {
            invalidate(PersonalTopNavItem.FollowingSeason)
            loadFollowingSeasons()
        }

        /** 刷新指定可见 [tab]；[preserveItems] 让历史/稍后再看自动刷新时保持列表与焦点。 */
        fun refresh(
            tab: PersonalTopNavItem,
            preserveItems: Boolean = false,
        ) {
            when (tab) {
                PersonalTopNavItem.ToView -> refreshToView(preserveItems)
                PersonalTopNavItem.History -> refreshHistory(preserveItems)
                PersonalTopNavItem.Favorite -> refreshFavorite()
                PersonalTopNavItem.FollowingSeason -> refreshFollowingSeasons()
            }
        }
    }
