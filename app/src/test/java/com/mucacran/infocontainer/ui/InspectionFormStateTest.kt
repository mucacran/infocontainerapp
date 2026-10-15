package com.mucacran.infocontainer.ui

import com.mucacran.infocontainer.data.DraftNotFoundException
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.model.InspectionDraft
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

class InspectionFormStateTest {
    @Test
    fun loadingBlocksSavingAndEditingUntilExpectedDraftArrives() = runBlocking {
        val draft = existingDraft()
        val repository = ControlledDraftRepository(draft)
        val readGate = CompletableDeferred<Unit>()
        repository.readGate = readGate
        val state = stateFor(draft.id)

        val load = async(start = CoroutineStart.UNDISPATCHED) { state.loadIfNeeded(repository) }
        assertTrue(state.loadState is FormLoadState.Loading)
        assertFalse(state.canEdit)
        assertFalse(state.updateContent(FormContent(observations = "No debe entrar")))
        assertNull(state.save(repository))
        assertEquals(0, repository.createCalls + repository.updateCalls)

        readGate.complete(Unit)
        load.await()
        assertTrue(state.loadState is FormLoadState.Success)
        assertEquals(draft.observations, state.content.observations)
        assertEquals(draft.createdAt, state.createdAt)
        assertEquals(state.content, state.lastSavedContent)
        assertFalse(state.hasUnsavedChanges)
        assertTrue(state.canEdit)
    }

    @Test
    fun readErrorBlocksSavingAndExplicitRetryLoadsData() = runBlocking {
        val draft = existingDraft()
        val repository = ControlledDraftRepository(draft)
        repository.readError = IllegalStateException("Lectura fallida")
        val state = stateFor(draft.id)

        state.loadIfNeeded(repository)
        assertEquals(FormLoadState.Error("Lectura fallida"), state.loadState)
        assertFalse(state.canEdit)
        assertNull(state.save(repository))
        state.loadIfNeeded(repository)
        assertEquals(1, repository.readCalls)
        assertEquals(0, repository.createCalls + repository.updateCalls)

        repository.readError = null
        state.retryLoad(repository)
        assertEquals(2, repository.readCalls)
        assertEquals(draft.observations, state.content.observations)
        assertTrue(state.canEdit)
    }

    @Test
    fun missingDraftBlocksSavingWithoutCreatingAlternative() = runBlocking {
        val repository = ControlledDraftRepository()
        val state = stateFor("missing-id")

        state.loadIfNeeded(repository)

        assertTrue(state.loadState is FormLoadState.NotFound)
        assertEquals("missing-id", state.draftId)
        assertFalse(state.updateContent(FormContent(observations = "Alternativa")))
        assertNull(state.save(repository))
        assertEquals(0, repository.createCalls + repository.updateCalls)
        assertTrue(repository.store.isEmpty())
    }

    @Test
    fun contentChangesAndRevertsUseTheRealSavedReference() = runBlocking {
        val draft = existingDraft().copy(observations = "Aa")
        val repository = ControlledDraftRepository(draft)
        val state = stateFor(draft.id)
        state.loadIfNeeded(repository)
        val saved = state.lastSavedContent

        state.updateContent(state.content.copy(observations = "BB"))
        assertTrue(state.hasUnsavedChanges)
        assertEquals(saved, state.lastSavedContent)

        state.updateContent(saved)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun delayedSaveBlocksEditingNavigationAndDuplicateWrites() = runBlocking {
        val repository = ControlledDraftRepository()
        val writeGate = CompletableDeferred<Unit>()
        repository.writeGate = writeGate
        val state = stateFor()
        state.updateContent(state.content.copy(observations = "Instantánea enviada"))
        val snapshot = state.content
        val oldReference = state.lastSavedContent

        val save = async(start = CoroutineStart.UNDISPATCHED) { state.save(repository) }
        assertTrue(state.isSaving)
        assertFalse(state.canEdit)
        assertFalse(state.canLeave)
        assertFalse(state.updateContent(snapshot.copy(observations = "Cambio durante I/O")))
        assertFalse(state.open("other-id"))
        assertNull(state.save(repository))
        assertEquals(snapshot, state.content)
        assertEquals(oldReference, state.lastSavedContent)
        assertTrue(state.hasUnsavedChanges)
        assertEquals(1, repository.createCalls)
        assertEquals(0, repository.updateCalls)
        assertEquals(0, state.completedSaveCount)
        assertNull(state.pendingSaveResult)

        writeGate.complete(Unit)
        assertTrue(save.await()!!.isSuccess)
        assertFalse(state.isSaving)
        assertTrue(state.canLeave)
        assertEquals(snapshot, state.content)
        assertEquals(snapshot, state.lastSavedContent)
        assertEquals(snapshot.observations, repository.store.values.single().observations)
        assertFalse(state.hasUnsavedChanges)
        assertEquals(1, state.completedSaveCount)
        assertTrue(state.consumeSaveResult()!!.isSuccess)
        assertNull(state.consumeSaveResult())
        assertEquals(1, state.completedSaveCount)
    }

    @Test
    fun successfulSaveUsesExactlyTheNormalizedPersistedSnapshot() = runBlocking {
        val repository = ControlledDraftRepository()
        val state = stateFor()
        state.updateContent(
            state.content.copy(
                containerIdentifier = " csqu-3054383 ",
                isoCode = " 45-g1 ",
                inspectionType = "inventory",
                observations = "Conservar observaciones"
            )
        )

        assertTrue(state.save(repository)!!.isSuccess)

        val persisted = repository.store.values.single()
        assertEquals("CSQU3054383", persisted.containerIdentifier)
        assertEquals("45G1", persisted.isoCode)
        assertEquals(persisted.containerIdentifier, state.content.containerIdentifier)
        assertEquals(persisted.isoCode, state.content.isoCode)
        assertEquals(persisted.observations, state.content.observations)
        assertEquals(state.content, state.lastSavedContent)
        assertEquals(persisted.id, state.draftId)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun failedCreatePreservesContentReferenceAndAllowsFurtherEditing() = runBlocking {
        val repository = ControlledDraftRepository()
        repository.writeError = IllegalStateException("Escritura fallida")
        val state = stateFor()
        state.updateContent(state.content.copy(containerIdentifier = " csqu-3054383 ", observations = "Pendiente"))
        val pending = state.content
        val savedReference = state.lastSavedContent

        val result = state.save(repository)!!

        assertTrue(result.isFailure)
        assertEquals(pending, state.content)
        assertEquals(savedReference, state.lastSavedContent)
        assertNull(state.draftId)
        assertTrue(state.hasUnsavedChanges)
        assertFalse(state.isSaving)
        assertTrue(state.updateContent(pending.copy(observations = "Corrección tras el error")))
        assertEquals("Corrección tras el error", state.content.observations)
        assertTrue(repository.store.isEmpty())
        assertEquals(1, state.completedSaveCount)
        assertTrue(state.consumeSaveResult()!!.isFailure)
    }

    @Test
    fun failedUpdateKeepsExpectedIdAndNeverFallsBackToCreate() = runBlocking {
        val draft = existingDraft()
        val repository = ControlledDraftRepository(draft)
        val state = stateFor(draft.id)
        state.loadIfNeeded(repository)
        state.updateContent(state.content.copy(observations = "Edición pendiente"))
        val pending = state.content
        val reference = state.lastSavedContent
        repository.store.remove(draft.id)

        val result = state.save(repository)!!

        assertTrue(result.exceptionOrNull() is DraftNotFoundException)
        assertEquals(draft.id, state.draftId)
        assertEquals(pending, state.content)
        assertEquals(reference, state.lastSavedContent)
        assertTrue(state.hasUnsavedChanges)
        assertTrue(state.canEdit)
        assertEquals(1, repository.updateCalls)
        assertEquals(0, repository.createCalls)
        assertTrue(repository.store.isEmpty())
    }

    @Test
    fun unsuccessfulBooleanDoesNotAdvanceSavedReference() = runBlocking {
        val repository = ControlledDraftRepository()
        repository.returnFalseFromWrite = true
        val state = stateFor()
        state.updateContent(state.content.copy(observations = "Pendiente"))
        val pending = state.content
        val reference = state.lastSavedContent

        assertTrue(state.save(repository)!!.isFailure)

        assertEquals(pending, state.content)
        assertEquals(reference, state.lastSavedContent)
        assertNull(state.draftId)
        assertTrue(state.hasUnsavedChanges)
        assertFalse(state.isSaving)
    }

    @Test
    fun firstSaveDoesNotReloadOrOverwriteSubsequentEditsAndNextSaveUpdates() = runBlocking {
        val repository = ControlledDraftRepository()
        val state = stateFor()
        state.updateContent(state.content.copy(observations = "Primera versión"))
        assertTrue(state.save(repository)!!.isSuccess)
        val savedId = state.draftId
        assertNotNull(savedId)
        state.updateContent(state.content.copy(observations = "Segunda versión pendiente"))

        state.ensureOpened(null)
        state.loadIfNeeded(repository)

        assertEquals(0, repository.readCalls)
        assertEquals(savedId, state.draftId)
        assertEquals("Segunda versión pendiente", state.content.observations)
        assertEquals("Primera versión", state.lastSavedContent.observations)
        assertTrue(state.hasUnsavedChanges)
        assertTrue(state.save(repository)!!.isSuccess)
        assertEquals(1, repository.createCalls)
        assertEquals(1, repository.updateCalls)
        assertEquals(1, repository.store.size)
        assertEquals("Segunda versión pendiente", repository.store[savedId]?.observations)
        assertFalse(state.hasUnsavedChanges)
        assertEquals(2, state.completedSaveCount)
    }

    @Test
    fun retainedStateKeepsUnsavedContentAndReferenceWhenScreenEnsuresItIsOpen() = runBlocking {
        val draft = existingDraft()
        val repository = ControlledDraftRepository(draft)
        val state = stateFor(draft.id)
        state.loadIfNeeded(repository)
        state.updateContent(state.content.copy(observations = "Edición sin guardar"))
        val pending = state.content
        val reference = state.lastSavedContent

        state.ensureOpened(draft.id)
        state.loadIfNeeded(repository)

        assertEquals(1, repository.readCalls)
        assertEquals(pending, state.content)
        assertEquals(reference, state.lastSavedContent)
        assertTrue(state.hasUnsavedChanges)
    }

    @Test
    fun newExplicitNavigationResetsThePreviousForm() = runBlocking {
        val repository = ControlledDraftRepository()
        val state = stateFor()
        state.updateContent(state.content.copy(observations = "Borrador anterior"))
        state.save(repository)
        assertNotNull(state.pendingSaveResult)

        assertTrue(state.open(null))

        assertEquals("", state.content.observations)
        assertEquals(state.content, state.lastSavedContent)
        assertFalse(state.hasUnsavedChanges)
        assertNull(state.draftId)
        assertNull(state.pendingSaveResult)
        assertEquals(1, state.completedSaveCount)
    }

    @Test
    fun anOlderLoadCannotReplaceANewlyOpenedForm() = runBlocking {
        val draft = existingDraft()
        val repository = ControlledDraftRepository(draft)
        val readGate = CompletableDeferred<Unit>()
        repository.readGate = readGate
        val state = stateFor(draft.id)
        val load = async(start = CoroutineStart.UNDISPATCHED) { state.loadIfNeeded(repository) }

        assertTrue(state.open(null))
        state.updateContent(state.content.copy(observations = "Nuevo formulario"))
        readGate.complete(Unit)
        load.await()

        assertTrue(state.loadState is FormLoadState.NewDraft)
        assertEquals("Nuevo formulario", state.content.observations)
        assertNull(state.draftId)
        assertTrue(state.hasUnsavedChanges)
    }

    @Test
    fun cancelledWriteReleasesTheGuardPreservingContentWithoutPublishingAFailure() = runBlocking {
        val repository = ControlledDraftRepository()
        repository.writeGate = CompletableDeferred()
        val state = stateFor()
        state.updateContent(state.content.copy(observations = "Pendiente al cancelar"))
        val pending = state.content
        val reference = state.lastSavedContent
        val save = async(start = CoroutineStart.UNDISPATCHED) { state.save(repository) }
        assertTrue(state.isSaving)

        save.cancel()
        save.join()

        assertFalse(state.isSaving)
        assertTrue(state.canEdit)
        assertEquals(pending, state.content)
        assertEquals(reference, state.lastSavedContent)
        assertTrue(state.hasUnsavedChanges)
        assertEquals(0, state.completedSaveCount)
        assertNull(state.pendingSaveResult)
    }

    private fun stateFor(id: String? = null) = InspectionFormState().apply { open(id) }

    private fun existingDraft() = InspectionDraft(
        id = "draft-1",
        containerIdentifier = "CSQU3054383",
        isoCode = "45G1",
        inspectionType = "inventory",
        inspectedAt = "2026-10-15T10:00:00-05:00",
        observations = "Guardado previamente",
        createdAt = 100L,
        updatedAt = 200L
    )

    private class ControlledDraftRepository(vararg drafts: InspectionDraft) : InspectionDraftRepository {
        val store = drafts.associateBy { it.id }.toMutableMap()
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var readError: Exception? = null
        var writeError: Exception? = null
        var returnFalseFromWrite = false
        var readCalls = 0
        var createCalls = 0
        var updateCalls = 0

        override suspend fun createDraft(draft: InspectionDraft): Result<Boolean> {
            createCalls++
            writeGate?.await()
            writeError?.let { return Result.failure(it) }
            if (returnFalseFromWrite) return Result.success(false)
            if (store.containsKey(draft.id)) return Result.failure(IllegalStateException("ID duplicado"))
            store[draft.id] = draft
            return Result.success(true)
        }

        override suspend fun updateDraft(draft: InspectionDraft): Result<Boolean> {
            updateCalls++
            writeGate?.await()
            writeError?.let { return Result.failure(it) }
            if (returnFalseFromWrite) return Result.success(false)
            if (!store.containsKey(draft.id)) return Result.failure(DraftNotFoundException(draft.id))
            store[draft.id] = draft
            return Result.success(true)
        }

        override suspend fun getDraftById(id: String): Result<InspectionDraft?> {
            readCalls++
            readGate?.await()
            readError?.let { return Result.failure(it) }
            return Result.success(store[id])
        }

        override suspend fun getAllDrafts(): Result<List<InspectionDraft>> = Result.success(store.values.toList())

        override suspend fun deleteDraft(id: String): Result<Boolean> = Result.success(store.remove(id) != null)
    }
}
