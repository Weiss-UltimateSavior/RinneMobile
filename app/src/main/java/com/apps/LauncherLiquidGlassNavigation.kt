package com.apps

import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.core.R
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs
import kotlin.math.roundToInt

private data class LiquidGlassNavItem(
    @param:DrawableRes val icon: Int,
    val label: Int,
)

private val liquidGlassNavItems = listOf(
    LiquidGlassNavItem(R.drawable.launcher_nav_home, R.string.core_home),
    LiquidGlassNavItem(R.drawable.launcher_nav_game, R.string.core_games),
    LiquidGlassNavItem(R.drawable.launcher_nav_manage, R.string.core_manage),
    LiquidGlassNavItem(R.drawable.launcher_nav_account, R.string.core_account),
)

@Composable
fun LauncherComposeBackground(backgroundColor: Int) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(backgroundColor)),
    )
}

/**
 * Hosts the existing View launcher as a recorded Backdrop and draws the optional glass navigation
 * above it. This keeps the rest of the launcher on its established XML/View implementation.
 */
@Composable
fun LauncherLiquidGlassHost(
    launcherRoot: View,
    selectedIndex: Int,
    darkMode: Boolean,
    primaryColor: Int,
    @DrawableRes landscapeIcon: Int,
    onItemClick: (Int) -> Unit,
    onLandscapeClick: () -> Unit,
) {
    val backdrop = remember(launcherRoot) { AndroidViewBackdrop(launcherRoot) }

    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidGlassBottomNavigation(
                selectedIndex = selectedIndex,
                backdrop = backdrop,
                darkMode = darkMode,
                primaryColor = Color(primaryColor),
                onItemClick = onItemClick,
                modifier = Modifier.weight(1f),
            )
            LiquidGlassLandscapeButton(
                backdrop = backdrop,
                darkMode = darkMode,
                primaryColor = Color(primaryColor),
                icon = landscapeIcon,
                onClick = onLandscapeClick,
            )
        }
    }
}

@Composable
private fun LiquidGlassLandscapeButton(
    backdrop: Backdrop,
    darkMode: Boolean,
    primaryColor: Color,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val surfaceColor = if (darkMode) Color(0xFF171919) else Color.White

    Box(
        modifier = Modifier
            .size(64.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    vibrancy()
                    blur(with(density) { 8.dp.toPx() })
                    lens(
                        with(density) { 18.dp.toPx() },
                        with(density) { 22.dp.toPx() },
                    )
                },
                highlight = { Highlight.Default.copy(alpha = 0.78f) },
                shadow = { Shadow.Default.copy(alpha = 0.8f) },
                onDrawSurface = { drawRect(surfaceColor.copy(alpha = 0.44f)) },
            )
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = stringResource(R.string.core_landscape_mode_title),
            modifier = Modifier.size(29.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(primaryColor),
        )
    }
}

/** Draws the already attached XML launcher into the glass effect's off-screen layer. */
private class AndroidViewBackdrop(
    private val source: View,
) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val targetCoordinates = coordinates ?: return
        val targetInWindow = targetCoordinates.positionInWindow()
        val sourceInWindow = IntArray(2)
        source.getLocationInWindow(sourceInWindow)
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            val checkpoint = nativeCanvas.save()
            nativeCanvas.translate(
                sourceInWindow[0] - targetInWindow.x,
                sourceInWindow[1] - targetInWindow.y,
            )
            source.draw(nativeCanvas)
            nativeCanvas.restoreToCount(checkpoint)
        }
    }
}

@Composable
private fun LiquidGlassBottomNavigation(
    selectedIndex: Int,
    backdrop: Backdrop,
    darkMode: Boolean,
    primaryColor: Color,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val surfaceColor = if (darkMode) Color(0xFF171919) else Color.White
    val mutedColor = if (darkMode) Color(0xFFB6BFBB) else Color(0xFF63716B)
    val shape = RoundedCornerShape(32.dp)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnItemClick by rememberUpdatedState(onItemClick)
    var navigationWidth by remember { mutableIntStateOf(0) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var lastDragIndex by remember { mutableIntStateOf(-1) }
    val glassFocusWidth = 76.dp
    val glassFocusWidthPx = with(density) { glassFocusWidth.toPx() }
    val horizontalPaddingPx = with(density) { 8.dp.toPx() }
    val contentWidth = (navigationWidth - horizontalPaddingPx).coerceAtLeast(0f)
    val itemWidth = contentWidth / liquidGlassNavItems.size
    val focusTargetX = dragPosition?.let { position ->
        itemWidth * (position + 0.5f) - glassFocusWidthPx / 2f
    } ?: 0f
    val focusX by animateFloatAsState(
        targetValue = focusTargetX,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 700f,
        ),
        label = "liquidGlassDragFocus",
    )
    val movement = if (itemWidth > 0f) abs(focusTargetX - focusX) / itemWidth else 0f
    val stretch = movement.coerceIn(0f, 1f) * 0.42f

    fun updateSelectedFragment(position: Float) {
        val index = position
            .roundToInt()
            .coerceIn(liquidGlassNavItems.indices)
        if (index != lastDragIndex) {
            lastDragIndex = index
            if (index != currentSelectedIndex) currentOnItemClick(index)
        }
    }

    Box(
        modifier = modifier
            .height(64.dp)
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(with(density) { 8.dp.toPx() })
                    lens(
                        with(density) { 18.dp.toPx() },
                        with(density) { 22.dp.toPx() },
                    )
                },
                highlight = { Highlight.Default.copy(alpha = 0.72f) },
                shadow = { Shadow.Default.copy(alpha = 0.8f) },
                onDrawSurface = { drawRect(surfaceColor.copy(alpha = 0.44f)) },
            )
            .onSizeChanged { navigationWidth = it.width }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        if (itemWidth <= 0f) return@detectDragGesturesAfterLongPress
                        val index = ((position.x - horizontalPaddingPx / 2f) / itemWidth)
                            .toInt()
                            .coerceIn(liquidGlassNavItems.indices)
                        lastDragIndex = currentSelectedIndex
                        dragPosition = index.toFloat()
                        updateSelectedFragment(index.toFloat())
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (itemWidth > 0f) {
                            val position = ((dragPosition ?: currentSelectedIndex.toFloat()) +
                                dragAmount.x / itemWidth)
                                .coerceIn(0f, liquidGlassNavItems.lastIndex.toFloat())
                            dragPosition = position
                            updateSelectedFragment(position)
                        }
                    },
                    onDragEnd = {
                        dragPosition = null
                        lastDragIndex = -1
                    },
                    onDragCancel = {
                        dragPosition = null
                        lastDragIndex = -1
                    },
                )
            }
            .padding(horizontal = 4.dp),
    ) {
        if (dragPosition != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(glassFocusWidth)
                    .height(48.dp)
                    .graphicsLayer {
                        translationX = focusX
                        scaleX = 1f + stretch
                        scaleY = 1f - stretch * 0.25f
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            vibrancy()
                            blur(with(density) { 9.dp.toPx() })
                            lens(
                                with(density) { 22.dp.toPx() },
                                with(density) { 28.dp.toPx() },
                            )
                        },
                        highlight = { Highlight.Default.copy(alpha = 0.98f) },
                        shadow = { Shadow.Default.copy(alpha = 0.8f) },
                        onDrawSurface = {
                            drawRect(primaryColor.copy(alpha = if (darkMode) 0.28f else 0.18f))
                        },
                    ),
            )
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            liquidGlassNavItems.forEachIndexed { index, item ->
                LiquidGlassNavigationItem(
                    item = item,
                    selected = index == selectedIndex,
                    primaryColor = primaryColor,
                    mutedColor = mutedColor,
                    onClick = { onItemClick(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LiquidGlassNavigationItem(
    item: LiquidGlassNavItem,
    selected: Boolean,
    primaryColor: Color,
    mutedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor by animateColorAsState(
        targetValue = if (selected) primaryColor else mutedColor,
        label = "liquidGlassNavColor",
    )

    Box(
        modifier = modifier
            .height(56.dp)
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(25.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(item.icon),
            contentDescription = stringResource(item.label),
            modifier = Modifier.size(23.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(contentColor),
        )
    }
}
