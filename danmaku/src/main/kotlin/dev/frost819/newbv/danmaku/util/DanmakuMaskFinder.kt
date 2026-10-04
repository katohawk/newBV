package dev.frost819.newbv.danmaku.util

import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuMask
import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuMaskFrame
import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuMaskSegment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 弹幕蒙版帧查找器。
 *
 * 根据当前播放位置查找对应的 [DanmakuMaskFrame]。
 * 内部按需解压蒙版数据（只解压当前 segment），避免一次性加载全部帧。
 *
 * **使用注意**：
 * - 切集或开关切换时调用 [reset] 清除缓存
 * - [findFrame] 是挂起函数，内部解压操作在 [Dispatchers.Default] 执行
 * - [findFrame] 和 [reset] 在同一个调用线程操作，UI 从主线程调用；后台只负责解压
 *
 * @see DanmakuMask
 * @see DanmakuMaskFrame
 */
class DanmakuMaskFinder {
    private var cachedSegment: DanmakuMaskSegment? = null
    private var cachedMask: DanmakuMask? = null
    private var cacheGeneration = 0

    /** 解压调度器，单元测试替换为可控调度器。 */
    internal var decodeDispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * 清除缓存的 segment。
     * 在切集、开关切换等导致蒙版数据变化时调用。
     */
    fun reset() {
        cacheGeneration++
        cachedMask = null
        cachedSegment = null
    }

    /**
     * 根据当前播放时间查找对应帧。
     *
     * @param mask         [DanmakuMask] 蒙版对象（从 [dev.frost819.newbv.biliapi.entity.danmaku.DanmakuMask] 获取）
     * @param currentTime  当前播放时间（毫秒）
     * @return 当前应渲染的 [DanmakuMaskFrame]，无则返回 null
     * @throws kotlinx.coroutines.CancellationException 调用方取消查找时抛出。
     */
    suspend fun findFrame(
        mask: DanmakuMask,
        currentTime: Long,
    ): DanmakuMaskFrame? {
        currentCoroutineContext().ensureActive()
        if (cachedMask !== mask) {
            reset()
            cachedMask = mask
        }
        val cached = cachedSegment
        if (cached != null && currentTime in cached.range) {
            return cached.frames.lastOrNull { currentTime in it.range }
        }
        val generation = ++cacheGeneration
        val segment = withContext(decodeDispatcher) { mask.getSegmentAt(currentTime) }
        // reset/切换视频发生在调用线程；过时的后台解压不得回写单段缓存。
        currentCoroutineContext().ensureActive()
        if (generation != cacheGeneration || cachedMask !== mask) return null
        cachedSegment = segment
        return segment?.frames?.lastOrNull { currentTime in it.range }
    }
}

/**
 * 计算弹幕蒙版轮询的休眠时间。
 *
 * 逻辑：
 * - 有蒙版 + 播放中：休眠到蒙版结束（限制在 20~300ms 以便响应 Seek）
 * - 播放中无蒙版：正常轮询间隔 100ms
 * - 暂停或异常：降低频率 200ms
 *
 * @param currentFrame 当前蒙版帧，null 表示无有效蒙版
 * @param currentTime  当前播放时间（毫秒）
 * @param isPlaying    是否正在播放
 * @return 下次轮询前应休眠的毫秒数
 */
fun calculateMaskDelay(
    currentFrame: DanmakuMaskFrame?,
    currentTime: Long,
    isPlaying: Boolean,
): Long =
    when {
        currentFrame != null && isPlaying -> {
            (currentFrame.range.last - currentTime).coerceIn(20L, 300L)
        }
        isPlaying -> 100L
        else -> 200L
    }
