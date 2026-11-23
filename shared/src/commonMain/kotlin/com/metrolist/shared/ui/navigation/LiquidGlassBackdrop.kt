package com.metrolist.shared.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * Shared backdrop captured from the Home screen content and sampled by floating glass controls.
 */
val LocalLiquidGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }
