package com.flactify.audio.spatial

/**
 * Playback-thread signal for distinguishing final renderer EOS from internal Media3 drains.
 * The active AudioOutput is registered here so the sink can pump the native tail after Media3
 * has signalled AudioOutput.stop().
 */
internal class SpatialEosDrainState {
    @Volatile
    var isFinalEosDrain: Boolean = false
        private set

    @Volatile
    private var activeOutput: SpatialAudioOutput? = null

    @Volatile
    private var processedStreamChangePending = false

    fun beginFinalEosDrain() {
        beginRendererEosDrain(drainHrtfTail = true)
    }

    fun beginRendererEosDrain(drainHrtfTail: Boolean) {
        isFinalEosDrain = drainHrtfTail
    }

    fun pumpFinalEosTail(): Boolean =
        activeOutput?.pumpFinalEosTail() ?: true

    fun markProcessedStreamChange() {
        processedStreamChangePending = true
    }

    fun markPotentialDiscontinuity() {
        isFinalEosDrain = false
        val isProcessedStreamChange = processedStreamChangePending
        processedStreamChangePending = false
        activeOutput?.markPotentialDiscontinuity(isProcessedStreamChange)
    }

    fun resetForDiscontinuity() {
        isFinalEosDrain = false
        processedStreamChangePending = false
        activeOutput?.resetForDiscontinuity()
    }

    fun isFinalTailComplete(): Boolean =
        activeOutput?.isFinalTailComplete ?: true

    fun register(output: SpatialAudioOutput) {
        activeOutput = output
    }

    fun unregister(output: SpatialAudioOutput) {
        if (activeOutput === output) activeOutput = null
    }

    fun clear() {
        isFinalEosDrain = false
        processedStreamChangePending = false
    }
}
