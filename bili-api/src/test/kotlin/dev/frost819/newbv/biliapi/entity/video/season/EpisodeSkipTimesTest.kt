package dev.frost819.newbv.biliapi.entity.video.season

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.http.entity.season.Episode
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** 验证服务端片头片尾标记字段与缺失区间的兼容行为。 */
class EpisodeSkipTimesTest {
    @Test
    fun `op start is preserved and missing ed remains unavailable`() {
        val skip = Json.decodeFromString<Episode.Skip>("""{"op":{"start":30,"end":90}}""")
        assertThat(skip.op.start).isEqualTo(30)
        assertThat(skip.op.end).isEqualTo(90)
        assertThat(skip.ed.end).isEqualTo(0)
    }

    @Test
    fun `missing op and unknown ed end do not invent durations`() {
        val skip = Json.decodeFromString<Episode.Skip>("""{"ed":{"start":1000}}""")
        assertThat(skip.op.end).isEqualTo(0)
        assertThat(skip.ed.start).isEqualTo(1000)
        assertThat(skip.ed.end).isEqualTo(0)
    }
}
