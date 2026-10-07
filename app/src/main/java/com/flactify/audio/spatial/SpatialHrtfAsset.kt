package com.flactify.audio.spatial

import android.content.Context
import java.nio.ByteBuffer

/** Loads the pinned SOFA profile once, outside the steady-state audio processing path. */
internal object SpatialHrtfAsset {
    private const val ASSET_PATH = "hrtf/cipic_124.sofa"

    @Volatile
    private var cachedData: ByteBuffer? = null

    fun load(context: Context): ByteBuffer = cachedData ?: synchronized(this) {
        cachedData ?: context.applicationContext.assets.open(ASSET_PATH).use { input ->
            val bytes = input.readBytes()
            require(bytes.isNotEmpty()) { "Pinned CIPIC SOFA asset is empty" }
            ByteBuffer.allocateDirect(bytes.size)
                .put(bytes)
                .apply { flip() }
        }.also { cachedData = it }
    }
}
