package dev.frost819.newbv.app.viewmodel.quickentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.app.data.VideoInfoRepository
import dev.frost819.newbv.app.entity.player.VideoListItem
import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.entity.video.season.SeasonDetail
import dev.frost819.newbv.biliapi.repositories.VideoDetailRepository
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.ApiType as DataApiType
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.data.quickentry.QuickEntry
import dev.frost819.newbv.data.quickentry.QuickEntryRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val LOAD_TIMEOUT_MS = 15_000L

/** 快捷收藏一次性 UI 事件。 */
sealed interface QuickEntryUiEffect {
    /** 跳转播放器直接播放番剧分集。 */
    data class PlayEpisode(
        val aid: Long,
        val cid: Long,
        val title: String,
        val cover: String,
        val epid: Int?,
    ) : QuickEntryUiEffect

    /** 降级：跳转番剧详情页（解析续播集失败时）。 */
    data class NavigateToSeasonDetail(
        val seasonId: Long,
    ) : QuickEntryUiEffect
}

/**
 * 首页快捷收藏 ViewModel。
 *
 * 暴露已收藏入口的 key 集合供按钮判断状态，并代理收藏/取消操作；
 * 番剧收藏支持解析续播分集后直接进入播放器。
 *
 * @param quickEntryRepository 快捷收藏仓库。
 * @param videoDetailRepository 视频详情仓库（解析番剧续播分集）。
 * @param videoInfoRepository 视频共享状态仓库（填充播放器内选集列表）。
 */
@HiltViewModel
class QuickEntryViewModel
    @Inject
    constructor(
        private val quickEntryRepository: QuickEntryRepository,
        private val videoDetailRepository: VideoDetailRepository,
        private val videoInfoRepository: VideoInfoRepository,
    ) : ViewModel() {
        private val logger = Loggers.get("QuickEntryViewModel")

        /** 已收藏入口的 key 集合。 */
        val savedKeys: StateFlow<Set<String>> =
            quickEntryRepository.entries
                .map { entries -> entries.map { it.key }.toSet() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

        private val _uiEffect = MutableSharedFlow<QuickEntryUiEffect>()
        val uiEffect: SharedFlow<QuickEntryUiEffect> = _uiEffect.asSharedFlow()

        /** 收藏（saved = true）或取消收藏（saved = false）。 */
        fun setSaved(
            entry: QuickEntry,
            saved: Boolean,
        ) {
            viewModelScope.launch { quickEntryRepository.setSaved(entry, saved) }
        }

        /**
         * 从首页收藏卡片直接进入番剧播放。
         *
         * 续播分集解析优先级与详情页一致：服务端观看记录 → 第一集。
         * 同时把整季分集写入播放列表，保证播放器内可以继续切集。
         * 任何一步失败都降级为跳转番剧详情页（[QuickEntryUiEffect.NavigateToSeasonDetail]）。
         *
         * @param entry 番剧收藏入口（需含有效 seasonId）。
         */
        fun playSeason(entry: QuickEntry) {
            val seasonId = entry.seasonId.takeIf { it > 0 } ?: return
            viewModelScope.launch {
                val detail =
                    runCatching {
                        withTimeout(LOAD_TIMEOUT_MS) {
                            videoDetailRepository.getPgcVideoDetail(
                                seasonId = seasonId.toInt(),
                                preferApiType = getApiType(),
                            )
                        }
                    }.onFailure { error ->
                        // 协程取消必须透传；其余失败降级为跳详情页
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        logger.error(error) { "Failed to resolve season for quick play: $seasonId" }
                    }.getOrNull()

                val episode =
                    detail?.let { resolveResumeEpisode(it) }
                        ?: run {
                            _uiEffect.emit(QuickEntryUiEffect.NavigateToSeasonDetail(seasonId))
                            return@launch
                        }

                // 与详情页跳转播放器一致：先填充整季播放列表，播放器内选集/下一集才可用
                videoInfoRepository.updateVideoList(
                    (detail.episodes + detail.sections.flatMap { it.episodes }).map { ep ->
                        VideoListItem(
                            aid = ep.aid,
                            cid = ep.cid,
                            epid = ep.epid,
                            seasonId = detail.seasonId,
                            title = ep.title,
                        )
                    },
                )

                _uiEffect.emit(
                    QuickEntryUiEffect.PlayEpisode(
                        aid = episode.aid,
                        cid = episode.cid,
                        title = episode.title,
                        cover = episode.cover,
                        epid = episode.epid,
                    ),
                )
            }
        }

        /**
         * 续播分集：优先服务端观看记录的集，其次第一集。
         *
         * 注意 App gRPC 模式下分集 [Episode.epid] 为 null（只有 id，值即 epid），
         * 匹配时须回退到 id，否则观看记录永远匹配不上、只会从第一集开播。
         */
        private fun resolveResumeEpisode(detail: SeasonDetail) =
            detail.userStatus.progress?.let { progress ->
                val lastEpId = progress.lastEpId
                (detail.episodes + detail.sections.flatMap { it.episodes })
                    .firstOrNull { (it.epid ?: it.id) == lastEpId }
            } ?: detail.episodes.firstOrNull()

        /** 将 DataApiType 映射为 bili-api 的 ApiType。 */
        private fun getApiType(): ApiType = if (Prefs.apiType == DataApiType.App) ApiType.App else ApiType.Web
    }
