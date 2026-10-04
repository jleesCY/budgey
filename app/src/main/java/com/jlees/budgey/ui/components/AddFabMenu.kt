package com.jlees.budgey.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * Every "add" button in the app looks the same: a round + button in the bottom corner. Screen
 * readers still hear what it adds ("Add purchase", "Add budget"…). When there's an unfinished
 * purchase / subscription, a separate ⟲ Resume button sits right beside it; together they form a pill.
 */

private val FabSize = 56.dp

/** The standard round + button. [label] is what it adds (read out by screen readers). */
@Composable
fun AddFab(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = modifier.semantics { contentDescription = "Add ${label.lowercase()}" },
        containerColor = if (secondary) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (secondary) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    ) { Icon(Icons.Rounded.Add, null) }
}

/**
 * The round + for purchases / subscriptions: tapping it fans out "Scan" and "Enter manually" above
 * it (and the + turns into an ×). Scan opens the system chooser (camera or photos), see
 * [rememberImageSource]. With [onResume] set, a separate Resume button appears beside it (together, a pill).
 */
@Composable
fun AddFabMenu(
    label: String,
    onManual: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    manualLabel: String = "Enter manually",
    /** Set when there's an unfinished one to pick up: shows the Resume button. */
    onResume: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(expanded) { expanded = false }
    val animate = LocalAnimations.current
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            visible = expanded,
            enter = if (animate) fadeIn() + expandVertically(expandFrom = Alignment.Bottom) else EnterTransition.None,
            exit = if (animate) fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom) else ExitTransition.None,
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuChoice(Icons.Rounded.DocumentScanner, "Scan") { expanded = false; onScan() }
                MenuChoice(Icons.Rounded.Edit, manualLabel) { expanded = false; onManual() }
            }
        }
        // Two separate buttons side by side: [ + ] [ ⟲ ]. Their outer ends are fully round and the
        // facing sides less so, so together they still read as a pill — with a clear gap between.
        val showResume = onResume != null && !expanded
        val turn by animateFloatAsState(if (expanded) 45f else 0f, if (animate) spring() else snap(), label = "fab-turn")
        val inner by animateDpAsState(if (showResume) 10.dp else FabSize / 2, if (animate) spring() else snap(), label = "fab-inner")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            FabButton(
                description = if (expanded) "Close menu" else "Add ${label.lowercase()}",
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(topStart = FabSize / 2, bottomStart = FabSize / 2, topEnd = inner, bottomEnd = inner),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                // + turns into × while the menu is open.
                Icon(Icons.Rounded.Add, null, Modifier.rotate(turn))
            }
            AnimatedVisibility(
                visible = showResume,
                enter = if (animate) fadeIn() + expandHorizontally(expandFrom = Alignment.Start) else EnterTransition.None,
                exit = if (animate) fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start) else ExitTransition.None,
            ) {
                FabButton(
                    description = "Resume the unfinished ${label.lowercase()}",
                    onClick = { onResume?.invoke() },
                    shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = FabSize / 2, bottomEnd = FabSize / 2),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) { Icon(Icons.Rounded.History, null) }
            }
        }
    }
}

/** A 56 dp FAB-style button with its own shadow and [shape]. */
@Composable
private fun FabButton(
    description: String,
    onClick: () -> Unit,
    shape: Shape,
    color: Color,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = color,
        contentColor = contentColor,
        shadowElevation = 6.dp,
        modifier = Modifier.size(FabSize).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun MenuChoice(icon: ImageVector, text: String, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(icon, null) },
        text = { Text(text) },
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}
