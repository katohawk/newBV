package dev.frost819.newbv.biliapi.http.entity.season

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 剧集相关推荐响应；只消费可播放的 season，忽略漫画等 relates 内容。 */
@Serializable
data class RelatedSeasons(
    val season: List<RelatedSeason>,
)

/** 剧集推荐卡片，使用 season_id 导航，不将剧集 ID 冒充视频 aid 或分集 epid。 */
@Serializable
data class RelatedSeason(
    @SerialName("season_id") val seasonId: Int,
    val title: String,
    val cover: String,
    @SerialName("new_ep") val newEp: NewEpisode? = null,
) {
    /** 推荐接口可选的最新分集封面及更新说明。 */
    @Serializable
    data class NewEpisode(
        val cover: String = "",
        @SerialName("index_show") val indexShow: String = "",
    )
}
