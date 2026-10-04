package dev.frost819.newbv.danmaku.util

import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuMobMaskFrame
import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuWebMaskFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 验证后台转换保持像素含义，并正确处理无效输入及取消。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DanmakuMaskBitmapTest {
    @Test
    fun `mobile bits preserve transparent and black pixels across rows`() =
        runBlocking {
            // Given: 宽度不是 8 的倍数，第二行从同一字节的中间继续读取。
            val frame = DanmakuMobMaskFrame(0L..100L, 3, 2, byteArrayOf(0b10101000.toByte()))

            // When
            val bitmap = requireNotNull(createDanmakuMaskBitmap(frame))

            // Then
            assertThat(bitmap.width).isEqualTo(3)
            assertThat(bitmap.height).isEqualTo(2)
            assertThat(bitmap.getPixel(0, 0)).isEqualTo(Color.TRANSPARENT)
            assertThat(bitmap.getPixel(1, 0)).isEqualTo(Color.BLACK)
            assertThat(bitmap.getPixel(0, 1)).isEqualTo(Color.BLACK)
            assertThat(bitmap.getPixel(1, 1)).isEqualTo(Color.TRANSPARENT)
        }

    @Test
    fun `web SVG renders matching dimensions and alpha`() =
        runBlocking {
            // Given
            val frame =
                DanmakuWebMaskFrame(
                    0L..100L,
                    """
                    <svg xmlns="http://www.w3.org/2000/svg" width="4" height="3">
                        <rect width="2" height="3" fill="black"/>
                    </svg>
                    """.trimIndent(),
                )

            // When
            val bitmap = requireNotNull(createDanmakuMaskBitmap(frame))

            // Then
            assertThat(bitmap.width).isEqualTo(4)
            assertThat(bitmap.height).isEqualTo(3)
            assertThat(Color.alpha(bitmap.getPixel(0, 1))).isEqualTo(255)
            assertThat(Color.alpha(bitmap.getPixel(3, 1))).isEqualTo(0)
        }

    @Test
    fun `invalid SVG and incomplete mobile pixels return null`() =
        runBlocking {
            assertThat(createDanmakuMaskBitmap(DanmakuWebMaskFrame(0L..100L, "invalid"))).isNull()
            assertThat(createDanmakuMaskBitmap(DanmakuMobMaskFrame(0L..100L, 8, 2, byteArrayOf(0)))).isNull()
        }

    @Test
    fun `cancelled conversion does not return a bitmap`() =
        runBlocking {
            val cancelled = Job().apply { cancel() }
            var cancellationObserved = false
            try {
                withContext(cancelled) {
                    createDanmakuMaskBitmap(DanmakuMobMaskFrame(0L..100L, 1, 1, byteArrayOf(0)))
                }
            } catch (_: CancellationException) {
                cancellationObserved = true
            }
            assertThat(cancellationObserved).isTrue()
        }
}
