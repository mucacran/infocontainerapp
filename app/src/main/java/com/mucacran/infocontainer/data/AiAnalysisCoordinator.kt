package com.mucacran.infocontainer.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.mucacran.infocontainer.network.AiApi
import com.mucacran.infocontainer.network.AiExecutionGate
import com.mucacran.infocontainer.network.AnalysisRun
import com.mucacran.infocontainer.network.AiReviewResponse
import com.mucacran.infocontainer.network.PhotoApi
import com.mucacran.infocontainer.network.RemotePhoto
import com.mucacran.infocontainer.network.SimulatedAiApi
import com.mucacran.infocontainer.network.SimulatedPhotoApi
import java.util.UUID

/** The only ungated path is privately constructed with the two concrete, socket-free fakes. */
class AiAnalysisCoordinator private constructor(
    private val ai: AiApi,
    private val photos: PhotoApi,
    private val gate: AiExecutionGate,
    val isSimulated: Boolean
) {
    constructor(ai: AiApi, photos: PhotoApi, gate: AiExecutionGate) : this(ai, photos, gate, false)

    private val requestIds = mutableMapOf<String, String>()
    private data class ReviewKey(val runId: Long, val action: String, val content: JsonElement, val resolution: String?)
    private val reviewIds = mutableMapOf<ReviewKey, String>()

    private fun requireExecutionAllowed() {
        check(isSimulated || gate.authorized) { "Real AI execution is disabled" }
    }

    suspend fun listPhotos(username: String, password: String, inspectionId: Long): List<RemotePhoto> {
        requireExecutionAllowed()
        return photos.list(username, password, inspectionId)
    }
    suspend fun analyze(username: String, password: String, inspectionId: Long, selectedPhotoIds: List<Long>): AnalysisRun {
        requireExecutionAllowed()
        require(inspectionId > 0 && selectedPhotoIds.isNotEmpty() && selectedPhotoIds.size <= 12)
        require(selectedPhotoIds.all { it > 0 } && selectedPhotoIds.distinct().size == selectedPhotoIds.size)
        val key = "$inspectionId:${selectedPhotoIds.sorted().joinToString(",")}"
        val requestId = synchronized(requestIds) { requestIds.getOrPut(key) { UUID.randomUUID().toString() } }
        return ai.analyze(username, password, inspectionId, requestId, selectedPhotoIds)
    }
    suspend fun review(username: String, password: String, runId: Long, action: String, content: Any?, resolution: String?): AiReviewResponse {
        requireExecutionAllowed()
        val key = ReviewKey(runId, action, Gson().toJsonTree(content), resolution)
        val reviewId = synchronized(reviewIds) { reviewIds.getOrPut(key) { UUID.randomUUID().toString() } }
        return ai.review(username, password, runId, reviewId, action, content, resolution)
    }

    companion object {
        /** Callers cannot supply a real HTTP transport to this factory. Use only in debug UI. */
        fun simulated(
            ai: SimulatedAiApi = SimulatedAiApi(),
            photos: SimulatedPhotoApi = SimulatedPhotoApi()
        ): AiAnalysisCoordinator = AiAnalysisCoordinator(
            ai, photos, AiExecutionGate(false), true
        )
    }
}
