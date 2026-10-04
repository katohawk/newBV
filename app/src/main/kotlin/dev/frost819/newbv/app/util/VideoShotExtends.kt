package dev.frost819.newbv.app.util

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntRect
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.size.Size
import dev.frost819.newbv.biliapi.entity.video.VideoShot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 当前秒对应的 Sprite 索引；非法或空元数据返回 null。 */
fun VideoShot.spriteIndex(time: Int): Int? {
    if (times.isEmpty() || imageCountX <= 0 || imageCountY <= 0) return null
    return (
        findClosestValueIndex(times, time.coerceIn(0, UShort.MAX_VALUE.toInt()).toUShort()) /
            (imageCountX.toLong() * imageCountY)
    ).toInt().takeIf { it in imageUrls.indices }
}

/** 从已解码的 Sprite 中计算目标帧，保持像素裁剪、不创建独立位图。 */
fun VideoShot.spriteFrame(
    time: Int,
    sheet: Bitmap,
): SpriteFrame? {
    val index = spriteIndex(time) ?: return null
    val frameIndex =
        findClosestValueIndex(times, time.coerceIn(0, UShort.MAX_VALUE.toInt()).toUShort()) %
            (imageCountX.toLong() * imageCountY)
    val cellWidth = sheet.width / imageCountX
    val cellHeight = sheet.height / imageCountY
    if (cellWidth <= 0 || cellHeight <= 0 || index !in imageUrls.indices) return null
    val left = ((frameIndex % imageCountX) * cellWidth).toInt()
    val top = ((frameIndex / imageCountX) * cellHeight).toInt()
    return SpriteFrame(sheet.asImageBitmap(), IntRect(left, top, left + cellWidth, top + cellHeight))
}

private fun findClosestValueIndex(
    array: List<UShort>,
    target: UShort,
): Int {
    var left = 0
    var right = array.lastIndex
    while (left < right) {
        val mid = (left + right) / 2
        if (array[mid] < target) left = mid + 1 else right = mid
    }
    return left
}

/**
 * 播放器独立的 Sprite 位图缓存，最多 3 张且总量不超过 16 MiB。
 * 网络和压缩文件复用应用 Coil；Sprite 请求禁用 Coil 内存缓存，避免双重保留。
 * @param context 应用上下文，Preview 可省略（不会触发网络）。
 * @param loader 可替换的图片加载器，用于设备测试。
 */
class VideoShotImageCache(
    context: Context? = null,
    private val loader: ImageLoader? = context?.let(SingletonImageLoader::get),
) {
    private val requestContext = context?.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = mutableMapOf<Int, Deferred<Bitmap>>()
    private var generation = 0L
    private var currentIndex: Int? = null
    private val memoryCache =
        object : LruCache<Int, Bitmap>(MAX_BYTES) {
            override fun sizeOf(
                key: Int,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }

    /** 当前驻留图片数，供缓存预算的设备测试核验。 */
    internal val cachedImageCount: Int get() = memoryCache.snapshot().size

    /** 当前驻留位图字节数，供缓存预算的设备测试核验。 */
    internal val cachedImageBytes: Int get() = memoryCache.size()

    /** 保留当前位置附近的图片，并取消远距离拖动遗留的加载。 */
    fun retainWindow(index: Int) {
        synchronized(tasks) {
            currentIndex = index
            tasks.keys.filter { it !in index..(index + 1) }.forEach { tasks.remove(it)?.cancel() }
            memoryCache
                .snapshot()
                .keys
                .filter { it !in (index - 1)..(index + 1) }
                .forEach(memoryCache::remove)
        }
    }

    /** 加载当前 Sprite；同时请求相同索引时共用一个任务，失败不缓存。 */
    suspend fun getOrLoadImage(
        index: Int,
        url: String,
    ): Bitmap {
        val task =
            synchronized(tasks) {
                memoryCache.get(index)?.let { return it }
                tasks.getOrPut(index) {
                    val expectedGeneration = generation
                    scope
                        .async(start = CoroutineStart.LAZY) {
                            val context = requireNotNull(requestContext) { "Sprite loader needs a context" }
                            val request =
                                ImageRequest
                                    .Builder(context)
                                    .data(url)
                                    .size(Size.ORIGINAL)
                                    .allowHardware(false)
                                    .bitmapConfig(Bitmap.Config.RGB_565)
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .build()
                            val result = requireNotNull(loader).execute(request)
                            val bitmap =
                                ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap
                                    ?: throw IllegalStateException("Sprite image unavailable")
                            currentCoroutineContext().ensureActive()
                            synchronized(tasks) {
                                if (expectedGeneration == generation) retainBitmap(index, bitmap)
                            }
                            bitmap
                        }.also { deferred ->
                            deferred.invokeOnCompletion {
                                synchronized(tasks) { if (tasks[index] === deferred) tasks.remove(index) }
                            }
                        }
                }
            }
        task.start()
        return task.await()
    }

    private fun retainBitmap(
        index: Int,
        bitmap: Bitmap,
    ) {
        val bytes = bitmap.allocationByteCount
        if (bytes > MAX_BYTES) return
        // 预取不能把正在显示的正常当前图挤出缓存，否则 UI 与缓存会同时保留超预算图片。
        val protectedIndex = currentIndex?.takeIf { it != index }
        val protectedBytes = protectedIndex?.let { memoryCache.snapshot()[it]?.allocationByteCount } ?: 0
        if (bytes > MAX_BYTES - protectedBytes) return
        memoryCache.remove(index)
        for (cached in memoryCache.snapshot().keys) {
            if (memoryCache.size() + bytes <= MAX_BYTES && memoryCache.snapshot().size < MAX_IMAGES) break
            if (cached != protectedIndex) memoryCache.remove(cached)
        }
        memoryCache.put(index, bitmap)
    }

    /** 预览关闭或切集时取消请求并释放全部缓存；同实例可以再次使用。 */
    fun clear() {
        synchronized(tasks) {
            generation++
            currentIndex = null
            tasks.values.toList().forEach { it.cancel() }
            tasks.clear()
            scope.coroutineContext.cancelChildren()
            memoryCache.evictAll()
        }
    }

    companion object {
        /** 最大驻留 Sprite 数量。 */
        const val MAX_IMAGES = 3

        /** 位图缓存最大字节数（不包含当前显示的超额单张与解码瞬时内存）。 */
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}

/**
 * 一张 Sprite 与其中的裁剪范围；绘制不会复制单格像素。
 * @property spriteSheet 包含整组缩略图的共享位图。
 * @property srcRect 本次预览在共享位图中的像素范围。
 */
data class SpriteFrame(
    val spriteSheet: ImageBitmap,
    val srcRect: IntRect,
)
