package dev.frost819.newbv.app.viewmodel.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.app.data.VideoInfoRepository
import dev.frost819.newbv.app.entity.player.VideoListItem
import dev.frost819.newbv.biliapi.entity.video.RelatedVideo
import dev.frost819.newbv.biliapi.entity.video.season.PgcSeason
import dev.frost819.newbv.biliapi.repositories.SeasonRepository
import dev.frost819.newbv.core.log.Loggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

private const val RELATED_LOAD_TIMEOUT_MS = 10_000L

/**
 * 视频列表 ViewModel。
 *
 * 管理分集列表和相关视频，提供上下集查找功能。
 * 观察 [VideoInfoRepository] 的共享状态，自动同步更新。
 *
 * @see VideoListState
 */
@HiltViewModel
class VideoListViewModel
    @Inject
    constructor(
        private val videoInfoRepository: VideoInfoRepository,
        private val seasonRepository: SeasonRepository,
    ) : ViewModel() {
        private val _videoListState = MutableStateFlow(VideoListState())
        val videoListState = _videoListState.asStateFlow()

        private val currentVideo = MutableStateFlow(0L to 0)
        private var relatedJob: Job? = null
        private val logger = Loggers.get("VideoListViewModel")

        /**
         * 更新当前视频与季度标识，普通视频的 [seasonId] 为 0。
         * 同季切集复用推荐；换季立即清空并取消旧请求，避免串入上一季的数据。
         */
        fun setCurrentVideo(
            aid: Long,
            seasonId: Int,
        ) {
            val previousSeasonId = currentVideo.value.second
            currentVideo.value = aid to seasonId
            if (previousSeasonId != seasonId) {
                relatedJob?.cancel()
                _videoListState.update {
                    it.copy(
                        relatedVideos = emptyList(),
                        relatedSeasons = emptyList(),
                        relatedLoading = false,
                        relatedError = false,
                    )
                }
                if (seasonId > 0) {
                    loadRelatedSeasons()
                }
            }
        }

        /** 重试当前剧集的推荐请求，不影响正在播放的视频。 */
        fun loadRelatedSeasons() {
            val seasonId = currentVideo.value.second
            if (seasonId <= 0) return
            relatedJob?.cancel()
            _videoListState.update { it.copy(relatedLoading = true, relatedError = false) }
            relatedJob =
                viewModelScope.launch {
                    runCatching {
                        val seasons =
                            withTimeout(RELATED_LOAD_TIMEOUT_MS) { seasonRepository.getRelatedSeasons(seasonId) }
                        currentCoroutineContext().ensureActive()
                        seasons
                    }.onSuccess { seasons ->
                        if (currentVideo.value.second == seasonId) {
                            _videoListState.update { it.copy(relatedSeasons = seasons, relatedLoading = false) }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        currentCoroutineContext().ensureActive()
                        logger.warn(error) { "Failed to load related seasons" }
                        if (currentVideo.value.second == seasonId) {
                            _videoListState.update { it.copy(relatedLoading = false, relatedError = true) }
                        }
                    }
                }
        }

        init {
            viewModelScope.launch {
                videoInfoRepository.videoList
                    .collect { list ->
                        if (list.isEmpty()) return@collect
                        _videoListState.update { it.copy(videoList = list) }
                    }
            }
            viewModelScope.launch {
                // 直接取详情内的 relatedVideos：详情（含相关视频）由 repository 整体原子更新，
                // 按 aid 校验归属可避免上一个视频的列表串入当前视频（快速切视频时不显示旧数据）
                combine(currentVideo, videoInfoRepository.videoDetail) { (aid, seasonId), detail ->
                    if (seasonId == 0 && aid != 0L && detail?.aid == aid) detail.relatedVideos else emptyList()
                }.distinctUntilChanged()
                    .collect { list ->
                        _videoListState.update { it.copy(relatedVideos = list) }
                    }
            }
        }

        /**
         * 查找下一个播放目标。
         *
         * @param currentAid 当前视频 AV 号
         * @param currentCid 当前视频 CID
         * @return 下一个播放项，null 表示没有下一集
         */
        fun findNextVideo(
            currentAid: Long,
            currentCid: Long,
        ): VideoListItem? {
            val list = videoInfoRepository.videoList.value
            val index = list.indexOfFirst { it.aid == currentAid }
            if (index == -1) return null

            val current = list.getOrNull(index) ?: return null

            // 先查找当前视频的分 P
            if (current.ugcPages?.isNotEmpty() == true) {
                val innerIndex = current.ugcPages.indexOfFirst { it.cid == currentCid }
                if (innerIndex != -1 && innerIndex + 1 < current.ugcPages.size) {
                    return VideoListItem(
                        aid = current.aid,
                        cid = current.ugcPages[innerIndex + 1].cid,
                        title = current.ugcPages[innerIndex + 1].title,
                    )
                }
            }

            // 查找列表中的下一个视频
            if (index + 1 < list.size) return list[index + 1]
            return null
        }

        /**
         * 查找上一个播放目标。
         *
         * @param currentAid 当前视频 AV 号
         * @param currentCid 当前视频 CID
         * @return 上一个播放项，null 表示没有上一集
         */
        fun findPreviousVideo(
            currentAid: Long,
            currentCid: Long,
        ): VideoListItem? {
            val list = videoInfoRepository.videoList.value
            val index = list.indexOfFirst { it.aid == currentAid }
            if (index == -1) return null

            val current = list.getOrNull(index) ?: return null

            // 先查找当前视频的分 P
            if (current.ugcPages?.isNotEmpty() == true) {
                val innerIndex = current.ugcPages.indexOfFirst { it.cid == currentCid }
                if (innerIndex > 0) {
                    return VideoListItem(
                        aid = current.aid,
                        cid = current.ugcPages[innerIndex - 1].cid,
                        title = current.ugcPages[innerIndex - 1].title,
                    )
                }
            }

            // 查找列表中的上一个视频
            if (index > 0) {
                val prev = list[index - 1]
                val prevLastPage = prev.ugcPages?.lastOrNull()
                return if (prevLastPage != null) {
                    VideoListItem(aid = prev.aid, cid = prevLastPage.cid, title = prevLastPage.title)
                } else {
                    prev
                }
            }
            return null
        }
    }

/**
 * 视频列表状态。
 * @property relatedSeasons 当前季度的相关推荐。
 * @property relatedLoading 剧集推荐请求是否正在进行。
 * @property relatedError 剧集推荐是否失败，可重试。
 */
data class VideoListState(
    val videoList: List<VideoListItem> = emptyList(),
    val relatedVideos: List<RelatedVideo> = emptyList(),
    val relatedSeasons: List<PgcSeason> = emptyList(),
    val relatedLoading: Boolean = false,
    val relatedError: Boolean = false,
)
