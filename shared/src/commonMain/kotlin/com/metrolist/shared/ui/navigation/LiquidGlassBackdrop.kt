package com.metrolist.shared.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * Shared backdrop captured from app content and sampled by Liquid Glass navigation controls.
 */
val LocalLiquidGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }
