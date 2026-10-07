package com.flactify.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun constrainedArtworkSize(availableWidth: Dp, availableHeight: Dp): Dp =
    minOf(
        300.dp,
        availableWidth.coerceAtLeast(0.dp),
        (availableHeight * 0.42f).coerceAtLeast(0.dp)
    )
