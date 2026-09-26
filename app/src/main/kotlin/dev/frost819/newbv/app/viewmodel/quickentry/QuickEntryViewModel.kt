package dev.frost819.newbv.app.viewmodel.quickentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.app.data.VideoInfoRepository
import dev.frost819.newbv.app.entity.player.VideoListItem
import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.entity.user.HistoryItemType
import dev.frost819.newbv.biliapi.entity.video.season.Episode
import dev.frost819.newbv.biliapi.entity.video.season.SeasonDetail
import dev.frost819.newbv.biliapi.repositories.HistoryRepository
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

    /** 降级：跳转番剧详情页（无法确定续播分集时）。 */
    data class NavigateToSeasonDetail(
        val seasonId: Long,
    ) : QuickEntryUiEffect
}

/** 续播分集解析结果。 */
private sealed interface ResumeResolution {
    /** 命中观看记录，直接播放该集。 */
    data class Matched(
        val episode: Episode,
    ) : ResumeResolution

    /** 确认从未看过（记录与历史都为空），从第一集开播。 */
    data object NeverWatched : ResumeResolution

    /** 无法判断（记录缺失且历史拉取失败），降级详情页。 */
    data object Unknown : ResumeResolution
}

/**
 * 首页快捷收藏 ViewModel。
 *
 * 暴露已收藏入口的 key 集合供按钮判断状态，并代理收藏/取消操作；
 * 番剧收藏支持解析续播分集后直接进入播放器。
 *
 * @param quickEntryRepository 快捷收藏仓库。
 * @param videoDetailRepository 视频详情仓库（解析番剧详情）。
 * @param historyRepository 历史记录仓库（续播分集兜底解析）。
 * @param videoInfoRepository 视频共享状态仓库（填充播放器内选集列表）。
 */
@HiltViewModel
class QuickEntryViewModel
    @Inject
    constructor(
        private val quickEntryRepository: QuickEntryRepository,
        private val videoDetailRepository: VideoDetailRepository,
        private val historyRepository: HistoryRepository,
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
         * 续播分集解析优先级：番剧详情的服务端观看记录 → 最近观看历史（按分集 aid 匹配）
         * → 从未看过则第一集；无法判断时降级跳转番剧详情页。
         * 同时把整季分集写入播放列表，保证播放器内可以继续切集。
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
                        if (error is CancellationException) throw error
                        logger.error(error) { "Failed to resolve season for quick play: $seasonId" }
                    }.getOrNull()

                if (detail == null) {
                    _uiEffect.emit(QuickEntryUiEffect.NavigateToSeasonDetail(seasonId))
                    return@launch
                }

                val episode =
                    when (val resolution = resolveResumeEpisode(detail)) {
                        is ResumeResolution.Matched -> resolution.episode
                        is ResumeResolution.NeverWatched -> detail.episodes.firstOrNull()
                        is ResumeResolution.Unknown -> null
                    }

                if (episode == null) {
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
         * 解析续播分集。
         *
         * 1. 番剧详情自带的观看记录（lastEpId）：App gRPC 分集映射的 epid 可能为
         *    null（id 即 epid），匹配时回退到 id；
         * 2. 兜底：最近观看历史中该番剧下最近看过的一集（按分集 aid 匹配，
         *    App 历史接口的 OGV 卡片不含 epid/cid，但 oid 即分集 aid）。
         */
        private suspend fun resolveResumeEpisode(detail: SeasonDetail): ResumeResolution {
            val allEps = detail.episodes + detail.sections.flatMap { it.episodes }
            if (allEps.isEmpty()) return ResumeResolution.Unknown

            val progress = detail.userStatus.progress
            if (progress != null) {
                allEps.firstOrNull { (it.epid ?: it.id) == progress.lastEpId }?.let {
                    return ResumeResolution.Matched(it)
                }
            }

            // 观看记录缺失或不匹配，拉最近的历史记录兜底
            val history =
                runCatching {
                    withTimeout(LOAD_TIMEOUT_MS) {
                        historyRepository.getHistories(cursor = 0, preferApiType = getApiType())
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    logger.error(error) { "Failed to fetch history for resume: ${detail.seasonId}" }
                }.getOrNull() ?: return ResumeResolution.Unknown

            val aidToEpisode = allEps.associateBy { it.aid }
            val matched =
                history.data
                    .filter { it.type == HistoryItemType.Pgc }
                    .firstOrNull { it.oid in aidToEpisode }
                    ?: return ResumeResolution.NeverWatched
            return ResumeResolution.Matched(aidToEpisode.getValue(matched.oid))
        }

        /** 将 DataApiType 映射为 bili-api 的 ApiType。 */
        private fun getApiType(): ApiType = if (Prefs.apiType == DataApiType.App) ApiType.App else ApiType.Web
    }
