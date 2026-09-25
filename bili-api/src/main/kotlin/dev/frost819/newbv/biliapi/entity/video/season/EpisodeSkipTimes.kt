package dev.frost819.newbv.biliapi.entity.video.season

import kotlinx.serialization.Serializable

/**
 * 番剧分集的片头片尾跳过时间（秒）。
 *
 * 数据来自 Web 剧集详情接口（`/pgc/view/web/season`）的 `skip` 字段；
 * App gRPC 接口不返回该数据。缺少某项时对应值为 0，表示不可跳过。
 *
 * @property introEnd 片头（OP）结束时间，0 表示无片头数据
 * @property outroStart 片尾（ED）开始时间，0 表示无片尾数据
 * @property outroEnd 片尾（ED）结束时间，0 表示未知
 */
@Serializable
data class EpisodeSkipTimes(
    val introEnd: Int = 0,
    val outroStart: Int = 0,
    val outroEnd: Int = 0,
)
