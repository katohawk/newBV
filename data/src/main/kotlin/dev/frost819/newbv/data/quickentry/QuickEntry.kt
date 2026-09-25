package dev.frost819.newbv.data.quickentry

import dev.frost819.newbv.biliapi.repositories.SearchType
import kotlinx.serialization.Serializable

/** 收藏入口类型：视频 / 番剧（剧集列表入口）/ 搜索条件 / UP 主个人页。 */
object QuickEntryType {
    const val VIDEO = "Video"
    const val SEASON = "Season"
    const val SEARCH = "Search"
    const val UP = "Up"
}

/**
 * 首页快捷收藏（导航入口书签）。
 *
 * 只保存"重新打开该入口"所需的最小信息，不是页面数据缓存。
 * 字段名持久化（不使用枚举序号），未知字段在反序列化时忽略，
 * 保证 App 升级或后续新增入口类型时的向前兼容。
 */
@Serializable
data class QuickEntry(
    val type: String,
    val title: String,
    val cover: String = "",
    val aid: Long = 0,
    val seasonId: Long = 0,
    val mid: Long = 0,
    val keyword: String = "",
    val searchType: String = SearchType.Video.name,
) {
    /** 稳定去重键：视频按 aid，番剧按 seasonId，UP 主按 mid，搜索按 keyword + 类型。 */
    val key: String
        get() =
            when (type) {
                QuickEntryType.VIDEO -> "video:$aid"
                QuickEntryType.SEASON -> "season:$seasonId"
                QuickEntryType.UP -> "up:$mid"
                else -> "search:$searchType:$keyword"
            }

    val isValid: Boolean
        get() =
            when (type) {
                QuickEntryType.VIDEO -> aid > 0
                QuickEntryType.SEASON -> seasonId > 0
                QuickEntryType.UP -> mid > 0
                QuickEntryType.SEARCH -> keyword.isNotBlank() && SearchType.entries.any { it.name == searchType }
                else -> false
            }

    companion object {
        /** 从路由参数解析初始搜索类型，未知/为空时回退 Video。 */
        fun initialSearchType(name: String?): SearchType =
            SearchType.entries.firstOrNull { it.name == name } ?: SearchType.Video
    }
}

/**
 * 首页推荐流中的单项：要么是收藏卡片，要么指向原始推荐列表的下标。
 *
 * 保留原始下标是为了让收藏只影响第一批内容的展示顺序，
 * 不改变推荐列表本身与分页触发逻辑。
 */
data class QuickFeedItem(
    val favorite: QuickEntry? = null,
    val recommendationIndex: Int = -1,
)

/**
 * 将收藏合并进首页第一批推荐内容之前。
 *
 * - 收藏按"最近收藏在前"排序（由仓库保证）；
 * - 与第一批推荐做基础去重（视频 aid / 番剧 seasonId）；
 * - 超出第一批的推荐不受影响，后续分页逻辑不变。
 */
fun mergeQuickEntries(
    favorites: List<QuickEntry>,
    recommendationKeys: List<String>,
    firstBatchSize: Int,
): List<QuickFeedItem> {
    val uniqueFavorites = favorites.filter { it.isValid }.distinctBy { it.key }
    val keys = uniqueFavorites.map { it.key }.toSet()
    return uniqueFavorites.map { QuickFeedItem(favorite = it) } +
        recommendationKeys.mapIndexedNotNull { index, key ->
            if (index < firstBatchSize && key in keys) null else QuickFeedItem(recommendationIndex = index)
        }
}
