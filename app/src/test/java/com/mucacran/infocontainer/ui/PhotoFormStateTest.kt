package com.mucacran.infocontainer.ui

import androidx.lifecycle.SavedStateHandle
import com.mucacran.infocontainer.data.DraftNotFoundException
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.model.PhotoLimits
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

class PhotoFormStateTest {
    @Test
    fun multiplePhotosAreIndependentOfInspectionTypeAndMarkTheFormDirty() = runBlocking {
        val state = newState()
        val photos = listOf(photo(), photo(), photo())
        val reference = state.lastSavedContent

        assertEquals(3, state.addPhotos { photos }.getOrThrow())

        assertEquals("", state.content.inspectionType)
        assertEquals(photos, state.content.photos)
        assertEquals(reference, state.lastSavedContent)
        assertTrue(state.hasUnsavedChanges)
        assertEquals(0, state.addPhotos { photos }.getOrThrow())
        assertEquals(photos, state.content.photos)
    }

    @Test
    fun photoRemovalParticipatesInContentComparisonAndDiscardKeepsPersistedFiles() = runBlocking {
        val saved = photo()
        val draft = InspectionDraft(id = "existing", photos = listOf(saved))
        val repository = MemoryRepository(draft)
        val state = InspectionFormState().apply { open(draft.id) }
        state.loadIfNeeded(repository)
        val added = photo()
        state.addPhotos { listOf(added) }

        assertTrue(state.removePhoto(saved.id))
        assertTrue(state.hasUnsavedChanges)
        assertEquals(listOf(saved), state.lastSavedContent.photos)
        assertEquals(listOf(added), state.discardChanges())

        assertEquals(listOf(saved), state.content.photos)
        assertFalse(state.hasUnsavedChanges)
        assertEquals(listOf(saved), repository.drafts[draft.id]!!.photos)
    }

    @Test
    fun delayedImportBlocksEditingNavigationSavingAndOtherPhotoActions() = runBlocking {
        val state = newState()
        val repository = MemoryRepository()
        val gate = CompletableDeferred<List<DraftPhoto>>()
        val import = async(start = CoroutineStart.UNDISPATCHED) { state.addPhotos { gate.await() } }
        assertTrue(state.isImportingPhotos)
        assertTrue(state.isPhotoBusy)
        assertFalse(state.canEdit)
        assertFalse(state.canLeave)
        assertFalse(state.updateContent(state.content.copy(observations = "No entra")))
        assertFalse(state.beginPhotoRequest("camera"))
        assertFalse(state.open(null))
        assertNull(state.save(repository))
        assertTrue(state.addPhotos { listOf(photo()) }.isFailure)
        assertTrue(state.discardChanges().isEmpty())

        val imported = photo()
        gate.complete(listOf(imported))
        assertEquals(1, import.await().getOrThrow())
        assertEquals(listOf(imported), state.content.photos)
        assertTrue(state.canEdit)
        assertTrue(state.canLeave)
        assertFalse(state.isPhotoBusy)
    }

    @Test
    fun failedImportKeepsContentReferenceAndShowsAnError() = runBlocking {
        val state = newState()
        state.addPhotos { listOf(photo()) }
        val content = state.content
        val reference = state.lastSavedContent

        assertTrue(state.addPhotos { throw IllegalStateException("Disco lleno") }.isFailure)

        assertEquals(content, state.content)
        assertEquals(reference, state.lastSavedContent)
        assertTrue(state.hasUnsavedChanges)
        assertEquals("Disco lleno", state.photoErrorMessage)
        assertTrue(state.canEdit)
    }

    @Test
    fun limitsRejectTheWholeBatchAndNeverAdvanceReference() = runBlocking {
        val state = newState()
        state.addPhotos { List(PhotoLimits.MAX_PHOTOS) { photo() } }
        val before = state.content
        assertTrue(state.addPhotos { listOf(photo()) }.isFailure)
        assertEquals(before, state.content)
        assertTrue(state.lastSavedContent.photos.isEmpty())

        val sizeState = newState()
        val big = List(6) { photo().copy(byteSize = PhotoLimits.MAX_BYTES_PER_PHOTO) }
        assertTrue(sizeState.addPhotos { big }.isFailure)
        assertTrue(sizeState.content.photos.isEmpty())

        val invalid = photo().copy(width = 10_000, height = 10_000)
        assertTrue(sizeState.addPhotos { listOf(invalid) }.isFailure)
        assertTrue(sizeState.content.photos.isEmpty())
    }

    @Test
    fun delayedSavePersistsPhotosAndConfirmsExactlyTheSnapshot() = runBlocking {
        val repository = MemoryRepository()
        val state = newState()
        val attached = listOf(photo(), photo())
        state.addPhotos { attached }
        repository.writeGate = CompletableDeferred()
        val snapshot = state.content
        val baseline = state.lastSavedContent

        val save = async(start = CoroutineStart.UNDISPATCHED) { state.save(repository) }
        assertFalse(state.removePhoto(attached.first().id))
        assertFalse(state.beginPhotoRequest("picker"))
        assertTrue(state.addPhotos { listOf(photo()) }.isFailure)
        assertEquals(baseline, state.lastSavedContent)
        assertEquals(snapshot, state.content)

        repository.writeGate!!.complete(Unit)
        assertTrue(save.await()!!.isSuccess)
        assertEquals(attached, repository.drafts.values.single().photos)
        assertEquals(snapshot, state.content)
        assertEquals(snapshot, state.lastSavedContent)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun failedSaveRetainsAttachedPhotosAndThePreviousReference() = runBlocking {
        val repository = MemoryRepository()
        repository.writeFailure = IllegalStateException("Sin espacio")
        val state = newState()
        state.addPhotos { listOf(photo()) }
        val content = state.content
        val reference = state.lastSavedContent

        assertTrue(state.save(repository)!!.isFailure)

        assertEquals(content, state.content)
        assertEquals(reference, state.lastSavedContent)
        assertTrue(state.hasUnsavedChanges)
        assertTrue(state.canEdit)
        assertNull(state.draftId)
    }

    @Test
    fun externalCaptureRestoresFieldsPhotosReferenceAndRequestAcrossStateReconstruction() = runBlocking {
        val handle = SavedStateHandle()
        val saved = photo()
        val draft = InspectionDraft(id = "restorable", observations = "Guardado", photos = listOf(saved))
        val repository = MemoryRepository(draft)
        val state = InspectionFormState(handle).apply { open(draft.id) }
        state.loadIfNeeded(repository)
        val attached = photo()
        state.addPhotos { listOf(attached) }
        state.updateContent(state.content.copy(observations = "Sin guardar"))
        assertTrue(state.beginPhotoRequest("camera"))
        val captureId = UUID.randomUUID().toString()
        assertTrue(state.setPendingCapture(captureId))
        val restored = InspectionFormState(copyHandle(handle))

        restored.ensureOpened(draft.id)
        restored.loadIfNeeded(repository)

        assertEquals(1, repository.reads)
        assertEquals(state.content, restored.content)
        assertEquals(state.lastSavedContent, restored.lastSavedContent)
        assertEquals("camera", restored.externalPhotoRequest)
        assertEquals(state.photoSessionId, restored.photoSessionId)
        assertEquals(captureId, restored.pendingCaptureId)
        assertTrue(restored.hasUnsavedChanges)
        assertFalse(restored.canEdit)
        assertFalse(restored.canLeave)

        restored.finishPhotoRequest()
        assertTrue(restored.canEdit)
        assertNull(restored.pendingCaptureId)
        assertNull(restored.externalPhotoRequest)
        assertTrue(restored.hasUnsavedChanges)
    }

    @Test
    fun newUnsavedFormRestoresThroughPickerCancellationWithoutCreatingARow() = runBlocking {
        val handle = SavedStateHandle()
        val state = InspectionFormState(handle).apply { open(null) }
        state.updateContent(state.content.copy(containerIdentifier = "CSQU3054383", observations = "Pendiente"))
        state.addPhotos { listOf(photo()) }
        state.beginPhotoRequest("picker")
        val repository = MemoryRepository()
        val restored = InspectionFormState(copyHandle(handle))

        restored.ensureOpened(null)
        restored.finishPhotoRequest()
        restored.loadIfNeeded(repository)

        assertEquals(state.content, restored.content)
        assertEquals(state.lastSavedContent, restored.lastSavedContent)
        assertEquals(state.photoSessionId, restored.photoSessionId)
        assertNull(restored.draftId)
        assertTrue(restored.hasUnsavedChanges)
        assertTrue(restored.canEdit)
        assertEquals(0, repository.reads)
        assertEquals(0, repository.creates)
    }

    @Test
    fun failedCaptureCanRetainItsPrivateTemporaryIdentityForRetry() {
        val handle = SavedStateHandle()
        val state = InspectionFormState(handle).apply { open(null) }
        state.beginPhotoRequest("camera", "private-capture")

        state.finishPhotoRequest(clearCapture = false)
        state.reportPhotoError("No se pudo incorporar la captura")
        val restored = InspectionFormState(copyHandle(handle))

        assertEquals("private-capture", restored.pendingCaptureId)
        assertNull(restored.externalPhotoRequest)
        assertTrue(restored.canEdit)
        assertEquals("No se pudo incorporar la captura", restored.photoErrorMessage)
        restored.setPendingCapture(null)
        assertNull(restored.pendingCaptureId)
    }

    @Test
    fun captureRetryAloneRequiresExitWarningUntilExplicitlyDiscarded() {
        val state = newState()
        val reference = state.lastSavedContent
        assertTrue(state.beginPhotoRequest("camera", UUID.randomUUID().toString()))
        state.finishPhotoRequest(clearCapture = false)
        state.reportPhotoError("Captura pendiente de copiar")

        assertEquals(reference, state.content)
        assertTrue(state.hasUnsavedChanges)
        assertTrue(state.canEdit)
        assertTrue(state.canLeave)
        assertTrue(state.discardChanges().isEmpty())
        assertNull(state.pendingCaptureId)
        assertEquals(reference, state.content)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun pendingSelectionRequiresExitWarningWithoutBlockingEditingEvenBeforeAnyCopyExists() {
        val state = newState()
        val reference = state.lastSavedContent

        state.setPendingPhotoSelection(true)

        assertTrue(state.hasUnsavedChanges)
        assertEquals(reference, state.content)
        assertTrue(state.canEdit)
        assertTrue(state.canLeave)
        state.setPendingPhotoSelection(false)
        assertFalse(state.hasUnsavedChanges)
        state.setPendingPhotoSelection(true)
        state.discardChanges()
        assertFalse(state.hasPendingPhotoSelection)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun captureAndSelectionRetryIdentitiesSurviveSavedStateReconstruction() {
        val handle = SavedStateHandle()
        val state = InspectionFormState(handle).apply { open(null) }
        val captureId = UUID.randomUUID().toString()
        state.setPendingCapture(captureId)
        state.setPendingPhotoSelection(true)

        val restoredHandle = copyHandle(handle)
        val restored = InspectionFormState(restoredHandle)
        restored.ensureOpened(null)

        assertEquals(captureId, restored.pendingCaptureId)
        assertEquals(state.photoSessionId, restored.photoSessionId)
        assertTrue(restored.hasPendingPhotoSelection)
        assertTrue(restored.hasUnsavedChanges)
        assertTrue(restored.canEdit)
        restored.discardChanges()
        val discarded = InspectionFormState(copyHandle(restoredHandle))
        assertNull(discarded.pendingCaptureId)
        assertFalse(discarded.hasPendingPhotoSelection)
        assertFalse(discarded.hasUnsavedChanges)
    }

    @Test
    fun committedCreateReconcilesCandidateIdentityAfterProcessReconstructionWithoutDuplicating() = runBlocking {
        val handle = SavedStateHandle()
        val state = InspectionFormState(handle).apply { open(null) }
        state.addPhotos { listOf(photo()) }
        state.updateContent(state.content.copy(observations = "Instantánea"))
        val repository = MemoryRepository()
        repository.afterCommitGate = CompletableDeferred()
        val save = async(start = CoroutineStart.UNDISPATCHED) { state.save(repository) }
        assertEquals(1, repository.drafts.size)
        val restored = InspectionFormState(copyHandle(handle))
        assertFalse(restored.canEdit)

        restored.loadIfNeeded(repository)

        assertEquals(repository.drafts.values.single().id, restored.draftId)
        assertEquals(state.content, restored.content)
        assertEquals(restored.content, restored.lastSavedContent)
        assertFalse(restored.hasUnsavedChanges)
        assertTrue(restored.canEdit)
        assertEquals(1, repository.creates)
        assertTrue(restored.consumeSaveResult()!!.isSuccess)
        save.cancel()
        save.join()
    }

    @Test
    fun interruptedUncommittedCreateRestoresPendingContentAndAllowsExplicitRetry() = runBlocking {
        val handle = SavedStateHandle()
        val state = InspectionFormState(handle).apply { open(null) }
        state.addPhotos { listOf(photo()) }
        val repository = MemoryRepository()
        repository.writeGate = CompletableDeferred()
        val save = async(start = CoroutineStart.UNDISPATCHED) { state.save(repository) }
        val restored = InspectionFormState(copyHandle(handle))

        restored.loadIfNeeded(repository)

        assertNull(restored.draftId)
        assertEquals(state.content, restored.content)
        assertTrue(restored.hasUnsavedChanges)
        assertTrue(restored.canEdit)
        assertTrue(restored.consumeSaveResult()!!.isFailure)
        assertEquals(1, repository.creates)
        assertTrue(repository.drafts.isEmpty())
        save.cancel()
        save.join()
    }

    @Test
    fun restoreUsesOnlyBundleCompatibleMetadataWithoutImagePayloads() = runBlocking {
        val handle = SavedStateHandle()
        val state = InspectionFormState(handle).apply { open(null) }
        state.addPhotos { List(4) { photo() } }
        assertTrue(handle.keys().isNotEmpty())
        handle.keys().forEach { key ->
            val value = handle.get<Any?>(key)
            assertTrue(value == null || value is String || value is Boolean || value is Long ||
                (value is ArrayList<*> && value.all { it is String }))
        }
        assertNotNull(handle.get<ArrayList<String>>("content.photos"))
    }

    private fun newState() = InspectionFormState().apply { open(null) }

    private fun photo() = DraftPhoto(UUID.randomUUID().toString(), "image/jpeg", 4096L, 640, 480)

    private fun copyHandle(source: SavedStateHandle): SavedStateHandle = SavedStateHandle(
        source.keys().associateWith { key ->
            val value = source.get<Any?>(key)
            if (value is ArrayList<*>) ArrayList(value) else value
        }
    )

    private class MemoryRepository(vararg initial: InspectionDraft) : InspectionDraftRepository {
        val drafts = initial.associateBy { it.id }.toMutableMap()
        var creates = 0
        var reads = 0
        var writeGate: CompletableDeferred<Unit>? = null
        var afterCommitGate: CompletableDeferred<Unit>? = null
        var writeFailure: Exception? = null

        override suspend fun createDraft(draft: InspectionDraft): Result<Boolean> {
            creates++
            writeGate?.await()
            writeFailure?.let { return Result.failure(it) }
            if (drafts.containsKey(draft.id)) return Result.failure(IllegalStateException("ID duplicado"))
            drafts[draft.id] = draft
            afterCommitGate?.await()
            return Result.success(true)
        }

        override suspend fun updateDraft(draft: InspectionDraft): Result<Boolean> {
            writeGate?.await()
            writeFailure?.let { return Result.failure(it) }
            if (!drafts.containsKey(draft.id)) return Result.failure(DraftNotFoundException(draft.id))
            drafts[draft.id] = draft
            afterCommitGate?.await()
            return Result.success(true)
        }

        override suspend fun getDraftById(id: String): Result<InspectionDraft?> {
            reads++
            return Result.success(drafts[id])
        }

        override suspend fun getAllDrafts() = Result.success(drafts.values.toList())
        override suspend fun deleteDraft(id: String) = Result.success(drafts.remove(id) != null)
    }
}
