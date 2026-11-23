/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.highlight.Highlight
import com.metrolist.shared.ui.navigation.LocalLiquidGlassBackdrop

private val FloatingBarElementSize = 48.dp
private val FloatingBarElementShadow = 4.dp

@Composable
private fun liquidGlassModifier(
    backdrop: LayerBackdrop?,
    enabled: Boolean,
): Modifier {
    val glassSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f)
    return if (enabled && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                colorControls(saturation = 1f)
                blur(2.dp.toPx())
                lens(18.dp.toPx(), 48.dp.toPx(), depthEffect = false)
            },
            highlight = { Highlight.Default },
            shadow = null,
            onDrawSurface = {
                drawRect(glassSurfaceColor)
            },
        )
    } else {
        Modifier
    }
}

/** Modifier that makes any 48dp action (such as the account button) look like a floating circle. */
@Composable
fun Modifier.floatingBarCircle(liquidGlass: Boolean = false): Modifier {
    val backdrop = LocalLiquidGlassBackdrop.current
    val useBackdropGlass = liquidGlass && backdrop != null
    return this
        .then(liquidGlassModifier(backdrop, liquidGlass))
        .size(FloatingBarElementSize)
        .then(if (useBackdropGlass) Modifier else Modifier.shadow(FloatingBarElementShadow, CircleShape))
        .background(
            if (useBackdropGlass) Color.Transparent
            else if (liquidGlass) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.76f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            CircleShape,
        )
        .then(if (liquidGlass && !useBackdropGlass) Modifier.border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.42f), MaterialTheme.colorScheme.outline.copy(alpha = 0.14f), Color.White.copy(alpha = 0.08f))), CircleShape) else Modifier)
}

/**
 * Icon button used by the top bar. Renders as a plain [IconButton] in the classic top bar,
 * and as a separate floating circle in the floating top bar.
 */
@Composable
fun TopBarIconButton(
    onClick: () -> Unit,
    floating: Boolean,
    liquidGlass: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (floating) {
        val backdrop = LocalLiquidGlassBackdrop.current
        val useBackdropGlass = liquidGlass && backdrop != null
        FilledTonalIconButton(
            onClick = onClick,
            modifier =
                modifier
                    .then(liquidGlassModifier(backdrop, liquidGlass))
                    .size(FloatingBarElementSize)
                    .then(if (useBackdropGlass) Modifier else Modifier.shadow(FloatingBarElementShadow, CircleShape))
                    .then(if (liquidGlass && !useBackdropGlass) Modifier.border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.42f), MaterialTheme.colorScheme.outline.copy(alpha = 0.14f), Color.White.copy(alpha = 0.08f))), CircleShape) else Modifier),
            shape = CircleShape,
            colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (useBackdropGlass) Color.Transparent else if (liquidGlass) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.76f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            content = content,
        )
    } else {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            shapes = IconButtonDefaults.shapes(),
            content = content,
        )
    }
}

/**
 * Floating top bar: a rounded title pill followed by separate circular action buttons,
 * all floating above the content.
 */
@Composable
fun FloatingTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
    liquidGlass: Boolean = false,
) {
    val backdrop = LocalLiquidGlassBackdrop.current
    val useBackdropGlass = liquidGlass && backdrop != null
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets.exclude(WindowInsets.captionBar))
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.then(liquidGlassModifier(backdrop, liquidGlass)).weight(1f).height(FloatingBarElementSize).then(if (liquidGlass && !useBackdropGlass) Modifier.border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.42f), MaterialTheme.colorScheme.outline.copy(alpha = 0.14f), Color.White.copy(alpha = 0.08f))), CircleShape) else Modifier),
            shape = CircleShape,
            color = if (useBackdropGlass) Color.Transparent else if (liquidGlass) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.76f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = if (useBackdropGlass) 0.dp else FloatingBarElementShadow,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                title()
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    Row(modifier = Modifier.padding(start = 8.dp)) { subtitle() }
                }
            }
        }
        actions()
    }
}
