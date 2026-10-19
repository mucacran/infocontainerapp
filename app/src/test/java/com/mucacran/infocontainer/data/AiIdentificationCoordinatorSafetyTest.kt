package com.mucacran.infocontainer.data

import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.network.AiApi
import com.mucacran.infocontainer.network.AiExecutionGate
import com.mucacran.infocontainer.network.AiReviewResponse
import com.mucacran.infocontainer.network.AiRun
import com.mucacran.infocontainer.network.AnalysisRun
import com.mucacran.infocontainer.network.IdentificationCandidate
import com.mucacran.infocontainer.network.IdentificationProposal
import com.mucacran.infocontainer.network.IdentificationRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

/** All transport responses are local fakes; these tests never construct an HTTP client. */
class AiIdentificationCoordinatorSafetyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun gateBlocksIdentificationAndReviewBeforeTransport() = runBlocking {
        val fixture = fixture()
        val coordinator = fixture.coordinator(AiExecutionGate(false))

        assertEquals("Real identification is disabled", failure { coordinator.identify("operador", "clave", fixture.photo) }.message)
        assertEquals("Real AI review is disabled", failure { coordinator.review("operador", "clave", 101, "accepted") }.message)
        assertNull(fixture.journal.read())
        assertEquals(0, fixture.api.posts)
        assertEquals(0, fixture.api.gets)
        assertEquals(0, fixture.api.reviews)
    }

    @Test fun oneExplicitAttemptIsReservedBeforePostAndCannotBeRepeatedAfterRecreation() = runBlocking {
        val fixture = fixture()
        val firstGate = AiExecutionGate(false)
        val first = fixture.coordinator(firstGate)
        first.authorizeIdentificationForSession()

        val response = first.identify("operador", "clave", fixture.photo)
        val saved = fixture.journal.read()
        assertFalse(firstGate.authorized)
        assertTrue(first.identificationAuthorized)
        assertEquals(1, fixture.api.posts)
        assertEquals(saved?.copy(runId = null), fixture.api.reservationAtPost)
        assertEquals(response.run.clientRequestId, saved?.clientRequestId)
        assertEquals(fixture.photo.id, saved?.photoId)
        assertTrue(saved?.sha256?.matches(Regex("[0-9a-f]{64}")) == true)
        assertEquals(101L, saved?.runId)

        val reopened = fixture.coordinator(AiExecutionGate(false))
        assertEquals(saved, reopened.attempt())
        reopened.authorizeIdentificationForSession()
        val duplicate = failure { reopened.identify("operador", "clave", fixture.photo) }
        assertTrue(duplicate is IdentificationAlreadyAttemptedException)
        assertEquals(101L, (duplicate as IdentificationAlreadyAttemptedException).knownRunId)
        assertEquals(1, fixture.api.posts)
    }

    @Test fun timeoutWithoutRunIdRemainsUncertainAndNeverPostsAgain() = runBlocking {
        val fixture = fixture()
        fixture.api.identifyFailure = IOException("synthetic lost response")
        val coordinator = fixture.coordinator(AiExecutionGate(false))
        coordinator.authorizeIdentificationForSession()

        assertTrue(failure { coordinator.identify("operador", "clave", fixture.photo) } is IOException)
        val saved = checkNotNull(fixture.journal.read())
        assertNull(saved.runId)
        assertNull(coordinator.checkExisting("operador", "clave"))
        assertEquals(0, fixture.api.gets)

        fixture.api.identifyFailure = null
        val reopened = fixture.coordinator(AiExecutionGate(false))
        reopened.authorizeIdentificationForSession()
        assertTrue(failure { reopened.identify("operador", "clave", fixture.photo) } is IdentificationAlreadyAttemptedException)
        assertEquals(saved.clientRequestId, reopened.attempt()?.clientRequestId)
        assertEquals(1, fixture.api.posts)
    }

    @Test fun knownRunIsRecoveredOnlyByGetAndCorrelationIsVerified() = runBlocking {
        val fixture = fixture()
        val coordinator = fixture.coordinator(AiExecutionGate(false))
        coordinator.authorizeIdentificationForSession()
        val first = coordinator.identify("operador", "clave", fixture.photo)
        fixture.api.getResponse = first.copy(run = first.run.copy(status = "completed"))

        val recovered = fixture.coordinator(AiExecutionGate(false)).checkExisting("operador", "clave")
        assertEquals("completed", recovered?.run?.status)
        assertEquals(1, fixture.api.gets)
        assertEquals(1, fixture.api.posts)

        fixture.api.getResponse = first.copy(run = first.run.copy(clientRequestId = UUID.randomUUID().toString()))
        assertTrue(failure { coordinator.checkExisting("operador", "clave") } is IllegalStateException)
        assertEquals(1, fixture.api.posts)
    }

    @Test fun invalidResponseRemainsReservedWithNoRunId() = runBlocking {
        val fixture = fixture()
        fixture.api.wrongRequestId = true
        val coordinator = fixture.coordinator(AiExecutionGate(false))
        coordinator.authorizeIdentificationForSession()
        assertTrue(failure { coordinator.identify("operador", "clave", fixture.photo) } is IllegalStateException)
        assertNull(coordinator.attempt()?.runId)
        assertTrue(failure { coordinator.identify("operador", "clave", fixture.photo) } is IdentificationAlreadyAttemptedException)
        assertEquals(1, fixture.api.posts)
    }

    @Test fun badCredentialsAndUnsupportedPhotoDoNotConsumeTheAttempt() = runBlocking {
        val fixture = fixture()
        val coordinator = fixture.coordinator(AiExecutionGate(false))
        coordinator.authorizeIdentificationForSession()
        assertTrue(failure { coordinator.identify("", "", fixture.photo) } is IllegalArgumentException)
        assertTrue(failure { coordinator.identify("operador\radmin", "clave", fixture.photo) } is IllegalArgumentException)
        assertTrue(failure { coordinator.identify("operador", "clave\nsecreta", fixture.photo) } is IllegalArgumentException)
        assertTrue(failure { coordinator.identify("operador", "clave", fixture.photo.copy(mimeType = "image/webp")) } is IllegalArgumentException)
        assertTrue(failure { coordinator.identify("operador", "clave", fixture.photo.copy(byteSize = 10L * 1024 * 1024 + 1)) } is IllegalArgumentException)
        assertNull(coordinator.attempt())
        assertEquals(0, fixture.api.posts)
    }

    @Test fun invalidFileSignatureDoesNotConsumeTheAttempt() = runBlocking {
        val fixture = fixture()
        fixture.file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val coordinator = fixture.coordinator(AiExecutionGate(false))
        coordinator.authorizeIdentificationForSession()
        assertTrue(failure { coordinator.identify("operador", "clave", fixture.photo) } is IllegalArgumentException)
        assertNull(coordinator.attempt())
        assertEquals(0, fixture.api.posts)
    }

    @Test fun identificationPermitDoesNotAuthorizeReview() = runBlocking {
        val fixture = fixture()
        val gate = AiExecutionGate(false)
        val coordinator = fixture.coordinator(gate)
        coordinator.authorizeIdentificationForSession()
        val run = coordinator.identify("operador", "clave", fixture.photo)
        assertEquals("Real AI review is disabled", failure { coordinator.review("operador", "clave", run.run.id, "accepted") }.message)
        assertFalse(gate.authorized)
        assertEquals(0, fixture.api.reviews)
    }

    private fun fixture(): Fixture {
        val file = temporary.newFile("synthetic-photo.jpg")
        file.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
        val photo = DraftPhoto(UUID.randomUUID().toString(), "image/jpeg", file.length(), 1, 1)
        val journal = MemoryJournal()
        return Fixture(file, photo, journal, FakeAiApi(journal))
    }

    private data class Fixture(val file: File, val photo: DraftPhoto, val journal: MemoryJournal, val api: FakeAiApi) {
        fun coordinator(gate: AiExecutionGate) = AiIdentificationCoordinator(api, { file }, gate, journal)
    }

    private class MemoryJournal : IdentificationAttemptJournal {
        private var attempt: IdentificationAttempt? = null
        @Synchronized override fun read() = attempt
        @Synchronized override fun reserveIfAbsent(attempt: IdentificationAttempt): IdentificationAttempt? {
            val prior = this.attempt
            if (prior == null) this.attempt = attempt
            return prior
        }
        @Synchronized override fun recordRunId(clientRequestId: String, runId: Long) {
            val prior = checkNotNull(attempt)
            check(prior.clientRequestId == clientRequestId)
            attempt = prior.copy(runId = runId)
        }
    }

    private class FakeAiApi(private val journal: MemoryJournal) : AiApi {
        var posts = 0
        var gets = 0
        var reviews = 0
        var reservationAtPost: IdentificationAttempt? = null
        var identifyFailure: IOException? = null
        var getResponse: IdentificationRun? = null
        var wrongRequestId = false
        private var response: IdentificationRun? = null

        override suspend fun identify(username: String, password: String, file: File, clientRequestId: String): IdentificationRun {
            posts++
            reservationAtPost = journal.read()
            identifyFailure?.let { throw it }
            val proposal = IdentificationProposal("TSTU4855933", listOf(IdentificationCandidate("TSTU4855933", true)), "22G1", "clear", emptyList())
            return IdentificationRun(
                AiRun(101, "identification", null, if (wrongRequestId) UUID.randomUUID().toString() else clientRequestId,
                    "completed", proposal, null, false, true), proposal
            ).also { response = it }
        }

        override suspend fun getIdentificationRun(username: String, password: String, runId: Long): IdentificationRun {
            gets++
            return getResponse ?: checkNotNull(response)
        }
        override suspend fun getRun(username: String, password: String, runId: Long): AiRun = getIdentificationRun(username, password, runId).run
        override suspend fun analyze(username: String, password: String, inspectionId: Long, clientRequestId: String, photoIds: List<Long>?): AnalysisRun = error("No analysis transport expected")
        override suspend fun listRuns(username: String, password: String, inspectionId: Long): List<AiRun> = error("No list transport expected")
        override suspend fun review(username: String, password: String, runId: Long, clientReviewId: String, action: String,
            reviewedContent: Any?, identificationResolution: String?): AiReviewResponse { reviews++; error("No review transport expected") }
    }

    private suspend fun failure(block: suspend () -> Any?): Exception = try {
        block()
        throw AssertionError("Expected failure")
    } catch (exception: Exception) { exception }
}
