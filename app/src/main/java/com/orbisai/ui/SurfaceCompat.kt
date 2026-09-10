package com.orbisai.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compatibility overloads for the office UI. */
@Composable
fun Surface(
    shape: RoundedCornerShape,
    color: Color,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.Surface(
        shape = shape,
        color = color,
        shadowElevation = shadowElevation,
        content = content
    )
}

@Composable
fun Surface(
    modifier: Modifier,
    shape: Shape,
    color: Color,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        shadowElevation = shadowElevation,
        content = content
    )
}
