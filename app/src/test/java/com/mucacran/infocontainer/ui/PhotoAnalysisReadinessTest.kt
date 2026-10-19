package com.mucacran.infocontainer.ui

import com.mucacran.infocontainer.data.DraftNotFoundException
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.model.InspectionDraft
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoAnalysisReadinessTest {
    @Test
    fun newInspectionNeedsNoTypingAndRecordsItsTimeAutomatically() {
        val state = newState()

        assertEquals("", state.content.containerIdentifier)
        assertEquals("", state.content.isoCode)
        assertEquals("", state.content.observations)
        assertTrue(state.content.inspectedAt.isNotBlank())
        assertEquals(PhotoAnalysisReadiness.PHOTOS_REQUIRED, state.photoAnalysisReadiness)
        assertNull(state.draftId)
    }

    @Test
    fun photoAndReasonReachServerBlockWithoutIdentifierIsoOrObservations() = runBlocking {
        val state = newState()
        state.addPhotos { listOf(photo()) }
        state.updateContent(state.content.copy(inspectionType = "inventory"))

        assertEquals("", state.content.containerIdentifier)
        assertEquals("", state.content.isoCode)
        assertEquals("", state.content.observations)
        assertEquals(PhotoAnalysisReadiness.SERVER_UNAVAILABLE, state.photoAnalysisReadiness)
        assertEquals(0, state.completedSaveCount)
        assertNull(state.draftId)
    }

    @Test
    fun addingRemovingAndSelectingReasonOnlyChangeLocalPreparation() = runBlocking {
        val state = newState()
        val initialReference = state.lastSavedContent
        val attached = photo()
        val inspectedAt = state.content.inspectedAt

        assertEquals(PhotoAnalysisReadiness.PHOTOS_REQUIRED, state.photoAnalysisReadiness)
        state.addPhotos { listOf(attached) }
        assertEquals(PhotoAnalysisReadiness.REASON_REQUIRED, state.photoAnalysisReadiness)
        state.updateContent(state.content.copy(inspectionType = "unknown"))
        assertEquals(PhotoAnalysisReadiness.REASON_REQUIRED, state.photoAnalysisReadiness)
        state.updateContent(state.content.copy(inspectionType = "receipt"))
        repeat(3) {
            assertEquals(PhotoAnalysisReadiness.SERVER_UNAVAILABLE, state.photoAnalysisReadiness)
        }
        assertTrue(state.removePhoto(attached.id))
        assertEquals(PhotoAnalysisReadiness.PHOTOS_REQUIRED, state.photoAnalysisReadiness)

        assertEquals(initialReference, state.lastSavedContent)
        assertEquals(inspectedAt, state.content.inspectedAt)
        assertEquals(0, state.completedSaveCount)
        assertNull(state.pendingSaveResult)
        assertNull(state.draftId)
        assertTrue(state.hasUnsavedChanges)
    }

    @Test
    fun unfinishedCaptureAndPendingSelectionRequireRecoveryBeforePrerequisites() = runBlocking {
        val state = preparedState()
        state.setPendingCapture(UUID.randomUUID().toString())
        assertTrue(state.canEdit)
        assertEquals(PhotoAnalysisReadiness.RECOVERY_REQUIRED, state.photoAnalysisReadiness)

        state.setPendingCapture(null)
        state.setPendingPhotoSelection(true)
        state.reportPhotoError("No se pudo completar la selección.")
        assertEquals(PhotoAnalysisReadiness.RECOVERY_REQUIRED, state.photoAnalysisReadiness)

        state.removePhoto(state.content.photos.single().id)
        assertEquals(PhotoAnalysisReadiness.RECOVERY_REQUIRED, state.photoAnalysisReadiness)
        state.setPendingPhotoSelection(false)
        assertEquals(PhotoAnalysisReadiness.PHOTOS_REQUIRED, state.photoAnalysisReadiness)
    }

    @Test
    fun successfulImportJournalDoesNotReportAnUnfinishedSelection() = runBlocking {
        val state = preparedState()
        state.setPendingPhotoSelection(true)

        // The real import journal stays available for recovery until an explicit save.
        assertNull(state.photoErrorMessage)
        assertEquals(PhotoAnalysisReadiness.SERVER_UNAVAILABLE, state.photoAnalysisReadiness)
        state.reportPhotoError("Selección incompleta.")
        assertEquals(PhotoAnalysisReadiness.RECOVERY_REQUIRED, state.photoAnalysisReadiness)
        state.reportPhotoError(null)
        assertEquals(PhotoAnalysisReadiness.SERVER_UNAVAILABLE, state.photoAnalysisReadiness)
        assertTrue(state.hasPendingPhotoSelection)
        assertEquals(0, state.completedSaveCount)
    }

    @Test
    fun externalPickerAndCameraBlockPreparationUntilTheyReturn() = runBlocking {
        val state = preparedState()

        assertTrue(state.beginPhotoRequest("picker"))
        assertEquals(PhotoAnalysisReadiness.BUSY, state.photoAnalysisReadiness)
        state.finishPhotoRequest()
        assertEquals(PhotoAnalysisReadiness.SERVER_UNAVAILABLE, state.photoAnalysisReadiness)

        assertTrue(state.beginPhotoRequest("camera", UUID.randomUUID().toString()))
        assertEquals(PhotoAnalysisReadiness.BUSY, state.photoAnalysisReadiness)
        state.finishPhotoRequest(clearCapture = false)
        assertEquals(PhotoAnalysisReadiness.RECOVERY_REQUIRED, state.photoAnalysisReadiness)
    }

    @Test
    fun importingPhotosIsBusyAndDoesNotSaveAfterCompletion() = runBlocking {
        val state = newState()
        state.updateContent(state.content.copy(inspectionType = "delivery"))
        val gate = CompletableDeferred<List<DraftPhoto>>()
        val import = async(start = CoroutineStart.UNDISPATCHED) {
            state.addPhotos { gate.await() }
        }

        assertEquals(PhotoAnalysisReadiness.BUSY, state.photoAnalysisReadiness)
        val attached = photo()
        gate.complete(listOf(attached))
        assertEquals(1, import.await().getOrThrow())
        assertEquals(PhotoAnalysisReadiness.SERVER_UNAVAILABLE, state.photoAnalysisReadiness)
        assertEquals(listOf(attached), state.content.photos)
        assertTrue(state.lastSavedContent.photos.isEmpty())
        assertEquals(0, state.completedSaveCount)
        assertNull(state.draftId)
    }

    @Test
    fun offlineLocalSaveWithBlankIdentifierRetainsPhotosAndRepeatedSaveKeepsOneId() = runBlocking {
        // This repository has no network dependency; the test does not contact any service.
        val repository = LocalRepository()
        val state = preparedState()
        val photos = state.content.photos
        repository.writeGate = CompletableDeferred()
        val save = async(start = CoroutineStart.UNDISPATCHED) { state.save(repository) }

        assertEquals(PhotoAnalysisReadiness.BUSY, state.photoAnalysisReadiness)
        assertNull(state.save(repository))
        assertEquals(1, repository.createCalls)

        repository.writeGate!!.complete(Unit)
        assertTrue(save.await()!!.isSuccess)
        val savedId = state.draftId
        assertNotNull(savedId)
        assertEquals("", repository.drafts.getValue(savedId!!).containerIdentifier)
        assertEquals(photos, repository.drafts.getValue(savedId).photos)
        assertEquals(PhotoAnalysisReadiness.SERVER_UNAVAILABLE, state.photoAnalysisReadiness)

        assertTrue(state.save(repository)!!.isSuccess)
        assertEquals(savedId, state.draftId)
        assertEquals(1, repository.createCalls)
        assertEquals(1, repository.updateCalls)
        assertEquals(1, repository.drafts.size)
        assertEquals(photos, state.content.photos)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun failedLocalSaveKeepsPhotosAndAllowsExplicitRetry() = runBlocking {
        val repository = LocalRepository()
        val state = preparedState()
        val content = state.content
        val reference = state.lastSavedContent
        repository.writeError = IllegalStateException("Sin espacio local")

        assertTrue(state.save(repository)!!.isFailure)
        assertEquals(content, state.content)
        assertEquals(reference, state.lastSavedContent)
        assertNull(state.draftId)
        assertTrue(repository.drafts.isEmpty())
        assertEquals(PhotoAnalysisReadiness.SERVER_UNAVAILABLE, state.photoAnalysisReadiness)

        repository.writeError = null
        assertTrue(state.save(repository)!!.isSuccess)
        assertEquals(content.photos, repository.drafts.values.single().photos)
    }

    @Test
    fun loadAndLoadErrorBlockPreparationUntilAnExplicitRetrySucceeds() = runBlocking {
        val attached = photo()
        val draft = InspectionDraft(
            id = "existing",
            inspectionType = "sale",
            photos = listOf(attached)
        )
        val repository = LocalRepository(draft)
        repository.readGate = CompletableDeferred()
        val state = InspectionFormState().apply { open(draft.id) }
        val load = async(start = CoroutineStart.UNDISPATCHED) {
            state.loadIfNeeded(repository)
        }

        assertEquals(PhotoAnalysisReadiness.BUSY, state.photoAnalysisReadiness)
        repository.readError = IllegalStateException("No se pudo leer el borrador")
        repository.readGate!!.complete(Unit)
        load.await()
        assertTrue(state.loadState is FormLoadState.Error)
        assertEquals(PhotoAnalysisReadiness.BUSY, state.photoAnalysisReadiness)
        assertEquals(listOf(attached), repository.drafts.getValue(draft.id).photos)

        repository.readError = null
        state.retryLoad(repository)
        assertEquals(PhotoAnalysisReadiness.SERVER_UNAVAILABLE, state.photoAnalysisReadiness)
        assertEquals(listOf(attached), state.content.photos)
        assertEquals(0, repository.createCalls + repository.updateCalls)
    }

    private fun newState() = InspectionFormState().apply { open(null) }

    private suspend fun preparedState() = newState().apply {
        addPhotos { listOf(photo()) }
        updateContent(content.copy(inspectionType = "inventory"))
    }

    private fun photo() = DraftPhoto(
        UUID.randomUUID().toString(), "image/jpeg", 1_024L, 640, 480
    )

    private class LocalRepository(vararg initial: InspectionDraft) : InspectionDraftRepository {
        val drafts = initial.associateBy { it.id }.toMutableMap()
        var createCalls = 0
        var updateCalls = 0
        var writeGate: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null
        var writeError: Exception? = null
        var readError: Exception? = null

        override suspend fun createDraft(draft: InspectionDraft): Result<Boolean> {
            createCalls++
            writeGate?.await()
            writeError?.let { return Result.failure(it) }
            if (drafts.containsKey(draft.id)) return Result.failure(IllegalStateException("ID duplicado"))
            drafts[draft.id] = draft
            return Result.success(true)
        }

        override suspend fun updateDraft(draft: InspectionDraft): Result<Boolean> {
            updateCalls++
            writeGate?.await()
            writeError?.let { return Result.failure(it) }
            if (!drafts.containsKey(draft.id)) return Result.failure(DraftNotFoundException(draft.id))
            drafts[draft.id] = draft
            return Result.success(true)
        }

        override suspend fun getDraftById(id: String): Result<InspectionDraft?> {
            readGate?.await()
            readError?.let { return Result.failure(it) }
            return Result.success(drafts[id])
        }

        override suspend fun getAllDrafts(): Result<List<InspectionDraft>> =
            Result.success(drafts.values.toList())

        override suspend fun deleteDraft(id: String): Result<Boolean> =
            Result.success(drafts.remove(id) != null)
    }
}
