package com.mucacran.infocontainer.data

import com.mucacran.infocontainer.network.AiApi
import com.mucacran.infocontainer.network.AiExecutionGate
import com.mucacran.infocontainer.network.AnalysisProposal
import com.mucacran.infocontainer.network.PhotoApi
import com.mucacran.infocontainer.network.SimulatedAiApi
import com.mucacran.infocontainer.network.SimulatedPhotoApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Focused regression coverage. No socket, HTTP client, database or original photo is used. */
class AiAnalysisCoordinatorRegressionTest {
    @Test
    fun simulated_acceptance_returns_registered_review_and_original_proposal() = runBlocking {
        val coordinator = AiAnalysisCoordinator.simulated()
        val photos = coordinator.listPhotos("", "", 17)
        val run = coordinator.analyze("", "", 17, photos.map { it.id })

        val response = coordinator.review("", "", run.run.id, "accepted", null, null)

        assertTrue(coordinator.isSimulated)
        assertEquals("completed", run.run.status)
        assertEquals("accepted", response.review.action)
        assertEquals(run.run.id, response.review.runId)
        assertEquals(listOf(response.review), response.history)
        assertEquals("Borrador sujeto a revisión humana.", run.proposal.reportDraft)
        assertTrue(run.proposal.photoObservations.isNotEmpty())
        assertTrue(run.proposal.visibleReadings.isNotEmpty())
        assertTrue(run.proposal.visibleDamage.isNotEmpty())
    }

    @Test
    fun simulated_edited_review_accepts_complete_schema_and_keeps_original() = runBlocking {
        val coordinator = AiAnalysisCoordinator.simulated()
        val run = coordinator.analyze("", "", 17, listOf(12, 15))
        val content = edited(run.proposal, "Informe revisado y aprobado por el operador")

        val response = coordinator.review("", "", run.run.id, "edited", content, null)

        assertEquals("edited", response.review.action)
        assertEquals(10, content.size)
        assertEquals("Informe revisado y aprobado por el operador", content["report_draft"])
        assertEquals("Borrador sujeto a revisión humana.", run.proposal.reportDraft)
    }

    @Test
    fun simulated_rejection_returns_confirmation() = runBlocking {
        val coordinator = AiAnalysisCoordinator.simulated()
        val run = coordinator.analyze("", "", 17, listOf(12, 15))

        val response = coordinator.review("", "", run.run.id, "rejected", "No permite confirmar el resultado.", null)

        assertEquals("rejected", response.review.action)
        assertEquals(1, response.history.size)
    }

    @Test
    fun retries_keep_request_uuid_and_review_uuid_without_duplicates() = runBlocking {
        val api = SimulatedAiApi()
        val coordinator = AiAnalysisCoordinator.simulated(api)
        val first = coordinator.analyze("", "", 17, listOf(12, 15))
        val second = coordinator.analyze("", "", 17, listOf(15, 12))
        val content = edited(first.proposal, "Informe revisado")
        val firstReview = coordinator.review("", "", first.run.id, "edited", content, null)
        // Equivalent JSON content with a different map order is the same logical decision.
        val retry = coordinator.review("", "", first.run.id, "edited", content.entries.reversed().associate { it.toPair() }, null)

        assertEquals(first.run.clientRequestId, second.run.clientRequestId)
        UUID.fromString(first.run.clientRequestId)
        UUID.fromString(firstReview.review.clientReviewId)
        assertEquals(first.run.id, second.run.id)
        assertEquals(firstReview.review, retry.review)
        assertEquals(1, retry.history.size)
        assertEquals(1, api.listRuns("", "", 17).size)
    }

    @Test
    fun changed_logical_decision_gets_its_own_stable_review_uuid() = runBlocking {
        val coordinator = AiAnalysisCoordinator.simulated()
        val run = coordinator.analyze("", "", 17, listOf(12, 15))
        val first = coordinator.review("", "", run.run.id, "edited", edited(run.proposal, "Primera revisión"), null)
        val second = coordinator.review("", "", run.run.id, "edited", edited(run.proposal, "Segunda revisión"), null)

        assertNotEquals(first.review.clientReviewId, second.review.clientReviewId)
        assertEquals(2, second.history.size)
    }

    @Test
    fun fake_rejects_same_review_uuid_with_different_payload() = runBlocking {
        val api = SimulatedAiApi()
        val coordinator = AiAnalysisCoordinator.simulated(api)
        val run = coordinator.analyze("", "", 17, listOf(12, 15))
        val first = coordinator.review("", "", run.run.id, "accepted", null, null)

        val conflict = expectFailure {
            api.review("", "", run.run.id, first.review.clientReviewId, "rejected", null, null)
        }
        assertEquals("infocontainer_ai_review_conflict", conflict.message)
        val retry = coordinator.review("", "", run.run.id, "accepted", null, null)
        assertEquals(1, retry.history.size)
    }

    @Test
    fun false_production_gate_blocks_all_calls_before_any_transport_dispatch() = runBlocking {
        var dispatches = 0
        val ai = object : AiApi by SimulatedAiApi() {
            override suspend fun analyze(username: String, password: String, inspectionId: Long, clientRequestId: String, photoIds: List<Long>?) =
                error("Unexpected transport dispatch ${++dispatches}")
            override suspend fun review(username: String, password: String, runId: Long, clientReviewId: String, action: String, reviewedContent: Any?, identificationResolution: String?) =
                error("Unexpected transport dispatch ${++dispatches}")
        }
        val photos = object : PhotoApi by SimulatedPhotoApi() {
            override suspend fun list(username: String, applicationPassword: String, inspectionId: Long) =
                error("Unexpected transport dispatch ${++dispatches}")
        }
        val gate = AiExecutionGate(false)
        val coordinator = AiAnalysisCoordinator(ai, photos, gate)

        assertFalse(coordinator.isSimulated)
        assertEquals("Real AI execution is disabled", expectFailure { coordinator.listPhotos("", "", 17) }.message)
        assertEquals("Real AI execution is disabled", expectFailure { coordinator.analyze("", "", 17, listOf(12, 15)) }.message)
        assertEquals("Real AI execution is disabled", expectFailure { coordinator.review("", "", 102, "accepted", null, null) }.message)
        assertEquals(0, dispatches)
        assertFalse(gate.authorized)
    }

    @Test
    fun failed_fake_review_can_retry_same_uuid_then_complete_once() = runBlocking {
        val api = SimulatedAiApi()
        val coordinator = AiAnalysisCoordinator.simulated(api)
        val run = coordinator.analyze("", "", 17, listOf(12, 15))
        api.reviewFailure = IllegalStateException("Synthetic review failure")
        assertEquals("Synthetic review failure", expectFailure { coordinator.review("", "", run.run.id, "accepted", null, null) }.message)
        api.reviewFailure = null

        val success = coordinator.review("", "", run.run.id, "accepted", null, null)
        val retry = coordinator.review("", "", run.run.id, "accepted", null, null)

        assertEquals(success.review, retry.review)
        assertEquals(1, retry.history.size)
    }

    private suspend fun expectFailure(block: suspend () -> Any?): IllegalStateException {
        try {
            block()
            throw AssertionError("Expected failure")
        } catch (failure: IllegalStateException) {
            return failure
        }
    }

    private fun edited(proposal: AnalysisProposal, report: String): Map<String, Any> = linkedMapOf(
        "photo_observations" to proposal.photoObservations.map { mapOf("text" to it.text, "photo_ids" to it.photoIds) },
        "visible_readings" to proposal.visibleReadings.map { mapOf("kind" to it.kind, "value" to it.value, "photo_ids" to it.photoIds) },
        "visible_damage" to proposal.visibleDamage.map { mapOf("text" to it.text, "photo_ids" to it.photoIds) },
        "needs_review" to proposal.needsReview.map { mapOf("text" to it.text, "photo_ids" to it.photoIds) },
        "documented_zones" to proposal.documentedZones,
        "unevaluable_zones" to proposal.unevaluableZones,
        "analyzed_photo_ids" to proposal.analyzedPhotoIds,
        "excluded_photo_ids" to proposal.excludedPhotoIds,
        "summary" to proposal.summary,
        "report_draft" to report
    )
}
