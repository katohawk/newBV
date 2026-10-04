/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.kuaishou.akdanmaku.ui

import android.graphics.Canvas
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Choreographer
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.data.DataSource
import com.kuaishou.akdanmaku.ecs.DanmakuEngine
import com.kuaishou.akdanmaku.ecs.system.DataSystem
import com.kuaishou.akdanmaku.ecs.system.RenderSystem
import com.kuaishou.akdanmaku.ext.endTrace
import com.kuaishou.akdanmaku.ext.startTrace
import com.kuaishou.akdanmaku.render.DanmakuRenderer
import com.kuaishou.akdanmaku.utils.Fraction
import com.kuaishou.akdanmaku.utils.ObjectPool
import java.lang.ref.WeakReference
import java.util.concurrent.Semaphore
import kotlin.math.max

/**
 *
 * 弹幕播放器，与 [DanmakuView] 形成类似于视频播放器的弹幕播放结构
 * 此类应当在共享同一个弹幕播放的场景间进行共享，它持有着
 * - 播放上下文（计时器，渲染器，缓存管理等）
 * - 渲染引擎
 * 在与一个 [DanmakuView] 绑定后会通过 [Choreographer] 进行帧同步，通过信号量在绘制和后台计算之间进行同步
 *
 * 内部具有一个用于执行计算的线程，几乎所有对外 API 均为同步返回，具体操作在此线程中进行
 *
 * @param renderer 业务端自定义的弹幕渲染器
 */
@Suppress("unused")
class DanmakuPlayer(
    renderer: DanmakuRenderer,
    dataSource: DataSource? = null,
) {
    companion object {
        internal const val MSG_FRAME_UPDATE = 2101
        internal const val NOTIFY_DISPLAYER_SIZE_CHANGE = 2201

        private const val PLAYER_WIDTH = 682
        const val MIN_DANMAKU_DURATION: Long = 4000
        const val MAX_DANMAKU_DURATION_HIGH_DENSITY: Long = 9000

        /**
         * 是否手动控制 Step 流程
         */
        var isManualStep = false
    }

    private var danmakuView: WeakReference<DanmakuView>? = null
    internal val engine = DanmakuEngine.get(renderer)
    private val actionThread by lazy { HandlerThread("ActionThread").apply { start() } }
    private val actionHandler by lazy { ActionHandler(actionThread.looper) }
    private val frameCallback by lazy { FrameCallback(actionHandler) }

    private var currentDisplayerWidth = 0
    private var currentDisplayerHeight = 0
    private var currentDisplayerSizeFactor = 1f
    private var config: DanmakuConfig? = null

    private val drawSemaphore = Semaphore(0)

    @Volatile
    private var started = false

    @Volatile
    private var renderingEnabled = true
    private var frameCallbackScheduled = false

    /** 是否允许弹幕渲染；关闭、后台或已释放时，直播消息可据此丢弃。 */
    val isRenderingEnabled: Boolean
        get() = renderingEnabled && !isReleased

    private val dataSystem: DataSystem?
        get() = engine.getSystem(DataSystem::class.java)

    /**
     * 弹幕埋点所需的接口
     */
    var listener: DanmakuListener? = null
        set(value) {
            if (field != value) {
                field = value
                engine.getSystem(RenderSystem::class.java)?.listener = value
            }
        }

    @Volatile
    var isReleased: Boolean = false
        private set

    val cacheHit: Fraction?
        get() = engine.getSystem(RenderSystem::class.java)?.cacheHit

    init {
        dataSource?.setListener(dataSystem)
    }

    /**
     * 运行在[actionHandler] [actionThread] 线程中
     */
    private fun postFrameCallback() {
        if (!started || isReleased || !renderingEnabled) return
        if (frameCallbackScheduled || danmakuView?.get() == null) return
        frameCallbackScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun updateFrame(deltaTimeSeconds: Float? = null) {
        if (!started || isReleased || !renderingEnabled) {
            return
        }

        if (isManualStep) {
            // Time goes one step for manual debug.
            engine.step(deltaTimeSeconds)
        } else {
            // Prepare next frameCallback.
            postFrameCallback()
            // 不阻塞 HandlerThread；解绑视图后没有 onDraw，控制/释放消息仍必须可执行。
            if (!drawSemaphore.tryAcquire()) return
            // update entities before system update
            engine.preAct()
        }
        if (!started || isReleased || !renderingEnabled) {
            return
        }
        startTrace("updateFrame")
        // Do work in actionThread.
        engine.act()
        // Post invalidate view to force onDraw's call on next frame.
        startTrace("postInvalidate")
        danmakuView?.get()?.postInvalidateOnAnimation()
        endTrace()
        endTrace()
    }

    internal fun draw(canvas: Canvas) {
        if (isReleased) {
            return
        }
        if (!isManualStep) {
            // Time goes one step.
            engine.step()
        }
        drawSemaphore.tryAcquire()
        if (!started || !renderingEnabled) {
            releaseSemaphore()
            return
        }
        engine.draw(canvas) {
            releaseSemaphore()
        }
    }

    private fun releaseSemaphore() {
        // Acquired or on the first draw(with init permit: 0).
        synchronized(drawSemaphore) {
            if (drawSemaphore.availablePermits() == 0) {
                drawSemaphore.release()
            }
        }
    }

    /**
     * For debug use, step manually.
     */
    fun step(deltaTimeMs: Int) {
        if (isManualStep) {
            actionHandler.obtainMessage(MSG_FRAME_UPDATE, deltaTimeMs, 0).sendToTarget()
        }
    }

    /**
     * 将播放器与一个 DanmakuView 绑定，前一个被绑定的会自动解锁。
     * 绑定后弹幕的绘制将在此 View 上进行
     */
    fun bindView(danmakuView: DanmakuView) {
        if (isReleased || this.danmakuView?.get() === danmakuView) return
        this.danmakuView?.get()?.danmakuPlayer = null
        this.danmakuView = WeakReference(danmakuView)
        danmakuView.danmakuPlayer = this
        engine.context.displayer = danmakuView.displayer
        notifyDisplayerSizeChanged(danmakuView.displayer.width, danmakuView.displayer.height)
        danmakuView.postInvalidate()
        actionHandler.post { postFrameCallback() }
    }

    /**
     * 解绑指定视图并停止帧调度；不会误解绑随后绑定的新视图。
     * @param view 要解绑的视图。
     */
    fun unbindView(view: DanmakuView) {
        if (danmakuView?.get() !== view) return
        view.danmakuPlayer = null
        danmakuView = null
        actionHandler.post { cancelFrameCallback() }
    }

    /**
     * 控制帧调度，用于弹幕关闭和页面不可见。
     *
     * 与计时器暂停分开；恢复播放位置及播放状态由调用方同步。
     * @param enabled 是否允许计算和绘制弹幕帧。
     */
    fun setRenderingEnabled(enabled: Boolean) {
        if (isReleased || renderingEnabled == enabled) return
        renderingEnabled = enabled
        releaseSemaphore()
        actionHandler.post {
            cancelFrameCallback()
            if (renderingEnabled) {
                danmakuView?.get()?.postInvalidate()
                postFrameCallback()
            }
        }
    }

    private fun cancelFrameCallback() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameCallbackScheduled = false
        actionHandler.removeMessages(MSG_FRAME_UPDATE)
    }

    /**
     * 播放弹幕
     *
     * @param danmakuConfig 弹幕配置
     */
    fun start(danmakuConfig: DanmakuConfig? = null) {
        if (isReleased) return
        danmakuConfig?.let {
            updateConfig(it)
        }
        engine.start()
        if (!started) {
            started = true
            if (!isManualStep) {
                actionHandler.post { postFrameCallback() }
            }
        }
    }

    fun pause() {
        if (isReleased) return
        engine.pause()
    }

    fun stop() {
        engine.pause()
        seekTo(0)
    }

    /**
     * 释放弹幕播放器，释放后弹幕播放器将不再可用。
     */
    @Synchronized
    fun release() {
        if (isReleased) {
            return
        }
        isReleased = true
        started = false
        renderingEnabled = false
        danmakuView?.get()?.danmakuPlayer = null
        danmakuView = null
        releaseSemaphore()
        actionHandler.removeCallbacksAndMessages(null)
        // 在计算线程完成 ECS 清理，避免与正在执行的 updateFrame 竞争；主线程不等待退出。
        actionHandler.post {
            cancelFrameCallback()
            engine.release()
            actionThread.quitSafely()
        }
    }

    fun seekTo(positionMs: Long) {
        if (isReleased) return
        Log.d(DanmakuEngine.TAG, "[Player] SeekTo($positionMs)")
        getConfig()?.updateFirstShown()
        engine.seekTo(max(positionMs, 0L))
    }

    fun getCurrentTimeMs(): Long = engine.getCurrentTimeMs()

    fun updatePlaySpeed(speed: Float) {
        if (isReleased) return
        engine.updateTimerFactor(speed)
    }

    fun updateData(dataList: List<DanmakuItemData>): List<DanmakuItem> {
        if (isReleased) return emptyList()
        val items = dataList.map { obtainItem(it) }
        dataSystem?.addItems(items)
        return items
    }

    /**
     * 清空所有已加载与待处理的弹幕数据，引擎保持可用。
     *
     * 用于切换视频时丢弃旧视频的弹幕。注意 [updateData] 是增量追加而非
     * 整体替换，传入空列表不会清空数据，必须调用本方法。
     */
    fun clearData() {
        if (isReleased) return
        dataSystem?.clearData()
        actionHandler.post { dataSystem?.applyPendingChanges() }
    }

    /**
     * 保留指定时间窗口内的原始弹幕及活动实体，裁剪在计算线程执行。
     *
     * @param startInclusiveMs 窗口起点（毫秒，含）。
     * @param endExclusiveMs 窗口终点（毫秒，不含），必须大于起点。
     * @throws IllegalArgumentException 窗口终点不大于起点时抛出。
     */
    fun retainData(
        startInclusiveMs: Long,
        endExclusiveMs: Long,
    ) {
        require(endExclusiveMs > startInclusiveMs) { "Retention window must not be empty" }
        if (isReleased) return
        dataSystem?.retainData(startInclusiveMs, endExclusiveMs)
        actionHandler.post { dataSystem?.applyPendingChanges() }
    }

    /**
     * 弹幕目前统一的数据结构就是 DanmakuItem，他是 DanmakuItemData 的超集，也是被定义为
     * 可以进行扩展的
     */
    fun updateItems(items: List<DanmakuItem>) {
        dataSystem?.addItems(items)
    }

    fun send(danmaku: DanmakuItemData): DanmakuItem {
        val item = obtainItem(danmaku)
        dataSystem?.addItem(item)
        return item
    }

    fun send(item: DanmakuItem) {
        dataSystem?.addItem(item)
    }

    /**
     * 更新一个弹幕
     */
    fun updateItem(item: DanmakuItem) {
        dataSystem?.updateItem(item)
    }

    fun updateConfig(danmakuConfig: DanmakuConfig?) {
        val oldConfig = config
        config = danmakuConfig
        if (danmakuConfig == null) return

        if (oldConfig != null && danmakuConfig.rollingSpeedFactor != oldConfig.rollingSpeedFactor) {
            danmakuConfig.rollingSpeedFactor = oldConfig.rollingSpeedFactor
        }

        engine.updateConfig(danmakuConfig)

        if (currentDisplayerWidth > 0) {
            updateViewportState(currentDisplayerWidth, currentDisplayerHeight, currentDisplayerSizeFactor, true)
        }
    }

    /**
     * 返回稳定的最新配置，计算线程消费待应用配置后仍可读取。
     * @return 最近传入的配置；尚未设置时返回引擎默认配置。
     */
    fun getConfig(): DanmakuConfig? = config ?: engine.context.config

    fun getDanmakusAtPoint(point: Point): List<DanmakuItem>? =
        engine.getSystem(RenderSystem::class.java)?.getDanmakus(point)

    fun getDanmakusInRect(hitRect: RectF): List<DanmakuItem>? =
        engine.getSystem(RenderSystem::class.java)?.getDanmakus(hitRect)

    fun hold(item: DanmakuItem?) {
        dataSystem?.hold(item)
    }

    fun obtainItem(danmaku: DanmakuItemData): DanmakuItem = ObjectPool.obtainItem(danmaku, this)

    fun releaseItem(item: DanmakuItem) {
        ObjectPool.releaseItem(item)
    }

    /**
     * 设置滚动弹幕的速度系数
     * @param factor 速度系数。
     */
    fun setDanmakuRollingSpeed(factor: Float) {
        if (factor <= 0) return

        if (factor != config?.rollingSpeedFactor) {
            config?.rollingSpeedFactor = factor
        }

        // 使用当前保存的尺寸重新计算时长
        updateViewportState(currentDisplayerWidth, currentDisplayerHeight, currentDisplayerSizeFactor, true)
    }

    internal fun notifyDisplayerSizeChanged(
        width: Int,
        height: Int,
    ) {
        val displayer = engine.context.displayer
        updateViewportState(width, height, displayer.getViewportSizeFactor())
        updateMaxDanmakuDuration()
        if (displayer.width != width || displayer.height != height) {
            Log.d(DanmakuEngine.TAG, "notifyDisplayerSizeChanged($width, $height)")
            displayer.width = width
            displayer.height = height
            actionHandler.obtainMessage(NOTIFY_DISPLAYER_SIZE_CHANGE).sendToTarget()
        }
    }

    private fun updateViewportState(
        width: Int,
        height: Int,
        viewportSizeFactor: Float,
        forceUpdate: Boolean = false,
    ) {
        val config = this.config ?: return
        if (currentDisplayerWidth != width ||
            currentDisplayerHeight != height ||
            currentDisplayerSizeFactor != viewportSizeFactor ||
            forceUpdate
        ) {
            val duration =
                (DanmakuConfig.DEFAULT_DURATION * (viewportSizeFactor * width / PLAYER_WIDTH)).toLong()

            if (config.rollingDurationMs != duration) {
                config.rollingDurationMs = duration
                config.updateRetainer()
                config.updateLayout()
                config.updateVisibility()
            }
            Log.d("XanaDanmaku", "[Factor] update rolling duration to $duration")
            currentDisplayerWidth = width
            currentDisplayerHeight = height
            currentDisplayerSizeFactor = viewportSizeFactor
        }
    }

    private fun updateMaxDanmakuDuration() {
        // FIXME distinguish differ danmaku type duration
    }

    private inner class ActionHandler(
        looper: Looper,
    ) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_FRAME_UPDATE -> {
                    val deltaTimeSeconds =
                        if (msg.arg1 > 0) {
                            msg.arg1 / 1000.0f
                        } else {
                            null
                        }
                    updateFrame(deltaTimeSeconds)
                }
                NOTIFY_DISPLAYER_SIZE_CHANGE -> {
                    val newConfig = engine.context.config
                    newConfig.updateLayout()
                    newConfig.updateMeasure()
                    newConfig.updateCache()
                    newConfig.updateRetainer()
                }
            }
        }
    }

    private inner class FrameCallback(
        handler: Handler,
    ) : Choreographer.FrameCallback {
        private val handlerWeakReference = WeakReference(handler)

        override fun doFrame(frameTimeNanos: Long) {
            frameCallbackScheduled = false
            val handler = handlerWeakReference.get() ?: return
            if (isReleased || !started || !renderingEnabled || danmakuView?.get() == null) return
            handler.removeMessages(MSG_FRAME_UPDATE)
            handler.sendEmptyMessage(MSG_FRAME_UPDATE)
        }
    }
}
