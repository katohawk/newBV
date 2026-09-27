package dev.frost819.newbv.app.ui.component.player.menu.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.frost819.newbv.app.ui.component.player.ifElse
import dev.frost819.newbv.app.ui.component.player.rememberPlayerFocusLoop

/**
 * 单选菜单列表。
 *
 * 使用 [LazyColumn] 渲染可选项，按 DirectionRight 返回父级。
 * 焦点恢复到当前选中项。
 *
 * @param modifier 修饰符
 * @param items 选项文本列表
 * @param selected 当前选中项索引
 * @param onSelectedChanged 选中变化回调
 * @param onFocusBackToParent 返回父级回调
 */
@Composable
fun RadioMenuList(
    modifier: Modifier = Modifier,
    items: List<String>,
    selected: Int = 0,
    onSelectedChanged: (index: Int) -> Unit,
    onFocusBackToParent: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    // 自定义值不在预设列表时不误选首项，但焦点仍需要有效的恢复节点。
    val fallbackIndex = selected.takeIf { it in items.indices } ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = fallbackIndex)
    val loop = rememberPlayerFocusLoop(items.size, scrollToItem = { listState.scrollToItem(it) })
    LazyColumn(
        state = listState,
        modifier =
            modifier
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyUp) {
                        if (listOf(Key.Enter, Key.DirectionCenter).contains(it.key)) {
                            return@onPreviewKeyEvent false
                        }
                        return@onPreviewKeyEvent true
                    }
                    val result = it.key == Key.DirectionRight
                    if (result) onFocusBackToParent()
                    result
                }.focusRestorer(focusRequester),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 120.dp, horizontal = 8.dp),
    ) {
        itemsIndexed(items) { index, item ->
            MenuListItem(
                modifier =
                    Modifier
                        .width(200.dp)
                        .then(loop(index == 0, index == items.lastIndex))
                        .ifElse(fallbackIndex == index, Modifier.focusRequester(focusRequester)),
                text = item,
                selected = selected == index,
                onClick = { onSelectedChanged(index) },
            )
        }
    }
}
