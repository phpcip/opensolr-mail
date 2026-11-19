package com.opensolr.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.opensolr.mail.ui.theme.LocalPalette

val DockShape = RoundedCornerShape(16.dp)

/** Every button that stands on its own. */
val ButtonShape = RoundedCornerShape(12.dp)

/** What lights up inside a dock while one of its actions is held. */
val DockCellShape = RoundedCornerShape(10.dp)

/** True for what is composed inside a [Dock]: a button there draws no frame of its own. */
val LocalDock = compositionLocalOf { false }

// the sheen every button and dock carries: lighter at the top, a touch darker at the bottom, kept faint
private const val SHEEN_TOP = 0.16f
private const val SHEEN_MID = 0.03f
private const val SHEEN_BOTTOM = 0.08f
private const val RIM_LIGHT = 0.55f

// how far a seam stays from the top and the bottom of the dock, as a share of its height
private const val SEAM_INSET = 0.24f

/** The faint light that falls on a glass surface, drawn over whatever fills it. */
fun Modifier.sheen(shape: Shape, strength: Float = 1f): Modifier = background(
    Brush.verticalGradient(
        0f to Color.White.copy(alpha = SHEEN_TOP * strength),
        0.5f to Color.White.copy(alpha = SHEEN_MID * strength),
        1f to Color.Black.copy(alpha = SHEEN_BOTTOM * strength),
    ),
    shape,
)

/** The rim of a glass surface: it catches the light at the top and fades into [edge]. */
fun rimBrush(edge: Color): Brush = Brush.verticalGradient(listOf(lerp(edge, Color.White, RIM_LIGHT), edge))

/** A glass band: the heading of a group or of a section that folds, and the box that narrows a list. */
fun Modifier.band(fill: Color, rim: Color, strength: Float = 1f): Modifier =
    clip(ButtonShape).background(fill).sheen(ButtonShape, strength).border(1.dp, rimBrush(rim), ButtonShape)

/**
 * One bar for a row of actions: a single rounded frame, a fine line between one action and the next.
 * With [fill] the actions share the width equally, otherwise each is as wide as it needs.
 */
@Composable
fun Dock(modifier: Modifier = Modifier, fill: Boolean = true, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val seams = remember { mutableStateOf(IntArray(0)) }
    val frame = modifier
        .clip(DockShape)
        .background(p.buttonFill)
        .sheen(DockShape)
        .border(1.dp, rimBrush(p.hairline), DockShape)
        .drawWithContent {
            drawContent()
            val inset = size.height * SEAM_INSET
            val stroke = 1.dp.toPx()
            seams.value.forEach { x -> drawLine(p.hairline, Offset(x.toFloat(), inset), Offset(x.toFloat(), size.height - inset), stroke) }
        }
    CompositionLocalProvider(LocalDock provides true) {
        Layout(content, frame) { measurables, constraints ->
            if (measurables.isEmpty()) return@Layout layout(0, 0) {}
            val count = measurables.size
            val shared = fill && constraints.hasBoundedWidth
            val placeables = if (shared) {
                val each = constraints.maxWidth / count
                val tall = measurables.maxOf { it.maxIntrinsicHeight(each) }.coerceIn(constraints.minHeight, constraints.maxHeight)
                // the last one takes the pixels the division left over
                measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(if (i == count - 1) constraints.maxWidth - each * (count - 1) else each, tall)) }
            } else {
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                measurables.map { it.measure(loose) }
            }
            val width = if (shared) constraints.maxWidth else placeables.sumOf { it.width }.coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = placeables.maxOf { it.height }
            val at = IntArray(count - 1)
            layout(width, height) {
                var x = 0
                placeables.forEachIndexed { i, placeable ->
                    if (i > 0) at[i - 1] = x
                    placeable.place(x, (height - placeable.height) / 2)
                    x += placeable.width
                }
                if (!at.contentEquals(seams.value)) seams.value = at
            }
        }
    }
}
