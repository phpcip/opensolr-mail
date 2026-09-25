package com.opensolr.mail.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long press on a row, then drag up or down: every row between is selected, as in Opensolr Photos.
 * Near the top or bottom edge the list scrolls on its own. [order] is the item key of every selectable
 * row in the order the list shows them; items whose key is not in it (headings, cards) are passed over.
 */
fun Modifier.dragSelect(
    state: LazyListState,
    order: () -> List<Any>,
    onStart: (Any) -> Unit,
    onRange: (List<Any>) -> Unit,
    onEnd: () -> Unit,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    // The gesture outlives recompositions: it always calls the callbacks of the latest one, never the first.
    val orderNow by rememberUpdatedState(order)
    val startNow by rememberUpdatedState(onStart)
    val rangeNow by rememberUpdatedState(onRange)
    val endNow by rememberUpdatedState(onEnd)
    val at = remember { mutableStateOf<Offset?>(null) }
    val edgePx = with(LocalDensity.current) { EDGE.toPx() }
    val stepPx = with(LocalDensity.current) { STEP.toPx() }

    // The selectable row under the finger, or the nearest one above it (below it at the very top).
    fun keyAt(point: Offset, positions: Map<Any, Int>): Any? {
        val items = state.layoutInfo.visibleItemsInfo.filter { it.key in positions }
        if (items.isEmpty()) return null
        return (items.lastOrNull { it.offset <= point.y } ?: items.first()).key
    }

    this.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Only a finger held still for a long press starts it; any earlier move is a scroll or a swipe.
            val left = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (left != null) return@awaitEachGesture
            var keys = orderNow()
            var positions = HashMap<Any, Int>(keys.size * 2).apply { keys.forEachIndexed { i, k -> put(k, i) } }
            var seenTotal = state.layoutInfo.totalItemsCount
            val startKey = state.layoutInfo.visibleItemsInfo.firstOrNull { down.position.y >= it.offset && down.position.y < it.offset + it.size }?.key
            if (startKey == null || startKey !in positions) return@awaitEachGesture
            at.value = down.position
            startNow(startKey)
            var covered: Pair<Any, Any> = startKey to startKey

            fun spread(point: Offset) {
                // A page landed (or mail arrived) during the drag: the rows it brought join the range.
                if (state.layoutInfo.totalItemsCount != seenTotal) {
                    seenTotal = state.layoutInfo.totalItemsCount
                    keys = orderNow()
                    positions = HashMap<Any, Int>(keys.size * 2).apply { keys.forEachIndexed { i, k -> put(k, i) } }
                }
                val anchor = positions[startKey] ?: return
                val here = keyAt(point, positions)?.let { positions[it] } ?: return
                val from = minOf(anchor, here)
                val to = maxOf(anchor, here)
                if (covered == keys[from] to keys[to]) return
                covered = keys[from] to keys[to]
                rangeNow(keys.subList(from, to + 1).toList())
            }

            val scrolling = scope.launch {
                while (true) {
                    val point = at.value ?: break
                    val height = state.layoutInfo.viewportSize.height.toFloat()
                    val speed = when {
                        point.y < edgePx -> -(edgePx - point.y) / edgePx * stepPx
                        point.y > height - edgePx -> (point.y - (height - edgePx)) / edgePx * stepPx
                        else -> 0f
                    }
                    if (speed != 0f) {
                        state.scrollBy(speed)
                        spread(point)
                    }
                    delay(16)
                }
            }
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    at.value = change.position
                    spread(change.position)
                }
            } finally {
                scrolling.cancel()
                at.value = null
                endNow()
            }
        }
    }
}

/** A drag held this close to an edge scrolls the list, faster the closer it is. */
private val EDGE = 72.dp
private val STEP = 14.dp
