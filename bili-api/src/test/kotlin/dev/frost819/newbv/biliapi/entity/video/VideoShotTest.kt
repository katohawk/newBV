package dev.frost819.newbv.biliapi.entity.video

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.http.BiliHttpApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/** 验证缩略图只读时间索引，不预先下载任何 Sprite，且失败不影响起播。 */
class VideoShotTest {
    @BeforeEach fun setUp() = mockkObject(BiliHttpApi)

    @AfterEach fun tearDown() = unmockkObject(BiliHttpApi)

    private fun metadata() =
        dev.frost819.newbv.biliapi.http.entity.video.VideoShot(
            pvData = "https://test/index",
            image = listOf("https://test/1.jpg", "https://test/2.jpg"),
            imgXLen = 10,
            imgYLen = 10,
            imgXSize = 160,
            imgYSize = 90,
        )

    @Test fun `index loads without downloading sprite images`() =
        runTest {
            // Given
            coEvery { BiliHttpApi.download("https://test/index") } returns byteArrayOf(0, 0, 0, 10, 0, 20)
            // When
            val shot = requireNotNull(VideoShot.fromVideoShot(metadata()))
            // Then
            assertThat(shot.times).containsExactly(10.toUShort(), 20.toUShort()).inOrder()
            assertThat(shot.imageUrls).containsExactly("https://test/1.jpg", "https://test/2.jpg").inOrder()
            coVerify(exactly = 1) { BiliHttpApi.download(any()) }
        }

    @Test fun `missing index returns null without any download`() =
        runTest {
            // Given / When
            val result = VideoShot.fromVideoShot(metadata().copy(pvData = null))
            // Then
            assertThat(result).isNull()
            coVerify(exactly = 0) { BiliHttpApi.download(any()) }
        }

    @Test fun `network failure leaves preview unavailable`() =
        runTest {
            // Given
            coEvery { BiliHttpApi.download(any()) } throws java.io.IOException("offline")
            // When / Then
            assertThat(VideoShot.fromVideoShot(metadata())).isNull()
        }

    @Test fun `truncated and nonmonotonic indexes are rejected`() =
        runTest {
            // Given
            coEvery { BiliHttpApi.download(any()) } returns byteArrayOf(0, 0, 1)
            // When / Then
            assertThat(VideoShot.fromVideoShot(metadata())).isNull()
            coEvery { BiliHttpApi.download(any()) } returns byteArrayOf(0, 0, 0, 20, 0, 10)
            assertThat(VideoShot.fromVideoShot(metadata())).isNull()
        }

    @Test fun `canceled index loading remains canceled`() =
        runTest {
            // Given
            coEvery { BiliHttpApi.download(any()) } throws CancellationException("left player")
            // When / Then
            assertFailsWith<CancellationException> { VideoShot.fromVideoShot(metadata()) }
        }
}
