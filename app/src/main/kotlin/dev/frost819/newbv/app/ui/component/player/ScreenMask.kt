package dev.frost819.newbv.app.ui.component.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.frost819.newbv.data.datastore.ScreenMaskConfig

/**
 * 绘制视频局部遮挡；调用方必须与视频共用同一个容器。
 * 不接收播放进度，不可聚焦，不拦截输入。编辑时即使关闭也显示预览边框。
 */
@Composable
fun ScreenMaskOverlay(
    config: ScreenMaskConfig,
    editing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!config.enabled && !editing) return
    Canvas(modifier.fillMaxSize().testTag("screen_mask")) {
        val bounds = config.bounds(size.width, size.height)
        val offset = Offset(bounds.x, bounds.y)
        val maskSize = Size(bounds.width, bounds.height)
        val radius =
            config.cornerRadiusDp.dp
                .toPx()
                .coerceAtMost(minOf(bounds.width, bounds.height) / 2)
        drawRoundRect(
            color = Color((config.color or 0xFF000000L).toInt()).copy(alpha = config.alpha),
            topLeft = offset,
            size = maskSize,
            cornerRadius = CornerRadius(radius),
        )
        if (editing) {
            drawRoundRect(
                color = Color.Cyan,
                topLeft = offset,
                size = maskSize,
                cornerRadius = CornerRadius(radius),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

/**
 * 临时占用遥控器焦点的编辑层，必须放在 Controller 外部以隔离快进和自定义快捷键。
 * 方向键每次调整 1%；确定/菜单切换移动与缩放，返回松开时保存退出，避免按键穿透。
 */
@Composable
fun ScreenMaskEditor(
    config: ScreenMaskConfig,
    onChange: (ScreenMaskConfig) -> Unit,
    onFinish: () -> Unit,
) {
    var resize by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    BackHandler(onBack = onFinish)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag("screen_mask_editor")
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    // 消费整对按键；模式切换仅首次按下触发，方向键允许长按重复。
                    if (event.key == Key.Back && event.type == KeyEventType.KeyUp) {
                        onFinish()
                    } else if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionLeft -> onChange(config.adjusted(-0.01f, 0f, resize))
                            Key.DirectionRight -> onChange(config.adjusted(0.01f, 0f, resize))
                            Key.DirectionUp -> onChange(config.adjusted(0f, -0.01f, resize))
                            Key.DirectionDown -> onChange(config.adjusted(0f, 0.01f, resize))
                            Key.DirectionCenter, Key.Enter, Key.Menu, Key(763) -> {
                                if (event.nativeKeyEvent.repeatCount == 0) resize = !resize
                            }
                            else -> Unit
                        }
                    }
                    true
                }.focusable(),
    ) {
        Text(
            text =
                "画面遮挡 · ${if (resize) "缩放模式" else "移动模式"}\n" +
                    "方向键：${if (resize) "左右调宽度，上下调高度" else "移动"}  ·  确定/菜单：切换  ·  返回：保存退出",
            color = Color.White,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(12.dp),
        )
    }
}
