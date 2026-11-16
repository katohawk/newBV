package dev.frost819.newbv.core.theme

import androidx.compose.ui.graphics.Color

/**
 * new BV 品牌色板。
 *
 * 以哔哩哔哩粉为主色，深浅主题分别使用独立的文字和容器色。
 */
object BVColors {
    /** 深色主题的交互强调色，来自哔哩哔哩官方小程序配色。 */
    val Primary = Color(0xFFFB7299)

    /** 浅色主题的交互强调色。 */
    val PrimaryStrong = Color(0xFFA92B54)

    /** 浅色主题的选中容器。 */
    val PrimaryLight = Color(0xFFFFDCE7)

    /** 深色主题的粉色按钮文字及选中容器。 */
    val DarkOnPrimary = Color(0xFF280B17)
    val DarkPrimaryContainer = Color(0xFF633044)
    val DarkOnPrimaryContainer = Color(0xFFFFE1EA)

    /** 次要交互沿用较柔和的玫瑰色。 */
    val Secondary = Color(0xFFE7A2B6)
    val SecondaryStrong = Color(0xFF873D55)

    /** 黄色，用于投币/警告。 */
    val Yellow = Color(0xFFC49B5C)

    /** 绿色，用于成功状态。 */
    val Green = Color(0xFF70A684)

    /** 浅色背景上的成功文字。 */
    val GreenStrong = Color(0xFF306347)

    /** 红色，用于错误/直播。 */
    val Red = Color(0xFFC87878)

    /** 深色主题中性色。 */
    val DarkBackground = Color(0xFF171717)
    val DarkSurface = Color(0xFF222222)
    val DarkSurfaceVariant = Color(0xFF303030)
    val DarkOnBackground = Color(0xFFF2F2F2)
    val DarkOnSurface = Color(0xFFF2F2F2)
    val DarkOnSurfaceVariant = Color(0xFFB8B8B8)
    val DarkBorder = Primary

    /** 浅色主题中性色。 */
    val LightBackground = Color(0xFFFFFBFC)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFF7E9EE)
    val LightOnBackground = Color(0xFF271A20)
    val LightOnSurface = Color(0xFF271A20)
    val LightOnSurfaceVariant = Color(0xFF66545D)
    val LightBorder = PrimaryStrong
}
