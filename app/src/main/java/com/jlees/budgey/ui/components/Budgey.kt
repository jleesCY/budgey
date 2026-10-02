package com.jlees.budgey.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jlees.budgey.R

/** The little budgie (hugging a coin) from the app icon — used sparingly as a friendly accent. */
@Composable
fun BudgeyMark(modifier: Modifier = Modifier, size: Dp = 28.dp, contentDescription: String? = null) {
    Image(painterResource(R.drawable.ic_budgey_mark), contentDescription, modifier.size(size))
}
