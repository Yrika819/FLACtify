package com.flactify.viewmodel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecommendationController internal constructor(
    private val recommendationManager: RecommendationManager
) {
    constructor(context: Context) : this(RecommendationManager(context))

    fun clearCache() = recommendationManager.clearCache()

    suspend fun getRecommendations(
        artistMap: Map<String, List<TrackData>>,
        allStats: Map<String, LibraryManager.PlayStats>
    ): Result<List<RecommendedTrack>> = withContext(Dispatchers.IO) {
        recommendationManager.getRecommendations(artistMap, allStats)
    }
}
