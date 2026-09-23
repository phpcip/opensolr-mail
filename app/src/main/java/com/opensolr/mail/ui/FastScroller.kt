package com.opensolr.mail.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import com.opensolr.mail.ui.theme.LocalPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val Corner = RoundedCornerShape(2.dp)
private val THUMB = 72.dp
private val LABEL_LIFT = 64.dp
private val TRACK = 36.dp
private val LABEL_ROOM = 260.dp
private const val SNAP_ITEMS = 4

/**
 * The shape of a lazy list for its fast scroller, built in the same order as the list's items:
 * [head] for a heading item, [row] for anything else. Every item carries the title it sits under.
 */
class ScrollIndex {
    val labels = ArrayList<String>()
    val heads = ArrayList<Boolean>()
    private var current = ""

    fun head(label: String) {
        current = label
        labels += label
        heads += true
    }

    /** A row; [label] gives it a title of its own (a flat list's day), otherwise it inherits the heading's. */
    fun row(label: String? = null) {
        if (label != null) current = label
        labels += current
        heads += false
    }

    fun rows(n: Int) = repeat(n) { row() }
}

/**
 * The fast scroller of Opensolr Photos for a lazy list: the thumb follows a map of the real heights
 * (headings and rows are learned apart), snaps to a nearby title, taps back at every title crossed,
 * and shows the title being passed next to the thumb.
 */
@Composable
fun BoxScope.FastScroller(state: LazyListState, index: ScrollIndex, minItems: Int = 25) {
    val p = LocalPalette.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var aimed by remember { mutableIntStateOf(-1) }
    var lastInto by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    val indexNow by rememberUpdatedState(index)
    val learned = remember { mutableStateMapOf<Boolean, Float>() }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { items ->
            val heads = indexNow.heads
            items.forEach { item ->
                val h = item.size.toFloat()
                if (h <= 0f || item.index >= heads.size) return@forEach
                val heading = heads[item.index]
                val old = learned[heading]
                learned[heading] = if (old == null) h else old * 0.9f + h * 0.1f
            }
        }
    }
    val n = index.labels.size
    val alpha by animateFloatAsState(
        targetValue = if (dragging || state.isScrollInProgress) 1f else 0f,
        animationSpec = tween(durationMillis = if (dragging) 0 else 450),
        label = "fastScrollerAlpha",
    )
    if (n < minItems) return

    // Heights stay frozen while dragging so the thumb never jumps under the finger.
    var heights by remember { mutableStateOf<Map<Boolean, Float>>(emptyMap()) }
    val learnedNow = learned.toMap()
    LaunchedEffect(learnedNow, dragging) { if (!dragging) heights = learnedNow }
    val density = LocalDensity.current
    val rowPx = heights[false] ?: with(density) { 72.dp.toPx() }
    val headPx = heights[true] ?: with(density) { 40.dp.toPx() }

    val tops = remember(index, n, rowPx, headPx) {
        val out = FloatArray(n + 1)
        var y = 0f
        for (i in 0 until n) {
            out[i] = y
            y += if (index.heads[i]) headPx else rowPx
        }
        out[n] = y
        out
    }
    // Where each title starts: a heading, or the first row of a new title in a flat list.
    val starts = remember(index, n) { BooleanArray(n) { i -> index.heads[i] || (i > 0 && index.labels[i] != index.labels[i - 1]) } }

    val viewportPx = state.layoutInfo.viewportSize.height.toFloat()
    val scrollablePx = (tops[n] - viewportPx).coerceAtLeast(1f)
    val topsNow by rememberUpdatedState(tops)
    val startsNow by rememberUpdatedState(starts)
    val scrollableNow by rememberUpdatedState(scrollablePx)
    val rowNow by rememberUpdatedState(rowPx)
    val fraction by remember(state) {
        derivedStateOf {
            val map = topsNow
            val last = map.size - 2
            if (last < 0) 0f else {
                val px = if (dragging && aimed >= 0) map[aimed.coerceIn(0, last)] + lastInto
                else map[state.firstVisibleItemIndex.coerceIn(0, last)] + state.firstVisibleItemScrollOffset
                (px / scrollableNow).coerceIn(0f, 1f)
            }
        }
    }

    BoxWithConstraints(Modifier.matchParentSize()) {
        val travelPx = with(density) { (maxHeight - THUMB).toPx() }
        val halfThumb = with(density) { THUMB.toPx() } / 2f
        val liftPx = with(density) { LABEL_LIFT.toPx() }

        fun aimAt(y: Float) {
            val map = topsNow
            val last = map.size - 2
            if (last < 0) return
            val marks = startsNow
            val labels = indexNow.labels
            val at = if (travelPx <= 0f) 0f else ((y - halfThumb) / travelPx).coerceIn(0f, 1f)
            val wanted = at * scrollableNow
            var lo = 0
            var hi = last
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (map[mid] <= wanted) lo = mid else hi = mid - 1
            }
            var target = lo
            ((target - SNAP_ITEMS).coerceAtLeast(0)..(target + SNAP_ITEMS).coerceAtMost(last))
                .filter { marks.getOrElse(it) { false } }
                .minByOrNull { abs(map[it] - wanted) }
                ?.let { if (abs(map[it] - wanted) <= rowNow) target = it }
            val into = if (target == lo) (wanted - map[target]).coerceAtLeast(0f) else 0f
            if (target == aimed && abs(into - lastInto) < 2f) return
            if (aimed >= 0 && target != aimed) {
                val from = minOf(aimed, target) + 1
                val to = maxOf(aimed, target)
                var crossed = 0
                for (i in from..to) if (marks.getOrElse(i) { false }) crossed++
                if (crossed > 0 && labels.getOrNull(aimed) != labels.getOrNull(target)) Haptics.tick(view, crossed > 1)
            }
            lastInto = into
            aimed = target
            job?.cancel()
            job = scope.launch { state.scrollToItem(target, into.roundToInt()) }
        }

        // Only the thumb takes the finger, and only while it shows: the rest of the right edge stays the rows',
        // so a swipe that starts at the edge of the screen reaches the row under it.
        val thumbTop = travelPx * fraction
        val top by androidx.compose.runtime.rememberUpdatedState(thumbTop)
        if (alpha > 0.05f || dragging) Box(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbTop.roundToInt()) }.width(TRACK).height(THUMB).pointerInput(travelPx) {
                detectVerticalDragGestures(
                    onDragStart = { o -> dragging = true; aimed = -1; Haptics.tick(view, false); aimAt(top + o.y) },
                    onDragEnd = { dragging = false; aimed = -1 },
                    onDragCancel = { dragging = false; aimed = -1 },
                    onVerticalDrag = { change, _ -> change.consume(); aimAt(top + change.position.y) },
                )
            },
        )
        Thumb(thumbTop, alpha, dragging)
        if (dragging && aimed >= 0) {
            Bubble(index.labels.getOrNull(aimed).orEmpty(), (travelPx * fraction - liftPx).coerceAtLeast(0f))
        }
    }
}

/** Titles of a scrolling column, each at its offset in the content; filled by [scrollMark]. */
class ScrollMarks {
    val at = mutableStateMapOf<Any, Pair<Int, String>>()
}

/** The marks of the column being built, so a [Zone] or a header registers itself without wiring. */
val LocalScrollMarks = compositionLocalOf<ScrollMarks?> { null }

/** Records this child of a scrolling column as a title at its position in the content. */
fun Modifier.scrollMark(marks: ScrollMarks?, key: Any, label: String): Modifier =
    if (marks == null) this else onGloballyPositioned { marks.at[key] = it.positionInParent().y.roundToInt() to label }

/** The same scroller for a scrolling column: snaps to and taps at every title in [marks]. */
@Composable
fun BoxScope.FastScroller(state: ScrollState, marks: ScrollMarks, minScreens: Float = 2f) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var aimedPx by remember { mutableIntStateOf(-1) }
    val alpha by animateFloatAsState(
        targetValue = if (dragging || state.isScrollInProgress) 1f else 0f,
        animationSpec = tween(durationMillis = if (dragging) 0 else 450),
        label = "columnScrollerAlpha",
    )
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.matchParentSize()) {
        val viewPx = with(density) { maxHeight.toPx() }
        if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE || state.maxValue < viewPx * (minScreens - 1f)) return@BoxWithConstraints
        val travelPx = with(density) { (maxHeight - THUMB).toPx() }
        val halfThumb = with(density) { THUMB.toPx() } / 2f
        val liftPx = with(density) { LABEL_LIFT.toPx() }
        val snapPx = with(density) { 48.dp.toPx() }
        val sorted = marks.at.values.sortedBy { it.first }

        fun titleAt(px: Int): String = sorted.lastOrNull { it.first <= px + 1 }?.second.orEmpty()

        fun aimAt(y: Float) {
            val at = if (travelPx <= 0f) 0f else ((y - halfThumb) / travelPx).coerceIn(0f, 1f)
            var wanted = (at * state.maxValue).roundToInt()
            sorted.minByOrNull { abs(it.first - wanted) }?.let { if (abs(it.first - wanted) <= snapPx) wanted = it.first.coerceAtMost(state.maxValue) }
            if (wanted == aimedPx) return
            if (aimedPx >= 0) {
                val lo = minOf(aimedPx, wanted)
                val hi = maxOf(aimedPx, wanted)
                val crossed = sorted.count { it.first in (lo + 1)..hi }
                if (crossed > 0) Haptics.tick(view, crossed > 1)
            }
            aimedPx = wanted
            scope.launch { state.scrollTo(wanted) }
        }

        val px = if (dragging && aimedPx >= 0) aimedPx else state.value
        val thumbY = travelPx * px / state.maxValue.coerceAtLeast(1)
        // Only the thumb takes the finger, and only while it shows.
        val top by androidx.compose.runtime.rememberUpdatedState(thumbY)
        if (alpha > 0.05f || dragging) Box(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY.roundToInt()) }.width(TRACK).height(THUMB).pointerInput(travelPx) {
                detectVerticalDragGestures(
                    onDragStart = { o -> dragging = true; aimedPx = -1; Haptics.tick(view, false); aimAt(top + o.y) },
                    onDragEnd = { dragging = false; aimedPx = -1 },
                    onDragCancel = { dragging = false; aimedPx = -1 },
                    onVerticalDrag = { change, _ -> change.consume(); aimAt(top + change.position.y) },
                )
            },
        )
        Thumb(thumbY, alpha, dragging)
        if (dragging) {
            val title = titleAt(px)
            if (title.isNotBlank()) Bubble(title, (thumbY - liftPx).coerceAtLeast(0f))
        }
    }
}

@Composable
private fun BoxScope.Thumb(y: Float, alpha: Float, dragging: Boolean) {
    val p = LocalPalette.current
    Box(
        Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y.roundToInt()) }
            .padding(end = 4.dp).size(width = 16.dp, height = THUMB).alpha(alpha)
            .background(if (dragging) p.accentFill else p.ink, Corner)
            .border(1.dp, if (dragging) p.accentFill else p.paper, Corner),
    )
}

@Composable
private fun BoxScope.Bubble(text: String, y: Float) {
    if (text.isBlank()) return
    val p = LocalPalette.current
    Box(
        Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y.roundToInt()) }
            .padding(end = TRACK + 4.dp).widthIn(max = LABEL_ROOM - TRACK - 8.dp)
            .background(p.accentFill, Corner).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.onAccentFill, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
