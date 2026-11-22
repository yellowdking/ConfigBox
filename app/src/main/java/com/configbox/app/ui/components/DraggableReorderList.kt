package com.configbox.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * Lightweight, zero-dependency long-press drag-to-reorder state for Jetpack Compose LazyColumn.
 */
class DragReorderState(
    val listState: LazyListState,
    private val headerCount: Int
) {
    var draggedDataIndex by mutableStateOf<Int?>(null)
        private set

    var dragOffsetY by mutableFloatStateOf(0f)
        private set

    private var initialItemOffset: Int = 0
    private var initialItemSize: Int = 0

    fun onDragStart(pointerY: Float): Boolean {
        val hit: LazyListItemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            pointerY.toInt() in item.offset..(item.offset + item.size)
        } ?: return false

        val dataIdx = hit.index - headerCount
        if (dataIdx < 0) return false

        draggedDataIndex = dataIdx
        dragOffsetY = 0f
        initialItemOffset = hit.offset
        initialItemSize = hit.size
        return true
    }

    fun onDrag(deltaY: Float, itemCount: Int, onSwap: (from: Int, to: Int) -> Unit) {
        val currentIdx = draggedDataIndex ?: return
        dragOffsetY += deltaY

        val currentCenter = initialItemOffset + dragOffsetY + initialItemSize / 2f
        val visible = listState.layoutInfo.visibleItemsInfo

        // Check swap downward
        if (currentIdx < itemCount - 1) {
            val nextInfo = visible.firstOrNull { it.index == currentIdx + 1 + headerCount }
            if (nextInfo != null && currentCenter > nextInfo.offset + nextInfo.size / 2f) {
                onSwap(currentIdx, currentIdx + 1)
                draggedDataIndex = currentIdx + 1
                initialItemOffset += nextInfo.size
                dragOffsetY -= nextInfo.size
                return
            }
        }

        // Check swap upward
        if (currentIdx > 0) {
            val prevInfo = visible.firstOrNull { it.index == currentIdx - 1 + headerCount }
            if (prevInfo != null && currentCenter < prevInfo.offset + prevInfo.size / 2f) {
                onSwap(currentIdx, currentIdx - 1)
                draggedDataIndex = currentIdx - 1
                initialItemOffset -= prevInfo.size
                dragOffsetY += prevInfo.size
            }
        }
    }

    fun onDragEnd() {
        draggedDataIndex = null
        dragOffsetY = 0f
    }
}

@Composable
fun rememberDragReorderState(
    listState: LazyListState,
    headerCount: Int = 0
): DragReorderState {
    return remember(listState, headerCount) {
        DragReorderState(listState = listState, headerCount = headerCount)
    }
}

@Composable
fun Modifier.longPressReorderContainer(
    reorderState: DragReorderState,
    enabled: Boolean,
    itemCount: Int,
    onSwap: (from: Int, to: Int) -> Unit,
    onDragFinished: () -> Unit
): Modifier {
    val latestCount by rememberUpdatedState(itemCount)
    val latestOnSwap by rememberUpdatedState(onSwap)
    val latestOnFinished by rememberUpdatedState(onDragFinished)

    if (!enabled) return this

    return this.pointerInput(reorderState, enabled) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                reorderState.onDragStart(offset.y)
            },
            onDrag = { change, dragAmount ->
                change.consume()
                reorderState.onDrag(
                    deltaY = dragAmount.y,
                    itemCount = latestCount,
                    onSwap = latestOnSwap
                )
            },
            onDragEnd = {
                reorderState.onDragEnd()
                latestOnFinished()
            },
            onDragCancel = {
                reorderState.onDragEnd()
                latestOnFinished()
            }
        )
    }
}

fun Modifier.draggedItemVisual(
    isDragging: Boolean,
    dragOffsetY: Float
): Modifier {
    if (!isDragging) return this
    return this
        .zIndex(1f)
        .graphicsLayer {
            translationY = dragOffsetY
            scaleX = 1.02f
            scaleY = 1.02f
            shadowElevation = 14f
        }
}
