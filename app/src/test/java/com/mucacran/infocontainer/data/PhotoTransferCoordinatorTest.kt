package com.mucacran.infocontainer.data

import com.mucacran.infocontainer.data.transfer.DraftTransferRecord
import com.mucacran.infocontainer.data.transfer.PhotoTransferMetadata
import com.mucacran.infocontainer.data.transfer.PhotoTransferRecord
import com.mucacran.infocontainer.data.transfer.PhotoTransferStatus
import com.mucacran.infocontainer.data.transfer.TransferJournal
import com.mucacran.infocontainer.data.transfer.TransferJournalException
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.network.PhotoApi
import com.mucacran.infocontainer.network.PhotoTransferException
import com.mucacran.infocontainer.network.PhotoTransferFailure
import com.mucacran.infocontainer.network.RemotePhoto
import com.mucacran.infocontainer.network.UploadPhotoPayload
import com.mucacran.infocontainer.network.VerifiedPhotoDownload
import com.mucacran.infocontainer.network.WordPressConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Coordinator unit tests with no sockets, SQLite or Android image decoding. A fake journal marks
 * the durable boundary; real AtomicFile persistence is covered separately by instrumented tests.
 */
class PhotoTransferCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val events = mutableListOf<String>()
    private lateinit var original: File
    private lateinit var originalBytes: ByteArray
    private lateinit var prepared: PreparedPhotoUpload
    private lateinit var photo: DraftPhoto
    private lateinit var initialDraft: InspectionDraft
    private lateinit var drafts: ReadOnlyDrafts
    private lateinit var journal: FakeJournal
    private lateinit var api: FakeApi
    private var sourceCalls = 0

    @Before
    fun setUp() {
        originalBytes = ByteArray(64) { index -> (index * 7).toByte() }
        original = temporary.newFile("synthetic-original.png").apply { writeBytes(originalBytes) }
        photo = DraftPhoto(UUID.randomUUID().toString(), "image/png", original.length(), 4, 4)
        initialDraft = InspectionDraft(
            id = UUID.randomUUID().toString(), containerIdentifier = "CSQU3054383",
            inspectionType = "receipt", observations = "Synthetic local draft", photos = listOf(photo)
        )
        prepared = PreparedPhotoUpload(
            photo.id, original, photo.mimeType, photo.byteSize, photo.width, photo.height,
            MessageDigest.getInstance("SHA-256").digest(originalBytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        )
        drafts = ReadOnlyDrafts(initialDraft, events)
        journal = FakeJournal(events)
        api = FakeApi(journal, events)
    }

    @After
    fun preserveOriginalAndDraftWrites() {
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals("The coordinator must never write drafts", 0, drafts.writeCalls)
    }

    @Test
    fun durablePreparationPrecedesApi_andOnlySuccessfulResponseCompletes() = runBlocking {
        val result = upload(coordinator())

        assertEquals(listOf("draft.read", "source", "journal.prepare", "journal.prepared", "api.upload", "journal.complete"), events)
        assertEquals(PhotoTransferStatus.COMPLETED, result.status)
        assertEquals(73L, result.remotePhotoId)
        assertEquals(photo.id, result.clientPhotoId)
        assertEquals(result, journal.record!!.photos.single())
        assertEquals(initialDraft, drafts.current)
        assertEquals(1, sourceCalls)
        assertEquals(1, api.payloads.size)
        assertEquals(1, journal.completeCalls)
    }

    @Test
    fun journalPreparationFailure_blocksAllNetworkAndCompletion() = runBlocking {
        journal.prepareFailure = TransferJournalException(TransferJournalException.Reason.STORAGE_FAILURE)

        val failure = expect<TransferJournalException> { upload(coordinator()) }

        assertEquals(TransferJournalException.Reason.STORAGE_FAILURE, failure.reason)
        assertTrue(api.payloads.isEmpty())
        assertEquals(0, journal.completeCalls)
        assertNull(journal.record)
        assertEquals(initialDraft, drafts.current)
    }

    @Test
    fun timeoutAndCoordinatorRecreation_retryExactUuidAndPayloadWithoutLosingPendingIntent() = runBlocking {
        api.failure = PhotoTransferException(PhotoTransferFailure.TIMEOUT)
        val first = expect<PhotoTransferException> { upload(coordinator()) }
        assertEquals(PhotoTransferFailure.TIMEOUT, first.reason)
        val pending = journal.record!!.photos.single()
        assertEquals(PhotoTransferStatus.PENDING, pending.status)
        assertNull(pending.remotePhotoId)
        assertEquals(0, journal.completeCalls)

        api.failure = null
        val afterRecreation = upload(coordinator())

        assertEquals(2, api.payloads.size)
        assertEquals(api.payloads[0], api.payloads[1])
        assertEquals(pending.clientPhotoId, afterRecreation.clientPhotoId)
        assertEquals(pending.metadata, afterRecreation.metadata)
        assertEquals(PhotoTransferStatus.COMPLETED, afterRecreation.status)
        assertEquals(listOf(71L, 71L), api.inspections)
        assertEquals(1, journal.completeCalls)
        assertEquals(initialDraft, drafts.current)
    }

    @Test
    fun rejectedUploadResponses_neverMarkPendingPhotoCompleted() = runBlocking {
        val failures = listOf(
            PhotoTransferFailure.CREDENTIALS_REJECTED to 401,
            PhotoTransferFailure.PERMISSION_DENIED to 403,
            PhotoTransferFailure.RETRY_CONFLICT to 409,
            PhotoTransferFailure.TOO_LARGE to 413,
            PhotoTransferFailure.UNSUPPORTED_FORMAT to 415
        )
        failures.forEach { (reason, code) ->
            api.failure = PhotoTransferException(reason, code)
            val failure = expect<PhotoTransferException> { upload(coordinator()) }
            assertEquals(reason, failure.reason)
            assertEquals(code, failure.httpCode)
            assertEquals(PhotoTransferStatus.PENDING, journal.record!!.photos.single().status)
            assertNull(journal.record!!.photos.single().remotePhotoId)
        }
        assertEquals(5, api.payloads.size)
        assertEquals(1, api.payloads.map { it.clientPhotoId }.distinct().size)
        assertEquals(1, api.payloads.distinct().size)
        assertEquals(0, journal.completeCalls)
    }

    @Test
    fun successfulRemoteUploadWithFailedCompletion_remainsRetryableWithSameIdentity() = runBlocking {
        journal.completeFailure = TransferJournalException(TransferJournalException.Reason.STORAGE_FAILURE)
        expect<TransferJournalException> { upload(coordinator()) }
        assertEquals(1, api.payloads.size)
        assertEquals(PhotoTransferStatus.PENDING, journal.record!!.photos.single().status)

        journal.completeFailure = null
        val completed = upload(coordinator())

        assertEquals(api.payloads[0], api.payloads[1])
        assertEquals(PhotoTransferStatus.COMPLETED, completed.status)
        assertEquals(73L, completed.remotePhotoId)
        assertEquals(2, journal.completeCalls)
    }

    @Test
    fun removedPhoto_isNotPreparedOrSentAndDraftIsNeverRecreated() = runBlocking {
        drafts.current = initialDraft.copy(photos = emptyList())

        expect<IllegalStateException> { upload(coordinator()) }

        assertEquals(0, sourceCalls)
        assertEquals(0, journal.prepareCalls)
        assertTrue(api.payloads.isEmpty())
        assertEquals(initialDraft.copy(photos = emptyList()), drafts.current)
    }

    @Test
    fun missingDraft_isNotPreparedSentOrRecreated() = runBlocking {
        drafts.current = null

        expect<IllegalStateException> { upload(coordinator()) }

        assertEquals(0, sourceCalls)
        assertEquals(0, journal.prepareCalls)
        assertTrue(api.payloads.isEmpty())
        assertNull(drafts.current)
    }

    @Test
    fun inspectionBindingConflict_blocksRequestAndPreservesFirstPlan() = runBlocking {
        leavePending()
        val firstPlan = journal.record

        val failure = expect<TransferJournalException> { upload(coordinator(), inspectionId = 72) }

        assertEquals(TransferJournalException.Reason.BINDING_CONFLICT, failure.reason)
        assertEquals(firstPlan, journal.record)
        assertEquals(1, api.payloads.size)
        assertEquals(0, journal.completeCalls)
    }

    @Test
    fun changedContentOrMetadata_blocksRequestAndPreservesFirstPlan() = runBlocking {
        leavePending()
        val firstPlan = journal.record
        val variants = listOf(prepared.copy(sha256 = "b".repeat(64)), prepared.copy(byteSize = prepared.byteSize + 1))
        variants.forEach { source ->
            val failure = expect<TransferJournalException> { upload(coordinator(source)) }
            assertEquals(TransferJournalException.Reason.PHOTO_CONFLICT, failure.reason)
        }
        val categoryConflict = expect<TransferJournalException> { upload(coordinator(), category = "damage") }
        val descriptionConflict = expect<TransferJournalException> { upload(coordinator(), description = "Changed description") }
        assertEquals(TransferJournalException.Reason.PHOTO_CONFLICT, categoryConflict.reason)
        assertEquals(TransferJournalException.Reason.PHOTO_CONFLICT, descriptionConflict.reason)
        assertEquals(firstPlan, journal.record)
        assertEquals(1, api.payloads.size)
        assertEquals(0, journal.completeCalls)
    }

    @Test
    fun sourceIdentityMismatch_blocksJournalAndNetwork() = runBlocking {
        expect<IllegalStateException> { upload(coordinator(prepared.copy(clientPhotoId = UUID.randomUUID().toString()))) }
        assertEquals(0, journal.prepareCalls)
        assertTrue(api.payloads.isEmpty())
        assertNull(journal.record)
    }

    private fun coordinator(source: PreparedPhotoUpload = prepared) = PhotoTransferCoordinator(
        drafts, journal, { supplied ->
            events += "source"
            sourceCalls++
            assertEquals(photo, supplied)
            source
        }, api
    )

    private suspend fun upload(
        coordinator: PhotoTransferCoordinator,
        inspectionId: Long = 71,
        category: String = "other",
        description: String = "Synthetic upload"
    ) = coordinator.upload(
        initialDraft.id, inspectionId, photo.id, category, description,
        "synthetic-operator", "synthetic-application-password"
    )

    private suspend fun leavePending() {
        api.failure = PhotoTransferException(PhotoTransferFailure.TIMEOUT)
        expect<PhotoTransferException> { upload(coordinator()) }
        api.failure = null
    }

    private suspend inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try { block() }
        catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private class ReadOnlyDrafts(
        var current: InspectionDraft?, private val events: MutableList<String>
    ) : InspectionDraftRepository {
        var writeCalls = 0
        override suspend fun getDraftById(id: String): Result<InspectionDraft?> {
            events += "draft.read"
            return Result.success(current?.takeIf { it.id == id })
        }
        override suspend fun getAllDrafts() = Result.success(listOfNotNull(current))
        override suspend fun createDraft(draft: InspectionDraft): Result<Boolean> = forbiddenWrite()
        override suspend fun updateDraft(draft: InspectionDraft): Result<Boolean> = forbiddenWrite()
        override suspend fun deleteDraft(id: String): Result<Boolean> = forbiddenWrite()
        private fun forbiddenWrite(): Nothing {
            writeCalls++
            throw AssertionError("Unexpected draft modification")
        }
    }

    private class FakeJournal(private val events: MutableList<String>) : TransferJournal {
        var record: DraftTransferRecord? = null
        var prepareFailure: TransferJournalException? = null
        var completeFailure: TransferJournalException? = null
        var prepareCalls = 0
        var completeCalls = 0

        override suspend fun load(draftId: String) = record?.takeIf { it.localDraftId == draftId }

        override suspend fun prepare(draftId: String, inspectionId: Long, photo: PhotoTransferMetadata): PhotoTransferRecord {
            events += "journal.prepare"
            prepareCalls++
            prepareFailure?.let { throw it }
            val previous = record
            if (previous != null && (previous.localDraftId != draftId || previous.remoteInspectionId != inspectionId)) {
                throw TransferJournalException(TransferJournalException.Reason.BINDING_CONFLICT)
            }
            val existing = previous?.photos?.singleOrNull { it.metadata.localPhotoId == photo.localPhotoId }
            if (existing != null) {
                if (existing.metadata != photo) throw TransferJournalException(TransferJournalException.Reason.PHOTO_CONFLICT)
                events += "journal.prepared"
                return existing
            }
            val pending = PhotoTransferRecord(photo, photo.localPhotoId)
            record = DraftTransferRecord(WordPressConfig.BASE_URL, draftId, inspectionId, previous?.photos.orEmpty() + pending)
            events += "journal.prepared"
            return pending
        }

        override suspend fun complete(draftId: String, localPhotoId: String, remotePhotoId: Long): PhotoTransferRecord {
            events += "journal.complete"
            completeCalls++
            completeFailure?.let { throw it }
            val previous = checkNotNull(record)
            assertEquals(draftId, previous.localDraftId)
            val pending = previous.photos.single { it.metadata.localPhotoId == localPhotoId }
            val completed = pending.copy(status = PhotoTransferStatus.COMPLETED, remotePhotoId = remotePhotoId)
            record = previous.copy(photos = previous.photos.map { if (it == pending) completed else it })
            return completed
        }
    }

    private class FakeApi(
        private val journal: FakeJournal, private val events: MutableList<String>
    ) : PhotoApi {
        val payloads = mutableListOf<UploadPhotoPayload>()
        val inspections = mutableListOf<Long>()
        var failure: PhotoTransferException? = null

        override suspend fun upload(
            username: String, applicationPassword: String, inspectionId: Long, payload: UploadPhotoPayload
        ): RemotePhoto {
            events += "api.upload"
            // This assertion ensures upload can observe an already prepared, immutable intention.
            val planned = checkNotNull(journal.record).photos.single { it.clientPhotoId == payload.clientPhotoId }
            assertEquals(inspectionId, journal.record!!.remoteInspectionId)
            assertEquals(planned.metadata.sha256, payload.sha256)
            assertEquals(planned.metadata.byteSize, payload.byteSize)
            assertEquals(planned.metadata.mimeType, payload.mimeType)
            assertEquals(planned.metadata.category, payload.category)
            assertEquals(planned.metadata.description.takeIf { it.isNotEmpty() }, payload.description)
            assertEquals(planned.metadata.capturedAt, payload.capturedAt)
            payloads += payload
            inspections += inspectionId
            failure?.let { throw it }
            return RemotePhoto(73, inspectionId, payload.clientPhotoId, payload.category, payload.mimeType, payload.byteSize, payload.sha256)
        }

        override suspend fun list(username: String, applicationPassword: String, inspectionId: Long): List<RemotePhoto> =
            error("Unexpected list")
        override suspend fun metadata(username: String, applicationPassword: String, photoId: Long): RemotePhoto =
            error("Unexpected metadata")
        override suspend fun downloadVerified(username: String, applicationPassword: String, photo: RemotePhoto): VerifiedPhotoDownload =
            error("Unexpected download")
    }
}
