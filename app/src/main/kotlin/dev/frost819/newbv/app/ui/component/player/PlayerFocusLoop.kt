package dev.frost819.newbv.app.ui.component.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch

/**
 * 播放器选项列表的首尾焦点循环；中间项和垂直于列表的方向仍走原导航。
 *
 * @param itemCount 列表项数，空列表不处理输入
 * @param horizontal 横向列表使用左右键，否则使用上下键
 * @param scrollToItem Lazy 列表在请求焦点前滚动，使另一端的节点完成组合
 * @return 给首项/末项绑定的修饰符；分集含展开子项时，末端应绑定到最后一个子项
 */
@Composable
fun rememberPlayerFocusLoop(
    itemCount: Int,
    horizontal: Boolean = false,
    scrollToItem: suspend (Int) -> Unit = {},
): (isFirst: Boolean, isLast: Boolean) -> Modifier {
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var wrapping by remember { mutableStateOf(false) }
    return { isFirst, isLast ->
        var modifier: Modifier = Modifier
        if (isFirst) modifier = modifier.focusRequester(first)
        if (isLast) modifier = modifier.focusRequester(last)
        modifier.onPreviewKeyEvent { event ->
            val previous = if (horizontal) Key.DirectionLeft else Key.DirectionUp
            val next = if (horizontal) Key.DirectionRight else Key.DirectionDown
            val toLast = isFirst && event.key == previous
            val toFirst = isLast && event.key == next
            if (itemCount == 0 || (!toLast && !toFirst)) {
                false
            } else {
                if (event.type == KeyEventType.KeyDown && !wrapping && itemCount > 1) {
                    wrapping = true
                    scope.launch {
                        try {
                            scrollToItem(if (toLast) itemCount - 1 else 0)
                            // Lazy 列表另一端可能尚未挂载，不能直接向离屏节点 requestFocus。
                            withFrameNanos { }
                            runCatching { (if (toLast) last else first).requestFocus() }
                        } finally {
                            wrapping = false
                        }
                    }
                }
                true
            }
        }
    }
}
