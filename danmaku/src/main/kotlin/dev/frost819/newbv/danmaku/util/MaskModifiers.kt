package dev.frost819.newbv.danmaku.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.caverock.androidsvg.SVG
import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuMaskFrame
import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuMobMaskFrame
import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuWebMaskFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 基础蒙版 Modifier：将 Bitmap 以 [BlendMode.DstIn] 方式叠加到内容上。
 *
 * 通过 saveLayer + DstIn 混合实现"挖空"效果，仅保留 Bitmap 中非透明区域的内容。
 *
 * @param bitmap      蒙版 Bitmap（ARGB_8888）
 * @param videoAspectRatio 视频宽高比（如 1920/1080 ≈ 1.777），用于将蒙版对齐到视频区域
 */
fun Modifier.bitmapMask(
    bitmap: Bitmap,
    videoAspectRatio: Float,
): Modifier =
    composed {
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
        val layerPaint = remember { Paint() }

        drawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), layerPaint)
                drawContent()

                val screenWidth = size.width
                val screenHeight = size.height
                val screenAspectRatio = screenWidth / screenHeight

                val dstWidth: Float
                val dstHeight: Float
                val offsetX: Float
                val offsetY: Float

                if (videoAspectRatio > screenAspectRatio) {
                    dstWidth = screenWidth
                    dstHeight = dstWidth / videoAspectRatio
                    offsetX = 0f
                    offsetY = (screenHeight - dstHeight) / 2f
                } else {
                    dstHeight = screenHeight
                    dstWidth = dstHeight * videoAspectRatio
                    offsetY = 0f
                    offsetX = (screenWidth - dstWidth) / 2f
                }

                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                    dstSize = IntSize(dstWidth.toInt(), dstHeight.toInt()),
                    blendMode = BlendMode.DstIn,
                )

                canvas.restore()
            }
        }
    }

/**
 * WebMask 蒙版 Modifier：将 SVG 数据渲染为 Bitmap 后应用 [bitmapMask]。
 *
 * [DanmakuWebMaskFrame] 包含已经解码的 SVG 字符串，后台将其渲染为 Bitmap。
 * 转换在后台执行，帧变化时取消旧转换，只持有当前帧位图。
 *
 * @param frame      WebMask 帧数据
 * @param aspectRatio 视频宽高比（用于蒙版对齐）
 */
fun Modifier.danmakuWebMask(
    frame: DanmakuWebMaskFrame,
    aspectRatio: Float,
): Modifier = danmakuFrameMask(frame, aspectRatio)

/**
 * MobMask 蒙版 Modifier：将 1bpp 像素数据解码为 Bitmap 后应用 [bitmapMask]。
 *
 * [DanmakuMobMaskFrame] 包含帧的宽高和 1bit/pixel 的位图数据（MSB first）。
 * 位图数据被解码为 ARGB_8888 Bitmap（0bit = BLACK，1bit = TRANSPARENT），实现"挖空"效果。
 * 转换在后台执行，帧变化时取消旧转换，只持有当前帧位图。
 *
 * @param frame      MobMask 帧数据
 * @param aspectRatio 视频宽高比（用于蒙版对齐）
 */
fun Modifier.danmakuMobMask(
    frame: DanmakuMobMaskFrame,
    aspectRatio: Float,
): Modifier = danmakuFrameMask(frame, aspectRatio)

private fun Modifier.danmakuFrameMask(
    frame: DanmakuMaskFrame,
    aspectRatio: Float,
): Modifier =
    composed {
        val bitmap by produceState<Bitmap?>(null, frame) {
            value = null
            value = createDanmakuMaskBitmap(frame)
        }
        bitmap?.let { bitmapMask(it, aspectRatio) } ?: this
    }

/**
 * 在 Default 工作线程将 Web SVG 或 App 1bpp 蒙版帧转换为位图。
 *
 * 不缓存转换结果，由调用方仅保留当前帧。取消后不会发布过时位图；
 * 不修改或回收已经由 UI 使用的位图。
 * @param frame 当前蒙版帧。
 * @return ARGB_8888 位图；SVG 无法解析或像素数据不完整时返回 null。
 * @throws kotlinx.coroutines.CancellationException 调用方取消转换时抛出。
 */
suspend fun createDanmakuMaskBitmap(frame: DanmakuMaskFrame): Bitmap? =
    withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val bitmap =
            when (frame) {
                is DanmakuWebMaskFrame -> {
                    val svg = runCatching { SVG.getFromString(frame.svg) }.getOrNull() ?: return@withContext null
                    val width = svg.documentWidth.toInt().coerceAtLeast(1)
                    val height = svg.documentHeight.toInt().coerceAtLeast(1)
                    context.ensureActive()
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        svg.renderToCanvas(Canvas(it))
                    }
                }
                is DanmakuMobMaskFrame -> {
                    val width = frame.width
                    val height = frame.height
                    val pixelCount = width.toLong() * height
                    if (width <= 0 ||
                        height <= 0 ||
                        pixelCount > Int.MAX_VALUE ||
                        (pixelCount + 7) / 8 > frame.image.size
                    ) {
                        return@withContext null
                    }
                    val row = IntArray(width)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        // 只展开一行，避免为每帧再分配 width * height 大小的 IntArray。
                        for (y in 0 until height) {
                            context.ensureActive()
                            for (x in 0 until width) {
                                val i = y * width + x
                                val bit = (frame.image[i / 8].toInt() shr (7 - i % 8)) and 1
                                row[x] =
                                    if (bit == 1) Color.TRANSPARENT else Color.BLACK
                            }
                            bitmap.setPixels(row, 0, width, 0, y, width, 1)
                        }
                    }
                }
            }
        context.ensureActive()
        bitmap
    }

/**
 * 弹幕蒙版 Modifier（统一入口）。
 *
 * 自动根据 [DanmakuMaskFrame] 的具体类型（[DanmakuWebMaskFrame] / [DanmakuMobMaskFrame]）
 * 分发到对应的 Modifier 实现。
 *
 * @param frame      蒙版帧数据，null 时不应用任何效果
 * @param aspectRatio 视频宽高比（用于蒙版对齐到视频区域）
 */
fun Modifier.danmakuMask(
    frame: DanmakuMaskFrame?,
    aspectRatio: Float,
): Modifier =
    composed {
        if (frame == null) return@composed this

        when (frame) {
            is DanmakuWebMaskFrame -> danmakuWebMask(frame, aspectRatio)
            is DanmakuMobMaskFrame -> danmakuMobMask(frame, aspectRatio)
        }
    }
