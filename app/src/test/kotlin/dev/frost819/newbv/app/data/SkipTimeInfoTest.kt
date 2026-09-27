package dev.frost819.newbv.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** 验证 OP 前正片、ED 后彩蛋及异常标记边界。 */
class SkipTimeInfoTest {
    @Test
    fun `only actual intro interval is skipped`() {
        val skip = SkipTimeInfo(introStartSec = 30, introEndSec = 90)
        assertThat(skip.containsIntro(29_999, 1_200_000)).isFalse()
        assertThat(skip.containsIntro(30_000, 1_200_000)).isTrue()
        assertThat(skip.containsIntro(89_999, 1_200_000)).isTrue()
        assertThat(skip.containsIntro(90_000, 1_200_000)).isFalse()
    }

    @Test
    fun `outro does not swallow post credits scene`() {
        val skip = SkipTimeInfo(outroStartSec = 1000, outroEndSec = 1100)
        assertThat(skip.containsOutro(999_999, 1_200_000)).isFalse()
        assertThat(skip.containsOutro(1_000_000, 1_200_000)).isTrue()
        assertThat(skip.containsOutro(1_100_000, 1_200_000)).isFalse()
    }

    @Test
    fun `unknown reversed negative and out of duration ranges never skip`() {
        listOf(
            SkipTimeInfo(),
            SkipTimeInfo(introStartSec = -1, introEndSec = 90),
            SkipTimeInfo(introStartSec = 90, introEndSec = 30),
            SkipTimeInfo(introEndSec = 1500),
        ).forEach { assertThat(it.containsIntro(0, 1_200_000)).isFalse() }
        assertThat(SkipTimeInfo(introEndSec = 90).containsIntro(0, 0)).isFalse()
        assertThat(SkipTimeInfo(outroStartSec = 1000).containsOutro(1_100_000, 1_200_000)).isFalse()
    }
}
