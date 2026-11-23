@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.components.focusOutline

val LocalFloatingNavBar = compositionLocalOf { false }

private val FloatingBarHeight = 64.dp
private val FloatingItemHeight = 48.dp
private val FloatingBarShadow = 6.dp
private val NavSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 700f)

@Composable
internal fun <T> FloatingNavigation(
    items: List<AdaptiveNavigationItem<T>>,
    selectedKey: T?,
    onSelect: (T) -> Unit,
    navigationVisible: Boolean,
    separateKey: T?,
    itemModifier: @Composable (T) -> Modifier,
    onBarHeightChanged: (Dp) -> Unit,
    liquidGlass: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    AnimatedVisibility(
        visible = navigationVisible,
        modifier = modifier,
        enter =
            expandVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
                expandFrom = Alignment.Bottom,
            ),
        exit =
            shrinkVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
                shrinkTowards = Alignment.Bottom,
            ),
    ) {
        FloatingNavigationBar(
            items = items,
            selectedKey = selectedKey,
            onSelect = onSelect,
            separateKey = separateKey,
            itemModifier = itemModifier,
            liquidGlass = liquidGlass,
            modifier =
                Modifier.onSizeChanged { size ->
                    onBarHeightChanged(with(density) { size.height.toDp() })
                },
        )
    }
}

@Composable
private fun <T> FloatingNavigationBar(
    items: List<AdaptiveNavigationItem<T>>,
    selectedKey: T?,
    onSelect: (T) -> Unit,
    separateKey: T?,
    itemModifier: @Composable (T) -> Modifier,
    liquidGlass: Boolean,
    modifier: Modifier = Modifier,
) {
    val pillItems = items.filter { it.key != separateKey }
    val separateItem = items.firstOrNull { it.key == separateKey }
    val backdrop = LocalLiquidGlassBackdrop.current
    val useLiquidGlass = liquidGlass && backdrop != null
    val glassModifier =
        if (useLiquidGlass) {
            Modifier.drawBackdrop(
                backdrop = backdrop!!,
                shape = { CircleShape },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                    lens(24.dp.toPx(), 24.dp.toPx())
                },
                highlight = { Highlight.Default },
                onDrawSurface = {
                    drawRect(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.38f))
                },
            )
        } else {
            Modifier
        }

    Row(
        modifier =
            modifier
                .wrapContentWidth()
                .windowInsetsPadding(ShortNavigationBarDefaults.windowInsets)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = glassModifier.wrapContentWidth().height(FloatingBarHeight).then(if (liquidGlass && !useLiquidGlass) Modifier.border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.38f), MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), Color.White.copy(alpha = 0.08f))), CircleShape) else Modifier),
            shape = CircleShape,
            color = if (useLiquidGlass) Color.Transparent else if (liquidGlass) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.76f) else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = FloatingBarShadow,
        ) {
            Row(
                modifier = Modifier.wrapContentWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pillItems.forEach { item ->
                    FloatingNavigationItem(
                        item = item,
                        selected = item.key == selectedKey,
                        onClick = { onSelect(item.key) },
                        modifier = itemModifier(item.key),
                        liquidGlass = liquidGlass,
                    )
                }
            }
        }

        if (separateItem != null) {
            val selected = separateItem.key == selectedKey
            val container by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                label = "floatingSearchContainer",
            )
            val content by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                label = "floatingSearchContent",
            )
            Surface(
                onClick = { onSelect(separateItem.key) },
                modifier = Modifier.then(glassModifier).size(FloatingBarHeight).then(itemModifier(separateItem.key)).then(if (liquidGlass && !useLiquidGlass) Modifier.border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.42f), MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))), CircleShape) else Modifier),
                shape = CircleShape,
                color = if (useLiquidGlass) Color.Transparent else if (liquidGlass) container.copy(alpha = 0.78f) else container,
                contentColor = content,
                shadowElevation = FloatingBarShadow,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = if (selected) separateItem.selectedIcon else separateItem.icon,
                        contentDescription = separateItem.label,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> FloatingNavigationItem(
    item: AdaptiveNavigationItem<T>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    liquidGlass: Boolean = false,
) {
    val indicator by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (liquidGlass) 0.72f else 1f) else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0f),
        label = "floatingItemIndicator",
    )
    val contentColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "floatingItemContent",
    )
    Row(
        modifier =
            modifier
                .height(FloatingItemHeight)
                .clip(CircleShape)
                .background(indicator)
                .focusOutline(CircleShape)
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = Role.Tab,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                ).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + expandHorizontally(spring(dampingRatio = 0.9f, stiffness = 700f)),
            exit = fadeOut() + shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 700f)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = item.selectedIcon, contentDescription = null, tint = contentColor)
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(
            text = item.label,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
