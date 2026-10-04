package dev.frost819.newbv.app.util

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.video.VideoShot
import org.junit.jupiter.api.Test

/** 验证按时间定位 Sprite，不因远距离 seek 越界。 */
class VideoShotExtendsTest {
    @Test fun `sprite index changes only when the target leaves a sheet`() {
        // Given
        val shot = VideoShot((0..5).map { (it * 10).toUShort() }, listOf("a", "b", "c"), 2, 1, 160, 90)
        // When / Then
        assertThat(shot.spriteIndex(0)).isEqualTo(0)
        assertThat(shot.spriteIndex(10)).isEqualTo(0)
        assertThat(shot.spriteIndex(20)).isEqualTo(1)
        assertThat(shot.spriteIndex(Int.MAX_VALUE)).isEqualTo(2)
    }

    @Test fun `empty or malformed grid has no preview sheet`() {
        // Given
        val shot = VideoShot(emptyList(), emptyList(), 0, 0, 160, 90)
        // When / Then
        assertThat(shot.spriteIndex(20)).isNull()
    }
}
