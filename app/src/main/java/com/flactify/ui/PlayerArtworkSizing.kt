package com.flactify.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun constrainedArtworkSize(availableWidth: Dp, availableHeight: Dp): Dp =
    minOf(
        availableWidth.coerceAtLeast(0.dp),
        (availableHeight * 0.52f).coerceAtLeast(0.dp)
    )
