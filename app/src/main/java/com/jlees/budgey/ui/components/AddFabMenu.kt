package com.jlees.budgey.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/*
 * Every "add" button in the app looks the same: an extended FAB with a + and what it adds
 * ("+ Purchase", "+ Subscription", "+ Budget", "+ Category"…).
 */

/** The standard "+ Thing" button. [secondary] is the tonal style for a second, less-used add button. */
@Composable
fun AddFab(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Rounded.Add, null) },
        text = { Text(label) },
        modifier = modifier.semantics { contentDescription = "Add ${label.lowercase()}" },
        containerColor = if (secondary) MaterialTheme.colorScheme.secondaryContainer else FloatingActionButtonDefaults.containerColor,
        contentColor = if (secondary) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

/**
 * "+ Purchase" / "+ Subscription": exactly the same button (and position) as [AddFab]; tapping it
 * fans out "Scan / Import" and "Enter manually" above it. Scan / Import opens the system chooser
 * (camera or photos), see [rememberImageSource].
 */
@Composable
fun AddFabMenu(
    label: String,
    onManual: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    manualLabel: String = "Enter manually",
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(expanded) { expanded = false }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            visible = expanded,
            enter = if (LocalAnimations.current) fadeIn() + expandVertically(expandFrom = Alignment.Bottom) else EnterTransition.None,
            exit = if (LocalAnimations.current) fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom) else ExitTransition.None,
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuChoice(Icons.Rounded.DocumentScanner, "Scan / Import") { expanded = false; onScan() }
                MenuChoice(Icons.Rounded.Edit, manualLabel) { expanded = false; onManual() }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { expanded = !expanded },
            icon = { Icon(if (expanded) Icons.Rounded.Close else Icons.Rounded.Add, null) },
            text = { Text(if (expanded) "Close" else label) },
            modifier = Modifier.semantics { contentDescription = if (expanded) "Close menu" else "Add ${label.lowercase()}" },
        )
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
