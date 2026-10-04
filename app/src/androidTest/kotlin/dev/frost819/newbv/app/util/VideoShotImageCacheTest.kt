package dev.frost819.newbv.app.util

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 使用真实 Coil 和位图分配验证 Sprite 数量／字节预算，无网络依赖。 */
@RunWith(AndroidJUnit4::class)
class VideoShotImageCacheTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val cache = VideoShotImageCache(context)
    private val files = mutableListOf<File>()

    private fun imageUrl(
        size: Int = 1024,
        height: Int = size,
    ): String {
        val file = File.createTempFile("sprite-budget-", ".png", context.cacheDir)
        files.add(file)
        val bitmap = Bitmap.createBitmap(size, height, Bitmap.Config.RGB_565)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.toURI().toString()
    }

    @After fun cleanUp() {
        cache.clear()
        files.forEach { it.delete() }
    }

    @Test fun currentSheetIsReusedAndOnlyThreeDecodedSheetsRemain() =
        runBlocking {
            // Given
            val url = imageUrl()
            // When
            val first = cache.getOrLoadImage(0, url)
            val repeated = cache.getOrLoadImage(0, url)
            for (index in 1..3) cache.getOrLoadImage(index, url)
            // Then
            assertThat(repeated).isSameInstanceAs(first)
            assertThat(cache.cachedImageCount).isEqualTo(3)
            assertThat(cache.cachedImageBytes).isAtMost(VideoShotImageCache.MAX_BYTES)
        }

    @Test fun byteBudgetIsEnforcedBeforeTheThirdLargeSheet() =
        runBlocking {
            // Given
            val url = imageUrl(2048)
            // When
            for (index in 0..2) cache.getOrLoadImage(index, url)
            // Then
            assertThat(cache.cachedImageBytes).isAtMost(VideoShotImageCache.MAX_BYTES)
            assertThat(cache.cachedImageCount).isAtMost(2)
        }

    @Test fun prefetchCannotEvictTheCurrentTenMiBSheet() =
        runBlocking {
            // Given
            val url = imageUrl(2560, 2048)
            cache.retainWindow(0)
            val current = cache.getOrLoadImage(0, url)
            // When
            cache.getOrLoadImage(1, url)
            // Then
            assertThat(cache.cachedImageCount).isEqualTo(1)
            assertThat(cache.getOrLoadImage(0, url)).isSameInstanceAs(current)
            assertThat(cache.cachedImageBytes).isAtMost(VideoShotImageCache.MAX_BYTES)
        }

    @Test fun distantSeekAndClosingPreviewReleaseOldSheets() =
        runBlocking {
            // Given
            val url = imageUrl()
            cache.getOrLoadImage(0, url)
            // When
            cache.retainWindow(100)
            // Then
            assertThat(cache.cachedImageCount).isEqualTo(0)
            cache.getOrLoadImage(100, url)
            cache.clear()
            assertThat(cache.cachedImageBytes).isEqualTo(0)
            cache.getOrLoadImage(100, url)
            assertThat(cache.cachedImageCount).isEqualTo(1)
        }

    @Test fun failureIsNotCachedAndCanBeRetried() =
        runBlocking {
            // Given
            val missing = File(context.cacheDir, "missing-sprite-${System.nanoTime()}.png")
            // When / Then
            assertThat(runCatching { cache.getOrLoadImage(0, missing.toURI().toString()) }.isFailure).isTrue()
            assertThat(cache.cachedImageCount).isEqualTo(0)
            cache.getOrLoadImage(0, imageUrl())
            assertThat(cache.cachedImageCount).isEqualTo(1)
        }
}
