package dev.frost819.newbv.data.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** 遮挡比例、边界、异常配置与序列化的纯 JVM 测试。 */
class ScreenMaskConfigTest {
    @Test
    fun `defaults cover bottom subtitles but are disabled`() {
        val config = ScreenMaskConfig()
        assertThat(config.enabled).isFalse()
        assertThat(config.xRatio).isEqualTo(0.05f)
        assertThat(config.yRatio).isEqualTo(0.80f)
        assertThat(config.widthRatio).isEqualTo(0.90f)
        assertThat(config.heightRatio).isEqualTo(0.15f)
        assertThat(config.color).isEqualTo(0L)
        assertThat(config.alpha).isEqualTo(1f)
        assertThat(config.cornerRadiusDp).isEqualTo(0f)
    }

    @Test
    fun `bounds scale with actual video area at different resolutions`() {
        // Given: the same subtitle rectangle on 16:9, 4:3 and ultrawide video containers.
        val config = ScreenMaskConfig()
        for ((width, height) in listOf(1920f to 1080f, 3840f to 2160f, 1440f to 1080f, 1920f to 800f)) {
            // When
            val bounds = config.bounds(width, height)
            // Then: these are local coordinates, letterbox offsets belong to the parent layout.
            assertThat(bounds.x / width).isWithin(0.00001f).of(0.05f)
            assertThat(bounds.y / height).isWithin(0.00001f).of(0.80f)
            assertThat(bounds.width / width).isWithin(0.00001f).of(0.90f)
            assertThat(bounds.height / height).isWithin(0.00001f).of(0.15f)
        }
    }

    @Test
    fun `normalization clamps every boundary and minimum size`() {
        val config = ScreenMaskConfig(xRatio = -2f, yRatio = 2f, widthRatio = 4f, heightRatio = -1f).normalized()
        assertThat(config.xRatio).isEqualTo(0f)
        assertThat(config.yRatio).isEqualTo(0.98f)
        assertThat(config.widthRatio).isEqualTo(1f)
        assertThat(config.heightRatio).isEqualTo(0.02f)
        assertThat(config.xRatio + config.widthRatio).isAtMost(1f)
        assertThat(config.yRatio + config.heightRatio).isAtMost(1f)
    }

    @Test
    fun `non finite and corrupt values cannot escape normalization`() {
        val config =
            ScreenMaskConfig(
                xRatio = Float.NaN,
                yRatio = Float.POSITIVE_INFINITY,
                widthRatio = Float.NaN,
                heightRatio = Float.NEGATIVE_INFINITY,
                color = -1,
                alpha = Float.NaN,
                cornerRadiusDp = Float.NaN,
            ).normalized()
        assertThat(config).isEqualTo(ScreenMaskConfig())
        assertThat(ScreenMaskConfig.decode("broken json")).isEqualTo(ScreenMaskConfig())
        assertThat(
            ScreenMaskConfig.decode("{\"alpha\":2,\"cornerRadiusDp\":-8,\"futureField\":42}").alpha,
        ).isEqualTo(1f)
        assertThat(ScreenMaskConfig.decode("{\"cornerRadiusDp\":-8}").cornerRadiusDp).isEqualTo(0f)
    }

    @Test
    fun `movement and resizing stop at edges without moving resize anchor`() {
        val config = ScreenMaskConfig()
        val moved = config.adjusted(5f, -5f, resize = false)
        assertThat(moved.xRatio).isWithin(0.00001f).of(0.1f)
        assertThat(moved.yRatio).isEqualTo(0f)
        val grown = config.adjusted(5f, 5f, resize = true)
        assertThat(grown.xRatio).isEqualTo(config.xRatio)
        assertThat(grown.yRatio).isEqualTo(config.yRatio)
        assertThat(grown.xRatio + grown.widthRatio).isEqualTo(1f)
        assertThat(grown.yRatio + grown.heightRatio).isEqualTo(1f)
        val shrunk = grown.adjusted(-5f, -5f, resize = true)
        assertThat(shrunk.widthRatio).isEqualTo(0.02f)
        assertThat(shrunk.heightRatio).isEqualTo(0.02f)
    }

    @Test
    fun `minimum rectangle at bottom right can still resize without float range crash`() {
        val config = ScreenMaskConfig(xRatio = 0.98f, yRatio = 0.98f, widthRatio = 0.02f, heightRatio = 0.02f)
        assertThat(config.adjusted(-0.01f, -0.01f, resize = true)).isEqualTo(config)
        assertThat(config.adjusted(0.01f, 0.01f, resize = true)).isEqualTo(config)
    }

    @Test
    fun `all fields round trip and missing fields keep defaults`() {
        val config = ScreenMaskConfig(true, 0.1f, 0.2f, 0.5f, 0.3f, 0xAABBCCL, 0.4f, 12f)
        assertThat(ScreenMaskConfig.decode(config.encode())).isEqualTo(config)
        assertThat(ScreenMaskConfig.decode("{}")).isEqualTo(ScreenMaskConfig())
    }
}
