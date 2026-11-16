package dev.frost819.newbv.biliapi.repositories

import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.entity.season.FollowingSeason
import dev.frost819.newbv.biliapi.entity.season.FollowingSeasonData
import dev.frost819.newbv.biliapi.entity.season.FollowingSeasonStatus
import dev.frost819.newbv.biliapi.entity.season.FollowingSeasonType
import dev.frost819.newbv.biliapi.entity.season.Timeline
import dev.frost819.newbv.biliapi.entity.season.TimelineFilter
import dev.frost819.newbv.biliapi.entity.video.season.PgcSeason
import dev.frost819.newbv.biliapi.http.BiliHttpApi
import dev.frost819.newbv.biliapi.http.util.BiliAppConf

class SeasonRepository(
    private val authRepository: AuthRepository,
) {
    /**
     * 获取当前剧集的相关推荐，Web/App 播放均使用此 Web-only 接口。
     * @param seasonId 当前季度 ID。
     * @return 按服务端顺序返回的其他剧集，移除无效 ID 与重复项。
     * @throws RuntimeException 请求、业务码或响应解析失败时交由调用方展示重试。
     */
    suspend fun getRelatedSeasons(seasonId: Int): List<PgcSeason> =
        BiliHttpApi
            .getRelatedSeasons(seasonId)
            .getResponseData()
            .season
            .filter { it.seasonId > 0 && it.seasonId != seasonId }
            .distinctBy { it.seasonId }
            .map {
                PgcSeason(
                    seasonId = it.seasonId,
                    title = it.title,
                    shortTitle = it.newEp?.indexShow.orEmpty(),
                    cover = it.cover,
                    horizontalCover = it.newEp?.cover?.takeIf(String::isNotBlank),
                )
            }

    /**
     * 获取追番/追剧列表
     *
     * @param type 追番/追剧类型
     * @param status 追剧状态
     * @param pageNumber 页码
     * @param pageSize 每页数量
     */
    suspend fun getFollowingSeasons(
        type: FollowingSeasonType = FollowingSeasonType.Bangumi,
        status: FollowingSeasonStatus = FollowingSeasonStatus.All,
        pageNumber: Int = 1,
        pageSize: Int = 30,
        preferApiType: ApiType,
    ): FollowingSeasonData =
        when (preferApiType) {
            ApiType.Web -> {
                BiliHttpApi
                    .getFollowingSeasons(
                        type = type.id,
                        status = status.id,
                        pageNumber = pageNumber,
                        pageSize = pageSize,
                        mid = authRepository.mid!!,
                    ).getResponseData()
                    .let { responseData ->
                        FollowingSeasonData(
                            list = responseData.list.map { FollowingSeason.fromFollowingSeason(it) },
                            total = responseData.total,
                        )
                    }
            }

            ApiType.App -> {
                BiliHttpApi
                    .getFollowingSeasons(
                        type = type.paramName,
                        status = status.id,
                        pageNumber = pageNumber,
                        pageSize = pageSize,
                        build = BiliAppConf.APP_BUILD_CODE,
                        accessKey = authRepository.accessToken ?: "",
                    ).getResponseData()
                    .let { responseData ->
                        FollowingSeasonData(
                            list = responseData.followList.map { FollowingSeason.fromFollowingSeason(it) },
                            total = responseData.total,
                        )
                    }
            }
        }

    suspend fun getTimeline(
        filter: TimelineFilter = TimelineFilter.All,
        preferApiType: ApiType,
    ): List<Timeline> =
        when (preferApiType) {
            ApiType.Web -> {
                BiliHttpApi
                    .getTimeline(
                        type = filter.webFilterId,
                        before = 7,
                        after = 7,
                    ).getResponseData()
                    .map { Timeline.fromTimeline(it) }
            }

            ApiType.App -> {
                BiliHttpApi
                    .getTimeline(
                        filterType = filter.appFilterId,
                    ).getResponseData()
                    .data
                    .map { Timeline.fromTimeline(it) }
            }
        }
}
