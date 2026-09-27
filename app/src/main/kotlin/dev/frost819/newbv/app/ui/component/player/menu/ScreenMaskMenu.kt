package dev.frost819.newbv.app.ui.component.player.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.frost819.newbv.app.ui.component.player.menu.component.PlayerThreeLevelMenu
import dev.frost819.newbv.app.ui.component.player.menu.component.RadioMenuList
import dev.frost819.newbv.app.ui.component.player.menu.component.StepLessMenuItem
import dev.frost819.newbv.app.viewmodel.player.MenuFocusState
import dev.frost819.newbv.data.datastore.ScreenMaskConfig

private enum class MaskSetting(
    val label: String,
) {
    Enabled("画面遮挡"),
    Region("编辑遮挡区域"),
    Color("颜色"),
    Red("自定义 · 红 (R)"),
    Green("自定义 · 绿 (G)"),
    Blue("自定义 · 蓝 (B)"),
    Alpha("不透明度"),
    Corner("圆角"),
    Reset("重置默认值"),
}

private val maskColors =
    listOf(
        "黑色" to 0x000000L,
        "白色" to 0xFFFFFFL,
        "灰色" to 0x808080L,
        "深灰" to 0x333333L,
        "浅灰" to 0xCCCCCCL,
        "米白" to 0xFFF8E7L,
        "红色" to 0xFF0000L,
        "橙色" to 0xFFA500L,
        "黄色" to 0xFFFF00L,
        "绿色" to 0x008000L,
        "蓝色" to 0x0000FFL,
        "紫色" to 0x800080L,
    )
private val maskColorLabels = maskColors.map { it.first }

/** 复用播放器三级菜单；外观修改立即保存，区域编辑交给全屏遥控器编辑层。 */
@Composable
fun ScreenMaskMenu(
    config: ScreenMaskConfig,
    onChange: (ScreenMaskConfig) -> Unit,
    onEdit: () -> Unit,
    onFocusStateChange: (MenuFocusState) -> Unit,
) {
    PlayerThreeLevelMenu(
        categories = MaskSetting.entries,
        categoryLabel = { it.label },
        onFocusStateChange = onFocusStateChange,
    ) { setting, modifier, back ->
        when (setting) {
            MaskSetting.Enabled ->
                RadioMenuList(
                    modifier = modifier,
                    items = listOf("关闭", "开启"),
                    selected = if (config.enabled) 1 else 0,
                    onSelectedChanged = { onChange(config.copy(enabled = it == 1)) },
                    onFocusBackToParent = back,
                )
            MaskSetting.Region ->
                RadioMenuList(
                    modifier = modifier,
                    items = listOf("自定义（遥控器编辑）", "底部字幕", "双语字幕"),
                    selected = 0,
                    onSelectedChanged = {
                        when (it) {
                            0 -> onEdit()
                            1 ->
                                onChange(
                                    config.copy(
                                        xRatio = 0.05f,
                                        yRatio = 0.80f,
                                        widthRatio = 0.90f,
                                        heightRatio = 0.15f,
                                    ),
                                )
                            2 ->
                                onChange(
                                    config.copy(
                                        xRatio = 0.05f,
                                        yRatio = 0.68f,
                                        widthRatio = 0.90f,
                                        heightRatio = 0.27f,
                                    ),
                                )
                        }
                    },
                    onFocusBackToParent = back,
                )
            MaskSetting.Color ->
                Column(modifier.fillMaxHeight()) {
                    MaskColorPreview(config.color)
                    RadioMenuList(
                        modifier = Modifier.weight(1f),
                        items = maskColorLabels,
                        selected = maskColors.indexOfFirst { it.second == config.color },
                        onSelectedChanged = { onChange(config.copy(color = maskColors[it].second)) },
                        onFocusBackToParent = back,
                    )
                }
            MaskSetting.Red, MaskSetting.Green, MaskSetting.Blue -> {
                val shift =
                    when (setting) {
                        MaskSetting.Red -> 16
                        MaskSetting.Green -> 8
                        else -> 0
                    }
                Column(modifier.fillMaxHeight()) {
                    MaskColorPreview(config.color)
                    StepLessMenuItem(
                        modifier = Modifier.weight(1f),
                        value = ((config.color shr shift) and 0xFF).toFloat(),
                        text = "${(config.color shr shift) and 0xFF} / 255",
                        step = 1f,
                        range = 0f..255f,
                        onValueChange = { value ->
                            // 仅替换选中的 8 位颜色通道，不改变其他通道和独立的不透明度。
                            val color = (config.color and (0xFFL shl shift).inv()) or (value.toLong() shl shift)
                            onChange(config.copy(color = color))
                        },
                        onFocusBackToParent = back,
                    )
                }
            }
            MaskSetting.Alpha ->
                StepLessMenuItem(
                    modifier = modifier,
                    value = config.alpha,
                    text = "${(config.alpha * 100).toInt()}%",
                    step = 0.05f,
                    onValueChange = { onChange(config.copy(alpha = it)) },
                    onFocusBackToParent = back,
                )
            MaskSetting.Corner ->
                StepLessMenuItem(
                    modifier = modifier,
                    value = config.cornerRadiusDp,
                    text = "${config.cornerRadiusDp.toInt()}dp",
                    step = 1f,
                    range = 0f..100f,
                    onValueChange = { onChange(config.copy(cornerRadiusDp = it)) },
                    onFocusBackToParent = back,
                )
            MaskSetting.Reset ->
                RadioMenuList(
                    modifier = modifier,
                    items = listOf("恢复默认（关闭遮挡）"),
                    onSelectedChanged = { onChange(ScreenMaskConfig()) },
                    onFocusBackToParent = back,
                )
        }
    }
}

/** 显示不透明的颜色样本，遮挡关闭或 alpha=0 时仍可调色。 */
@Composable
private fun MaskColorPreview(color: Long) {
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).background(Color((color or 0xFF000000L).toInt())))
        Text(text = "#${color.toString(16).padStart(6, '0').uppercase()}", color = Color.White)
    }
}
