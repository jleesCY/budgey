package com.jlees.budgey.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Settings → Appearance → Animations. Two layers make "off" reliable:
 *  1. MainActivity runs the whole UI with an animation speed of 0, so every Compose animation
 *     (including Material components and ripples) jumps straight to its end;
 *  2. the app's own animations also check this flag and skip animating entirely.
 */
val LocalAnimations = staticCompositionLocalOf { true }

/** Show/expand: fade + grow, or nothing when animations are off. */
@Composable
@ReadOnlyComposable
fun appear(): EnterTransition = if (LocalAnimations.current) fadeIn() + expandVertically() else EnterTransition.None

/** Hide/collapse: fade + shrink, or nothing when animations are off. */
@Composable
@ReadOnlyComposable
fun disappear(): ExitTransition = if (LocalAnimations.current) fadeOut() + shrinkVertically() else ExitTransition.None
