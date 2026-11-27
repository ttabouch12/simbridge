package io.github.simbridge.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.simbridge.R

/*
 * SimBridge design language (see docs/DESIGN.md):
 * - Material 3 with the phone's own colours (Material You); status colours only for state.
 * - Four places at the bottom: Messages, Calls, SIM phone, Settings. Everything else is one level
 *   down, opened from a list row, with a back arrow.
 * - Lists are grouped: rows of one topic sit together in one rounded container (as in Android's
 *   own Settings), 2 dp apart, with a short label above the group.
 * - Things you switch are tiles (like Quick Settings): filled when on, plain when off, a spinner
 *   while the SIM phone is working on it.
 * - Spacing: 16 dp page margins, 8 dp inside groups, 24 dp between groups.
 */
object Space {
    val page = 16.dp
    val group = 24.dp
    val item = 8.dp
}

/** Meaning colours that work in light and dark mode. */
object Status {
    @Composable fun ok(): Color = if (MaterialTheme.colorScheme.isDark()) Color(0xFF81C995) else Color(0xFF1E8E3E)
    @Composable fun warn(): Color = if (MaterialTheme.colorScheme.isDark()) Color(0xFFFDD663) else Color(0xFFB06000)
    @Composable fun bad(): Color = MaterialTheme.colorScheme.error
}

private fun androidx.compose.material3.ColorScheme.isDark() = (surface.red + surface.green + surface.blue) / 3 < 0.5f

/** A second-level page: title, back arrow, optional actions, scrolling content with page margins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { onBack?.let { IconButton(it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
                .padding(horizontal = Space.page).padding(bottom = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.item),
            content = content,
        )
    }
}

/** Label above a group of rows or tiles. */
@Composable
fun GroupLabel(text: String) = Text(
    text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 4.dp, top = Space.group - Space.item, bottom = 2.dp),
)

/**
 * Rows of one topic in a rounded container; each child is drawn with [GroupRow] (or anything
 * else) and gets the right corners: big at the ends of the group, small between.
 */
@Composable
fun Group(label: String? = null, rows: List<@Composable () -> Unit>) {
    if (label != null) GroupLabel(label)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, row ->
            val top = if (i == 0) 20.dp else 4.dp
            val bottom = if (i == rows.lastIndex) 20.dp else 4.dp
            Surface(shape = RoundedCornerShape(top, top, bottom, bottom), color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()) { row() }
        }
    }
}

/** One row: tinted icon, title, optional summary, optional trailing content (or a chevron when it opens a page). */
@OptIn(ExperimentalFoundationApi::class)
/** Material motion: emphasized easing curves (M3 motion tokens). */
object Motion {
    val emphasizedDecelerate = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val pressSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.6f, stiffness = 800f)
}

/** Shrinks a little while pressed and springs back: quick tactile feedback on rows and tiles. */
@Composable
fun Modifier.pressScale(src: androidx.compose.foundation.interaction.MutableInteractionSource, to: Float = 0.97f): Modifier {
    val pressed by src.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) to else 1f, Motion.pressSpring, label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun GroupRow(
    icon: ImageVector?,
    title: String,
    summary: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    opens: Boolean = onClick != null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).pressScale(src, 0.98f)
            .let { if (onClick != null && enabled) it.combinedClickable(src, androidx.compose.material3.ripple(), onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f * alpha)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
        else if (opens) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A Quick Settings style tile: filled when on, a spinner while the SIM phone works on it.
 * Tap runs [onClick]; long-press opens details when [onLongClick] is given.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(
    icon: ImageVector,
    label: String,
    state: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
            on -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }, label = "tile")
    val fg = if (on && enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    // Expressive press: the tile squares its corners and shrinks a touch, then springs back.
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val corner by androidx.compose.animation.core.animateDpAsState(if (pressed) 12.dp else if (on) 28.dp else 24.dp,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 800f), label = "corner")
    Row(
        modifier.heightIn(min = 72.dp).pressScale(src, 0.96f).clip(RoundedCornerShape(corner)).background(bg)
            .combinedClickable(src, androidx.compose.material3.ripple(), enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape)
            .background(if (on && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center) {
            if (busy) Spinner(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
            else Icon(icon, null, Modifier.size(22.dp),
                tint = if (on && enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = if (enabled) 1f else 0.5f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = if (enabled) 0.8f else 0.4f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Tiles two per row. */
@Composable
fun TileGrid(tiles: List<@Composable (Modifier) -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.item)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.item)) {
                pair.forEach { it(Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A small rounded label for a state: connected, offline, roaming. */
@Composable
fun StatusPill(text: String, color: Color) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A plain card for free-form content inside a page (charts, maps, dial pad). */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun VSpace(h: androidx.compose.ui.unit.Dp = Space.item) = Spacer(Modifier.height(h))
