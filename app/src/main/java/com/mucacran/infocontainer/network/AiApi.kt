package com.mucacran.infocontainer.network

import java.io.File

data class IdentificationCandidate(val value: String, val iso6346Valid: Boolean)
data class IdentificationProposal(val visibleIdentifierText: String?, val candidates: List<IdentificationCandidate>, val isoCode: String?, val legibility: String, val warnings: List<String>)
data class AiRun(val id: Long, val operation: String, val inspectionId: Long?, val clientRequestId: String, val status: String, val proposal: Any?, val error: String?, val stale: Boolean, val humanConfirmationRequired: Boolean)
/** A proposal exists only when WordPress has completed identification successfully. */
data class IdentificationRun(val run: AiRun, val proposal: IdentificationProposal?)
data class AnalysisItem(val text: String, val photoIds: List<Long>)
data class VisibleReading(val kind: String, val value: String?, val photoIds: List<Long>)
data class IdentifierComparison(val registeredIdentifier: String?, val conflict: Boolean)
data class AnalysisProposal(val photoObservations: List<AnalysisItem>, val visibleReadings: List<VisibleReading>, val visibleDamage: List<AnalysisItem>, val needsReview: List<AnalysisItem>, val documentedZones: List<String>, val unevaluableZones: List<String>, val analyzedPhotoIds: List<Long>, val excludedPhotoIds: List<Long>, val summary: String, val reportDraft: String, val identifierComparison: IdentifierComparison?)
data class AnalysisRun(val run: AiRun, val proposal: AnalysisProposal)
data class AiReview(val id: Long, val runId: Long, val clientReviewId: String, val action: String)
data class AiReviewResponse(val review: AiReview, val history: List<AiReview>)

interface AiApi {
    suspend fun identify(username: String, password: String, file: File, clientRequestId: String): IdentificationRun
    /** Query a known run after a processing or uncertain POST; this does not start a new provider call. */
    suspend fun getIdentificationRun(username: String, password: String, runId: Long): IdentificationRun {
        throw UnsupportedOperationException("Identification run lookup unavailable")
    }
    suspend fun analyze(username: String, password: String, inspectionId: Long, clientRequestId: String, photoIds: List<Long>?): AnalysisRun
    suspend fun getRun(username: String, password: String, runId: Long): AiRun
    suspend fun listRuns(username: String, password: String, inspectionId: Long): List<AiRun>
    suspend fun review(username: String, password: String, runId: Long, clientReviewId: String, action: String, reviewedContent: Any? = null, identificationResolution: String? = null): AiReviewResponse
}
