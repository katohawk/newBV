package dev.frost819.newbv.data.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 全局唯一的画面遮挡。比例相对实际视频布局区域，而非屏幕；位置为左上角。
 *
 * @property enabled 是否显示
 * @property xRatio 左边距比例
 * @property yRatio 上边距比例
 * @property widthRatio 宽度比例，至少 0.02
 * @property heightRatio 高度比例，至少 0.02
 * @property color 不含透明度的 RGB 色值
 * @property alpha 不透明度，0 为完全透明，1 为不透明
 * @property cornerRadiusDp 圆角半径（dp）
 */
@Serializable
data class ScreenMaskConfig(
    val enabled: Boolean = false,
    val xRatio: Float = 0.05f,
    val yRatio: Float = 0.80f,
    val widthRatio: Float = 0.90f,
    val heightRatio: Float = 0.15f,
    val color: Long = 0x000000,
    val alpha: Float = 1f,
    val cornerRadiusDp: Float = 0f,
) {
    /** 修复越界和非有限数值；优先保留尺寸，再把位置移回视频内。 */
    fun normalized(): ScreenMaskConfig {
        val width = widthRatio.finiteOr(0.90f).coerceIn(0.02f, 1f)
        val height = heightRatio.finiteOr(0.15f).coerceIn(0.02f, 1f)
        return copy(
            xRatio = xRatio.finiteOr(0.05f).coerceIn(0f, 1f - width),
            yRatio = yRatio.finiteOr(0.80f).coerceIn(0f, 1f - height),
            widthRatio = width,
            heightRatio = height,
            color = color.coerceIn(0L, 0xFFFFFFL),
            alpha = alpha.finiteOr(1f).coerceIn(0f, 1f),
            cornerRadiusDp = cornerRadiusDp.finiteOr(0f).coerceIn(0f, 100f),
        )
    }

    /** 移动或缩放一个比例步长；缩放固定左上角，到边界停止。 */
    fun adjusted(
        horizontal: Float,
        vertical: Float,
        resize: Boolean,
    ): ScreenMaskConfig {
        val current = normalized()
        return if (resize) {
            current.copy(
                // 1 - 0.98f 可能略小于 0.02f，避免浮点误差令 coerceIn 区间反转。
                widthRatio =
                    (current.widthRatio + horizontal.finiteOr(0f))
                        .coerceIn(0.02f, (1f - current.xRatio).coerceAtLeast(0.02f)),
                heightRatio =
                    (current.heightRatio + vertical.finiteOr(0f))
                        .coerceIn(0.02f, (1f - current.yRatio).coerceAtLeast(0.02f)),
            )
        } else {
            current.copy(xRatio = current.xRatio + horizontal, yRatio = current.yRatio + vertical).normalized()
        }
    }

    /** 将比例转为视频局部坐标；只在绘制时换算，不持久化像素。 */
    fun bounds(
        videoWidth: Float,
        videoHeight: Float,
    ): ScreenMaskBounds {
        val config = normalized()
        return ScreenMaskBounds(
            x = config.xRatio * videoWidth,
            y = config.yRatio * videoHeight,
            width = config.widthRatio * videoWidth,
            height = config.heightRatio * videoHeight,
        )
    }

    /** 编码为单个偏好值，保证区域与外观一起保存。 */
    fun encode(): String = json.encodeToString(normalized())

    /** 容错读取旧版本或损坏的配置。 */
    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** 缺失字段使用默认值，非法 JSON 恢复默认关闭状态。 */
        fun decode(value: String): ScreenMaskConfig =
            runCatching { json.decodeFromString<ScreenMaskConfig>(value).normalized() }
                .getOrDefault(ScreenMaskConfig())
    }
}

/** 视频局部绘制坐标，仅用于运行时换算，不保存到 Prefs。 */
data class ScreenMaskBounds(
    /** 左边距。 */
    val x: Float,
    /** 上边距。 */
    val y: Float,
    /** 宽度。 */
    val width: Float,
    /** 高度。 */
    val height: Float,
)

private fun Float.finiteOr(default: Float): Float = if (isFinite()) this else default
