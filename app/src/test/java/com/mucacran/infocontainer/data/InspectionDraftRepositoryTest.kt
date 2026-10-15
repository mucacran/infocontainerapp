package com.mucacran.infocontainer.data

import com.mucacran.infocontainer.model.InspectionDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InspectionDraftRepositoryTest {

    private lateinit var repository: InspectionDraftRepository

    @Before
    fun setUp() {
        repository = FakeInspectionDraftRepository()
    }

    @Test
    fun saveDraft_savesNewIncompleteDraftSuccessfully() = runBlocking {
        val incompleteDraft = InspectionDraft(
            id = "draft-101",
            containerIdentifier = "CSQU", // Incompleto
            isoCode = "",
            inspectionType = "", // Sin motivo
            observations = "Borrador inicial en proceso"
        )

        val savedResult = repository.createDraft(incompleteDraft)
        assertTrue(savedResult.isSuccess)

        val retrievedResult = repository.getDraftById("draft-101")
        val retrieved = retrievedResult.getOrNull()
        assertNotNull(retrieved)
        assertEquals("draft-101", retrieved?.id)
        assertEquals("CSQU", retrieved?.containerIdentifier)
        assertEquals("Borrador inicial en proceso", retrieved?.observations)
    }

    @Test
    fun saveDraft_updatingExistingDraftDoesNotDuplicateRecord() = runBlocking {
        val initialDraft = InspectionDraft(
            id = "draft-202",
            containerIdentifier = "CSQU3054383",
            inspectionType = "inventory"
        )

        repository.createDraft(initialDraft)
        assertEquals(1, repository.getAllDrafts().getOrNull()?.size)

        // Actualización del mismo borrador
        val updatedDraft = initialDraft.copy(
            inspectionType = "sale",
            observations = "Actualizado a Venta"
        )

        repository.updateDraft(updatedDraft)

        val allDrafts = repository.getAllDrafts().getOrNull() ?: emptyList()
        assertEquals(1, allDrafts.size) // No se duplicó
        assertEquals("sale", allDrafts[0].inspectionType)
        assertEquals("Actualizado a Venta", allDrafts[0].observations)
    }

    @Test
    fun deleteDraft_removesTargetDraft() = runBlocking {
        val draft1 = InspectionDraft(id = "draft-1")
        val draft2 = InspectionDraft(id = "draft-2")

        repository.createDraft(draft1)
        repository.createDraft(draft2)
        assertEquals(2, repository.getAllDrafts().getOrNull()?.size)

        val deleteResult = repository.deleteDraft("draft-1")
        assertTrue(deleteResult.isSuccess)
        assertEquals(1, repository.getAllDrafts().getOrNull()?.size)
        assertNull(repository.getDraftById("draft-1").getOrNull())
        assertNotNull(repository.getDraftById("draft-2").getOrNull())
    }
}

class FakeInspectionDraftRepository : InspectionDraftRepository {
    private val memoryStore = mutableMapOf<String, InspectionDraft>()

    override suspend fun createDraft(draft: InspectionDraft): Result<Boolean> {
        if (memoryStore.containsKey(draft.id)) return Result.failure(IllegalStateException("ID existente"))
        memoryStore[draft.id] = draft
        return Result.success(true)
    }

    override suspend fun updateDraft(draft: InspectionDraft): Result<Boolean> {
        if (!memoryStore.containsKey(draft.id)) return Result.failure(DraftNotFoundException(draft.id))
        memoryStore[draft.id] = draft
        return Result.success(true)
    }

    override suspend fun getDraftById(id: String): Result<InspectionDraft?> {
        return Result.success(memoryStore[id])
    }

    override suspend fun getAllDrafts(): Result<List<InspectionDraft>> {
        return Result.success(memoryStore.values.sortedByDescending { it.updatedAt })
    }

    override suspend fun deleteDraft(id: String): Result<Boolean> {
        val removed = memoryStore.remove(id) != null
        return if (removed) Result.success(true) else Result.failure(Exception("Not found"))
    }
}
