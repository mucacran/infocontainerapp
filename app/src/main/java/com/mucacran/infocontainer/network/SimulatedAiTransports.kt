package com.mucacran.infocontainer.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.delay
import java.io.File

/** Synthetic metadata only: no HTTP client, files or production delegate. */
class SimulatedPhotoApi : PhotoApi {
    override suspend fun upload(username: String, applicationPassword: String, inspectionId: Long, payload: UploadPhotoPayload) = error("not used in AI simulation")
    override suspend fun list(username: String, applicationPassword: String, inspectionId: Long): List<RemotePhoto> {
        require(inspectionId > 0)
        return listOf(
            RemotePhoto(12, inspectionId, "00000000-0000-4000-8000-000000000012", "identifier", "image/png", 2143, "a".repeat(64)),
            RemotePhoto(15, inspectionId, "00000000-0000-4000-8000-000000000015", "damage", "image/png", 2143, "b".repeat(64))
        )
    }
    override suspend fun metadata(username: String, applicationPassword: String, photoId: Long) = error("not used in AI simulation")
    override suspend fun downloadVerified(username: String, applicationPassword: String, photo: RemotePhoto) = error("not used in AI simulation")
}

/** Socket-free contract fixture. It cannot invoke WordPressAiApi or consume credentials. */
class SimulatedAiApi : AiApi {
    private data class StoredAnalysis(val inspectionId: Long, val photoIds: List<Long>, val response: AnalysisRun)
    private data class ReviewInput(val runId: Long, val action: String, val content: JsonElement, val resolution: String?)
    private data class StoredReview(val input: ReviewInput, val review: AiReview)
    private val runs = linkedMapOf<String, StoredAnalysis>()
    private val reviews = linkedMapOf<String, StoredReview>()
    private val lock = Any()

    // Fault injection is confined to this concrete in-memory fake.
    internal var reviewFailure: Exception? = null
    internal var reviewDelayMillis: Long = 0
    override suspend fun identify(username: String, password: String, file: File, clientRequestId: String) = error("identification simulation is separate")
    override suspend fun analyze(username: String, password: String, inspectionId: Long, clientRequestId: String, photoIds: List<Long>?): AnalysisRun = synchronized(lock) {
        val selected = (photoIds ?: listOf(12L, 15L)).sorted()
        require(inspectionId > 0 && selected.isNotEmpty() && selected.distinct().size == selected.size)
        require(selected.all { it in setOf(12L, 15L) })
        runs[clientRequestId]?.let {
            check(it.inspectionId == inspectionId && it.photoIds == selected) { "infocontainer_ai_idempotency_conflict" }
            return@synchronized it.response
        }
        val proposal = AnalysisProposal(
            if (12L in selected) listOf(AnalysisItem("La placa presenta desgaste visible.", listOf(12))) else emptyList(),
            if (12L in selected) listOf(VisibleReading("identifier", "TSTU4855933", listOf(12))) else emptyList(),
            if (15L in selected) listOf(AnalysisItem("Abolladura visible en la puerta derecha.", listOf(15))) else emptyList(),
            if (15L in selected) listOf(AnalysisItem("La iluminación no permite confirmar el sello.", listOf(15))) else emptyList(),
            listOf("puerta derecha"), listOf("techo"), selected, emptyList(),
            "Se observan daños visibles en la puerta derecha.", "Borrador sujeto a revisión humana.",
            IdentifierComparison("TSTU4855933", false)
        )
        val response = AnalysisRun(
            AiRun(102L + runs.size, "inspection_analysis", inspectionId, clientRequestId, "completed", proposal, null, false, true),
            proposal
        )
        runs[clientRequestId] = StoredAnalysis(inspectionId, selected, response)
        response
    }

    override suspend fun getRun(username: String, password: String, runId: Long): AiRun = synchronized(lock) {
        runs.values.firstOrNull { it.response.run.id == runId }?.response?.run ?: error("simulated run not found")
    }

    override suspend fun listRuns(username: String, password: String, inspectionId: Long): List<AiRun> = synchronized(lock) {
        runs.values.filter { it.inspectionId == inspectionId }.map { it.response.run }
    }

    override suspend fun review(username: String, password: String, runId: Long, clientReviewId: String, action: String, reviewedContent: Any?, identificationResolution: String?): AiReviewResponse {
        if (reviewDelayMillis > 0) delay(reviewDelayMillis)
        reviewFailure?.let { throw it }
        return synchronized(lock) {
            require(action in setOf("accepted", "edited", "rejected"))
            check(runs.values.any { it.response.run.id == runId }) { "simulated run not found" }
            val input = ReviewInput(runId, action, Gson().toJsonTree(reviewedContent), identificationResolution)
            val existing = reviews[clientReviewId]
            check(existing == null || existing.input == input) { "infocontainer_ai_review_conflict" }
            val review = existing?.review ?: AiReview(501L + reviews.size, runId, clientReviewId, action).also {
                reviews[clientReviewId] = StoredReview(input, it)
            }
            AiReviewResponse(review, reviews.values.filter { it.review.runId == runId }.map { it.review })
        }
    }
}
