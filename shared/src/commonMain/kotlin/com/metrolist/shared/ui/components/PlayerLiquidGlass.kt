package com.metrolist.shared.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.navigation.LocalLiquidGlassBackdrop

val LocalLyricsGlassCanvasAvailable = compositionLocalOf { true }

/**
 * Uses the app's captured backdrop and the same lens configuration as the liquid-glass navigation.
 * When the setting is disabled or no backdrop is available, the original control surface is retained.
 */
@Composable
fun Modifier.playerLiquidGlass(
    shape: Shape,
    surfaceColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f),
): Modifier {
    val backdrop = LocalLiquidGlassBackdrop.current
    if (backdrop == null || !Settings.shared.liquidGlassEnabled || !LocalLyricsGlassCanvasAvailable.current) return this

    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1.4f)
            blur(2.dp.toPx())
            lens(23.dp.toPx(), 64.dp.toPx(), depthEffect = false)
        },
        highlight = { Highlight.Default },
        shadow = null,
        onDrawSurface = { drawRect(surfaceColor) },
    )
}

@Composable
fun playerLiquidGlassEnabled(): Boolean =
    Settings.shared.liquidGlassEnabled && LocalLiquidGlassBackdrop.current != null

@Composable
fun playerLiquidGlassContainerColor(fallbackColor: Color): Color =
    if (playerLiquidGlassEnabled()) Color.Transparent else fallbackColor
