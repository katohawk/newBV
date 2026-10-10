package dev.frost819.newbv.app.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/**
 * 封装了 TV 焦点定轴逻辑的 [LazyVerticalGrid]。
 *
 * TV 端 D-Pad 导航时，聚焦的 item 会自动滚动到屏幕上方 [pivotFraction] 比例处，
 * 避免焦点被顶部/底部遮挡。默认 0.3f（屏幕上方 30%），符合 TV 端习惯。
 *
 * @param pivotFraction 焦点 item 在屏幕上的停留位置比例 (0.0 - 1.0)。
 * @param onHorizontalBoundary 在行边界横移时切换页面，参数为 -1（左）或 1（右）。null 保留默认导航。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(0.dp),
    pivotFraction: Float = 0.3f,
    onHorizontalBoundary: ((Int) -> Unit)? = null,
    content: LazyGridScope.() -> Unit,
) {
    var gridCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var focusedCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val bringIntoViewSpec =
        remember(pivotFraction) {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float,
                ): Float {
                    val targetPosition = containerSize * pivotFraction
                    return offset - targetPosition
                }
            }
        }

    CompositionLocalProvider(
        LocalBringIntoViewSpec provides bringIntoViewSpec,
    ) {
        LazyVerticalGrid(
            columns = columns,
            modifier =
                modifier
                    .onGloballyPositioned { gridCoordinates = it }
                    .onFocusedBoundsChanged { focusedCoordinates = it }
                    .onKeyEvent { event ->
                        val move = onHorizontalBoundary ?: return@onKeyEvent false
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val step =
                            when (event.key) {
                                Key.DirectionLeft -> -1
                                Key.DirectionRight -> 1
                                else -> return@onKeyEvent false
                            }
                        val grid = gridCoordinates?.takeIf { it.isAttached } ?: return@onKeyEvent false
                        val focused = focusedCoordinates?.takeIf { it.isAttached } ?: return@onKeyEvent false
                        val bounds = grid.localBoundingBoxOf(focused, clipBounds = false)
                        val items = state.layoutInfo.visibleItemsInfo
                        val item =
                            items.firstOrNull {
                                bounds.center.x >= it.offset.x &&
                                    bounds.center.x < it.offset.x + it.size.width &&
                                    bounds.center.y >= it.offset.y &&
                                    bounds.center.y < it.offset.y + it.size.height
                            } ?: return@onKeyEvent false
                        // shortcut: 以整卡宽度区分操作按钮；若新增接近整卡宽的按钮，改为显式标记焦点角色。
                        if (bounds.width < item.size.width * 0.9f) return@onKeyEvent false
                        val lastColumn = items.filter { it.row == item.row }.maxOf { it.column }
                        if (isGridTabBoundary(item.column, lastColumn, step)) {
                            move(step)
                            true
                        } else {
                            false
                        }
                    },
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
    }
}

/** 判断当前格子是否处于横移方向的行边界，包括未满的一行。 */
internal fun isGridTabBoundary(
    column: Int,
    lastColumn: Int,
    step: Int,
): Boolean = (step == -1 && column == 0) || (step == 1 && column == lastColumn)
