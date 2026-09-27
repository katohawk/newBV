package dev.frost819.newbv.app.data

import dev.frost819.newbv.app.entity.player.VideoListItem
import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.entity.video.RelatedVideo
import dev.frost819.newbv.biliapi.entity.video.VideoDetail
import dev.frost819.newbv.biliapi.repositories.VideoDetailRepository
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.data.repository.PlaybackProgressRepository
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频共享状态（播放器与详情页同步）。
 *
 * 合并了交互状态（点赞/投币/收藏）与历史播放进度（cid/时间），
 * 避免多个独立 StateFlow 造成的分散读写。
 *
 * @property aid 视频 AV 号，用于校验状态归属。
 * @property liked 是否已点赞。
 * @property coined 是否已投币。
 * @property favorited 是否已收藏。
 * @property lastPlayedCid 最近播放的 CID。
 * @property lastPlayedTime 最近播放位置（秒），-1 表示已看完。
 */
data class VideoSharedState(
    val aid: Long,
    val liked: Boolean = false,
    val coined: Boolean = false,
    val favorited: Boolean = false,
    val lastPlayedCid: Long = 0L,
    val lastPlayedTime: Int = 0,
)

/**
 * 番剧分集的片头片尾时间（秒），用于播放器自动跳过 OP/ED。
 *
 * @property introStartSec 片头开始时间，开始之前不跳过
 * @property introEndSec 片头结束时间，0 表示无片头数据
 * @property outroStartSec 片尾开始时间，0 表示无片尾数据
 * @property outroEndSec 片尾结束时间，0 表示未知
 */
data class SkipTimeInfo(
    val introEndSec: Int = 0,
    val introStartSec: Int = 0,
    val outroStartSec: Int = 0,
    val outroEndSec: Int = 0,
) {
    /** 仅跳过真实 OP 区间，不跳过 OP 前的正片；未知总时长时等待播放器就绪。 */
    fun containsIntro(
        positionMs: Long,
        durationMs: Long,
    ): Boolean = validRange(introStartSec, introEndSec, positionMs, durationMs)

    /** 仅跳过完整有效的 ED 区间，结束后的彩蛋不触发跳过。 */
    fun containsOutro(
        positionMs: Long,
        durationMs: Long,
    ): Boolean = validRange(outroStartSec, outroEndSec, positionMs, durationMs)

    private fun validRange(
        start: Int,
        end: Int,
        position: Long,
        duration: Long,
    ): Boolean =
        start >= 0 &&
            end > start &&
            duration > 0 &&
            end * 1000L <= duration &&
            position >= start * 1000L &&
            position < end * 1000L
}

/**
 * 应用级视频信息共享仓库。
 *
 * 在详情页加载视频详情后，播放器页面通过本仓库获取已缓存的视频列表和详情，
 * 避免重复请求。若播放器直接打开（如从外部入口），则由 [PlayerViewModel] 自行加载。
 *
 * 生命周期：Hilt `@Singleton`，随应用进程存活。
 */
@Singleton
class VideoInfoRepository
    @Inject
    constructor(
        private val videoDetailRepository: VideoDetailRepository,
        private val playbackProgressRepository: PlaybackProgressRepository,
    ) {
        private val logger = Loggers.get("VideoInfoRepository")
        private var detailGeneration = 0L
        private var historyOwner: Pair<Long, Long>? = null

        private val _videoList = MutableStateFlow<List<VideoListItem>>(emptyList())
        val videoList = _videoList.asStateFlow()

        private val _videoDetail = MutableStateFlow<VideoDetail?>(null)
        val videoDetail = _videoDetail.asStateFlow()

        private val _relatedVideos = MutableStateFlow<List<RelatedVideo>>(emptyList())
        val relatedVideos = _relatedVideos.asStateFlow()

        private val _videoSharedState = MutableStateFlow<VideoSharedState?>(null)

        /** 当前视频的共享状态（交互 + 历史）。 */
        val videoSharedState = _videoSharedState.asStateFlow()

        private val _skipTimes = MutableStateFlow<Map<Long, SkipTimeInfo>>(emptyMap())

        /** 番剧分集片头片尾时间（cid -> 时间），由播放器入口加载，播放器按 cid 查询。 */
        val skipTimes = _skipTimes.asStateFlow()

        /**
         * 更新视频详情（同步相关视频和共享状态）。
         *
         * 供详情页 ViewModel 在加载完成后调用，确保播放器页面能获取相关视频数据。
         *
         * @param detail 视频详情
         */
        fun updateVideoDetail(detail: VideoDetail) {
            _videoDetail.update { detail }
            _relatedVideos.update { detail.relatedVideos }
            _videoSharedState.update { old ->
                val local = old?.takeIf { it.aid == detail.aid && historyOwner == (Prefs.uid to detail.aid) }
                VideoSharedState(
                    aid = detail.aid,
                    liked = detail.userActions.like,
                    coined = detail.userActions.coin,
                    favorited = detail.userActions.favorite,
                    lastPlayedCid = local?.lastPlayedCid ?: detail.history.lastPlayedCid,
                    lastPlayedTime = local?.lastPlayedTime ?: detail.history.progress,
                )
            }
        }

        /**
         * 加载视频详情并更新共享状态。
         *
         * @param aid 视频 AV 号
         * @param preferApiType 接口类型
         */
        suspend fun loadVideoDetail(
            aid: Long,
            preferApiType: ApiType,
            bvid: String = "",
        ) {
            val generation = ++detailGeneration
            runCatching {
                val detail = videoDetailRepository.getVideoDetail(aid = aid, preferApiType = preferApiType, bvid = bvid)
                val local = playbackProgressRepository.get(Prefs.uid, aid)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (generation != detailGeneration) return
                updateVideoDetail(detail)
                if (local != null) updateHistory(local.position, local.cid)
                logger.info { "Loaded video detail: aid=$aid, related=${detail.relatedVideos.size}" }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.error(e) { "Failed to load video detail: aid=$aid" }
            }
        }

        /** 用本机记录刷新详情续播入口，不让服务端旧记录覆盖本机进度。 */
        suspend fun restoreLocalHistory(aid: Long) {
            val local = playbackProgressRepository.get(Prefs.uid, aid) ?: return
            if (_videoSharedState.value?.aid == aid) updateHistory(local.position, local.cid)
        }

        /**
         * 获取视频同时观看人数文案。
         *
         * 按 [preferApiType] 选择 Web/App 接口。
         * 任何失败（网络错误、接口错误、UP 主关闭展示开关）均返回 null，
         * 调用方应保持现有文案不变，绝不影响播放。
         *
         * @param aid 视频 AV 号
         * @param cid 分 P CID
         * @param preferApiType 接口类型
         * @return 可展示的人数文本；不展示或失败时为 null
         */
        suspend fun getOnlineWatchingText(
            aid: Long,
            cid: Long,
            preferApiType: ApiType,
        ): String? =
            runCatching {
                videoDetailRepository.getOnlineTotalText(
                    aid = aid,
                    cid = cid,
                    preferApiType = preferApiType,
                )
            }.onFailure { e ->
                logger.error(e) { "Failed to get online total: aid=$aid, cid=$cid" }
            }.getOrNull()

        /**
         * 更新视频列表。
         *
         * @param items 新的视频列表
         */
        fun updateVideoList(items: List<VideoListItem>) {
            _videoList.update { items }
        }

        /**
         * 为列表中的每个视频加载 UGC 分 P 信息。
         *
         * 仅对普通视频生效；番剧以完整的 aid/cid/epid 分集标识播放。
         *
         * @param preferApiType 接口类型
         */
        suspend fun updateUgcPages(preferApiType: ApiType) {
            _videoList.update { oldList ->
                oldList.map { item ->
                    // 部分番剧的 UGC 接口返回整季 CID，套在单个 EP 下会导致选集时 AV/EP 与 CID 错配。
                    if ((item.epid ?: 0) > 0 || (item.seasonId ?: 0) > 0) {
                        return@map item.copy(ugcPages = null)
                    }
                    runCatching {
                        val pages = videoDetailRepository.getUgcPages(aid = item.aid, preferApiType = preferApiType)
                        if (pages.size > 1) item.copy(ugcPages = pages) else item
                    }.getOrElse { item }
                }
            }
        }

        /**
         * 更新播放历史（仅历史字段，不影响交互状态）。
         *
         * @param progress 播放进度（秒），-1 表示已看完
         * @param aid 当前播放视频，切集时显式传入以避免继承上一集的交互状态
         * @param lastPlayedCid 最近播放的 CID
         */
        fun updateHistory(
            progress: Int,
            lastPlayedCid: Long,
            aid: Long? = null,
        ) {
            val targetAid = aid ?: _videoSharedState.value?.aid ?: 0L
            historyOwner = Prefs.uid to targetAid
            _videoSharedState.update { old ->
                old?.takeIf { it.aid == targetAid }?.copy(lastPlayedCid = lastPlayedCid, lastPlayedTime = progress)
                    ?: VideoSharedState(aid = targetAid, lastPlayedCid = lastPlayedCid, lastPlayedTime = progress)
            }
        }

        /**
         * 更新视频交互状态（仅交互字段，不影响历史进度），并通知详情页与播放器。
         *
         * @param aid 视频 AV 号
         * @param liked 是否点赞
         * @param coined 是否投币
         * @param favorited 是否收藏
         */
        fun updateVideoActionState(
            aid: Long,
            liked: Boolean? = null,
            coined: Boolean? = null,
            favorited: Boolean? = null,
        ) {
            _videoSharedState.update { old ->
                val current = old?.takeIf { it.aid == aid }
                VideoSharedState(
                    aid = aid,
                    liked = liked ?: current?.liked ?: false,
                    coined = coined ?: current?.coined ?: false,
                    favorited = favorited ?: current?.favorited ?: false,
                    lastPlayedCid = current?.lastPlayedCid ?: 0L,
                    lastPlayedTime = current?.lastPlayedTime ?: 0,
                )
            }
        }

        /**
         * 更新番剧分集片头片尾时间。
         *
         * @param times cid -> 片头片尾时间
         */
        fun updateSkipTimes(times: Map<Long, SkipTimeInfo>) {
            _skipTimes.update { times }
        }

        /** 兼容只携带 epid 的旧播放入口，补齐番剧身份和分集列表。 */
        suspend fun loadSeasonContext(
            epid: Int?,
            apiType: ApiType,
        ): dev.frost819.newbv.biliapi.entity.video.season.SeasonDetail {
            val detail =
                kotlinx.coroutines.withTimeout(10_000L) {
                    videoDetailRepository.getPgcVideoDetail(epid = epid, preferApiType = apiType)
                }
            updateVideoList(
                (detail.episodes + detail.sections.flatMap { it.episodes }).map {
                    VideoListItem(
                        aid = it.aid,
                        cid = it.cid,
                        epid = it.epid ?: it.id,
                        seasonId = detail.seasonId,
                        title = it.title,
                    )
                },
            )
            return detail
        }

        /** 从播放入口加载标记，调用方负责切集时取消旧请求；缺失标记不影响播放。 */
        suspend fun loadSkipTimes(
            epid: Int?,
            seasonId: Int,
        ) {
            val times =
                kotlinx.coroutines
                    .withTimeoutOrNull(10_000L) {
                        videoDetailRepository.getPgcSkipTimes(epid, seasonId.takeIf { it > 0 })
                    }.orEmpty()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            updateSkipTimes(
                times.mapValues { (_, skip) ->
                    SkipTimeInfo(skip.introEnd, skip.introStart, skip.outroStart, skip.outroEnd)
                },
            )
        }

        /** 重置所有状态。 */
        fun reset() {
            detailGeneration++
            historyOwner = null
            _videoList.update { emptyList() }
            _videoDetail.update { null }
            _relatedVideos.update { emptyList() }
            _videoSharedState.update { null }
            _skipTimes.update { emptyMap() }
        }
    }
