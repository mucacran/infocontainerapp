package com.mucacran.infocontainer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.model.PhotoLimits
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqliteRealDatabaseTest {

    private lateinit var context: Context
    private lateinit var testDbName: String
    private val openedHelpers = mutableListOf<SQLiteOpenHelper>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        testDbName = "infocontainer_real_test_${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        openedHelpers.forEach { it.close() }
        openedHelpers.clear()
    }

    private fun newHelper() = InspectionDatabaseHelper(context, testDbName).also {
        openedHelpers.add(it)
    }

    @Test
    fun realSqlite_saveCloseReopenAndRetrieve_preservesData() = runBlocking {
        val dbHelper1 = newHelper()
        val repo1 = SqliteInspectionDraftRepository(dbHelper1)

        val draft1 = InspectionDraft(
            id = "real-sqlite-001",
            containerIdentifier = "CSQU3054383",
            isoCode = "45G1",
            inspectionType = "inventory",
            inspectedAt = "2025-02-23T15:30:00-05:00",
            observations = "Unicode test: 📦 Contenedor sellado 🛳️",
            createdAt = 100L,
            updatedAt = 200L
        )

        // 1. Guardar en SQLite real
        val saveResult = repo1.createDraft(draft1)
        assertTrue(saveResult.isSuccess)

        // 2. CERRAR conexión explícitamente para garantizar descarga a disco
        dbHelper1.close()

        // 3. REABRIR la conexión desde el archivo SQLite
        val dbHelper2 = newHelper()
        val repo2 = SqliteInspectionDraftRepository(dbHelper2)

        val retrievedResult = repo2.getDraftById("real-sqlite-001")
        assertTrue(retrievedResult.isSuccess)

        val retrieved = retrievedResult.getOrNull()
        assertNotNull(retrieved)
        assertEquals("real-sqlite-001", retrieved?.id)
        assertEquals("CSQU3054383", retrieved?.containerIdentifier)
        assertEquals("45G1", retrieved?.isoCode)
        assertEquals("inventory", retrieved?.inspectionType)
        assertEquals("Unicode test: 📦 Contenedor sellado 🛳️", retrieved?.observations)
        assertEquals(draft1, retrieved)

        dbHelper2.close()
    }

    @Test
    fun realSqlite_updateDoesNotDuplicateRecord() = runBlocking {
        val dbHelper = newHelper()
        val repo = SqliteInspectionDraftRepository(dbHelper)

        val initialDraft = InspectionDraft(
            id = "real-sqlite-002",
            containerIdentifier = "CSQU3054383",
            inspectionType = "inventory",
            createdAt = 100L,
            updatedAt = 200L
        )

        repo.createDraft(initialDraft).getOrThrow()

        val allFirst = repo.getAllDrafts().getOrNull()
        assertEquals(1, allFirst?.size)

        // Actualizar el registro existente
        val updatedDraft = initialDraft.copy(
            inspectionType = "delivery",
            observations = "Entregado a cliente",
            updatedAt = 300L
        )
        repo.updateDraft(updatedDraft).getOrThrow()

        dbHelper.close()

        // Reabrir conexión
        val dbHelperReopened = newHelper()
        val repoReopened = SqliteInspectionDraftRepository(dbHelperReopened)

        val allSecond = repoReopened.getAllDrafts().getOrNull()
        assertEquals(1, allSecond?.size) // No se duplicó
        assertEquals("delivery", allSecond?.get(0)?.inspectionType)
        assertEquals("Entregado a cliente", allSecond?.get(0)?.observations)
        assertEquals(updatedDraft, allSecond?.single())

        dbHelperReopened.close()
    }

    @Test
    fun realSqlite_deleteRemovesRecord() = runBlocking {
        val dbHelper = newHelper()
        val repo = SqliteInspectionDraftRepository(dbHelper)

        val draft = InspectionDraft(id = "real-sqlite-003", containerIdentifier = "MSCU1234566")
        repo.createDraft(draft).getOrThrow()

        val deleteResult = repo.deleteDraft("real-sqlite-003")
        assertTrue(deleteResult.isSuccess)

        val retrieved = repo.getDraftById("real-sqlite-003").getOrNull()
        assertNull(retrieved)

        dbHelper.close()
    }

    @Test
    fun realSqlite_deletedAfterLoad_updateFailsWithoutRecreatingDraft() = runBlocking {
        val dbHelper = newHelper()
        val repo = SqliteInspectionDraftRepository(dbHelper)
        val draft = InspectionDraft(id = "deleted-after-load", containerIdentifier = "CSQU3054383")
        repo.createDraft(draft).getOrThrow()
        val loaded = repo.getDraftById(draft.id).getOrThrow()!!

        repo.deleteDraft(draft.id).getOrThrow()
        val result = repo.updateDraft(loaded.copy(observations = "Cambio pendiente"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DraftNotFoundException)
        assertEquals(draft.id, (result.exceptionOrNull() as DraftNotFoundException).draftId)
        assertNull(repo.getDraftById(draft.id).getOrThrow())
        assertTrue(repo.getAllDrafts().getOrThrow().isEmpty())
    }

    @Test
    fun realSqlite_duplicateCreation_failsWithoutReplacingOriginal() = runBlocking {
        val dbHelper = newHelper()
        val repo = SqliteInspectionDraftRepository(dbHelper)
        val original = InspectionDraft(
            id = "duplicate-create",
            containerIdentifier = "CSQU3054383",
            observations = "Original",
            createdAt = 100L,
            updatedAt = 200L
        )
        repo.createDraft(original).getOrThrow()

        val result = repo.createDraft(original.copy(observations = "Reemplazo", updatedAt = 300L))

        assertTrue(result.isFailure)
        assertEquals(original, repo.getDraftById(original.id).getOrThrow())
        assertEquals(listOf(original), repo.getAllDrafts().getOrThrow())
    }

    @Test
    fun realSqlite_photosSurviveCloseAndReopenInTheirOriginalOrder() = runBlocking {
        val helper = newHelper()
        // File validation is supplied by LocalPhotoStore in production; this test exercises SQLite.
        val repo = SqliteInspectionDraftRepository(helper) { true }
        val draft = InspectionDraft(
            observations = "Fotos del mismo contenedor",
            photos = listOf(photo(), photo(), photo())
        )
        repo.createDraft(draft).getOrThrow()
        helper.close()

        val reopenedRepo = SqliteInspectionDraftRepository(newHelper()) { true }
        assertEquals(draft, reopenedRepo.getDraftById(draft.id).getOrThrow())
        assertEquals(listOf(draft), reopenedRepo.getAllDrafts().getOrThrow())
        draft.photos.forEach { assertTrue(reopenedRepo.isPhotoReferenced(it.id).getOrThrow()) }
    }

    @Test
    fun realSqlite_removePhotoAndDeleteDraft_removeOnlyTheirLinks() = runBlocking {
        val helper = newHelper()
        val repo = SqliteInspectionDraftRepository(helper) { true }
        val first = photo()
        val second = photo()
        val otherPhoto = photo()
        val draft = InspectionDraft(photos = listOf(first, second))
        val other = InspectionDraft(photos = listOf(otherPhoto))
        repo.createDraft(draft).getOrThrow()
        repo.createDraft(other).getOrThrow()

        repo.updateDraft(draft.copy(photos = listOf(second))).getOrThrow()
        assertFalse(repo.isPhotoReferenced(first.id).getOrThrow())
        assertTrue(repo.isPhotoReferenced(second.id).getOrThrow())
        assertEquals(listOf(second), repo.getDraftById(draft.id).getOrThrow()!!.photos)

        repo.deleteDraft(draft.id).getOrThrow()
        assertFalse(repo.isPhotoReferenced(second.id).getOrThrow())
        assertTrue(repo.isPhotoReferenced(otherPhoto.id).getOrThrow())
        assertEquals(other, repo.getDraftById(other.id).getOrThrow())
        assertEquals(0L, helper.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { it.count.toLong() })
    }

    @Test
    fun realSqlite_missingPhotoDuringCreation_rollsBackDraftAndAllLinks() = runBlocking {
        val first = photo()
        val missing = photo()
        val repo = SqliteInspectionDraftRepository(newHelper()) { it.id == first.id }
        val draft = InspectionDraft(photos = listOf(first, missing))

        assertTrue(repo.createDraft(draft).isFailure)
        assertNull(repo.getDraftById(draft.id).getOrThrow())
        assertFalse(repo.isPhotoReferenced(first.id).getOrThrow())
        assertFalse(repo.isPhotoReferenced(missing.id).getOrThrow())
    }

    @Test
    fun realSqlite_missingPhotoDuringUpdate_preservesOriginalFieldsAndAttachments() = runBlocking {
        val first = photo()
        val second = photo()
        val missing = photo()
        val repo = SqliteInspectionDraftRepository(newHelper()) { it.id != missing.id }
        val original = InspectionDraft(observations = "Original", photos = listOf(first, second))
        repo.createDraft(original).getOrThrow()

        val attempted = original.copy(observations = "Pendiente", photos = listOf(first, missing))
        assertTrue(repo.updateDraft(attempted).isFailure)
        assertEquals(original, repo.getDraftById(original.id).getOrThrow())
        assertTrue(repo.isPhotoReferenced(second.id).getOrThrow())
        assertFalse(repo.isPhotoReferenced(missing.id).getOrThrow())
    }

    @Test
    fun realSqlite_photoCannotBeStolenByAnotherDraftOnCreateOrUpdate() = runBlocking {
        val repo = SqliteInspectionDraftRepository(newHelper()) { true }
        val original = InspectionDraft(photos = listOf(photo()))
        val other = InspectionDraft(photos = listOf(photo()))
        repo.createDraft(original).getOrThrow()
        repo.createDraft(other).getOrThrow()
        val newDraft = InspectionDraft(photos = original.photos)

        assertTrue(repo.createDraft(newDraft).isFailure)
        assertNull(repo.getDraftById(newDraft.id).getOrThrow())
        assertTrue(repo.updateDraft(other.copy(observations = "Cambio", photos = original.photos)).isFailure)
        assertEquals(original, repo.getDraftById(original.id).getOrThrow())
        assertEquals(other, repo.getDraftById(other.id).getOrThrow())
        assertTrue(repo.isPhotoReferenced(other.photos.single().id).getOrThrow())
    }

    @Test
    fun realSqlite_invalidOrExcessivePhotoMetadata_createsNoDraft() = runBlocking {
        val repo = SqliteInspectionDraftRepository(newHelper()) { true }
        val valid = photo()
        val cases = listOf(
            listOf(valid.copy(id = "../outside")),
            listOf(valid.copy(mimeType = "application/pdf")),
            listOf(valid.copy(byteSize = 0)),
            listOf(valid.copy(byteSize = PhotoLimits.MAX_BYTES_PER_PHOTO + 1)),
            listOf(valid.copy(width = 0)),
            listOf(valid.copy(width = 10_000, height = 10_000)),
            listOf(valid, valid),
            List(PhotoLimits.MAX_PHOTOS + 1) { photo() },
            List(6) { photo().copy(byteSize = PhotoLimits.MAX_BYTES_PER_PHOTO) }
        )
        cases.forEach { photos ->
            val draft = InspectionDraft(photos = photos)
            assertTrue("Should reject $photos", repo.createDraft(draft).isFailure)
            assertNull(repo.getDraftById(draft.id).getOrThrow())
        }
        assertTrue(repo.getAllDrafts().getOrThrow().isEmpty())
    }

    @Test
    fun realSqlite_deletedDraftWithPhotos_updateDoesNotRecreateDraftOrAttachments() = runBlocking {
        val repo = SqliteInspectionDraftRepository(newHelper()) { true }
        val draft = InspectionDraft(photos = listOf(photo()))
        repo.createDraft(draft).getOrThrow()
        val loaded = repo.getDraftById(draft.id).getOrThrow()!!
        repo.deleteDraft(draft.id).getOrThrow()

        assertTrue(repo.updateDraft(loaded).exceptionOrNull() is DraftNotFoundException)
        assertNull(repo.getDraftById(draft.id).getOrThrow())
        assertFalse(repo.isPhotoReferenced(draft.photos.single().id).getOrThrow())
    }

    @Test
    fun realSqlite_v1ToV2OnOpen_preservesAllDraftFieldsAndAddsEmptyPhotoTable() = runBlocking {
        val historicalHelper = object : SQLiteOpenHelper(context, testDbName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                // Historical v1 fixture: intentionally independent of the production v2 onCreate.
                db.execSQL("""
                    CREATE TABLE inspection_drafts (
                        id TEXT PRIMARY KEY,
                        container_identifier TEXT NOT NULL,
                        iso_code TEXT NOT NULL,
                        inspection_type TEXT NOT NULL,
                        inspected_at TEXT NOT NULL,
                        observations TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("El fixture histórico solo crea v1")
            }
        }.also { openedHelpers.add(it) }
        val originals = listOf(
            InspectionDraft(
                id = "historical-draft-1", containerIdentifier = "CSQU3054383", isoCode = "45G1",
                inspectionType = "delivery", inspectedAt = "2025-02-23T15:30:00-05:00",
                observations = "Conservar 📦 áéíóú", createdAt = 123L, updatedAt = 456L
            ),
            InspectionDraft(
                id = "historical-draft-2", containerIdentifier = "", isoCode = "",
                inspectionType = "", inspectedAt = "2026-01-01T01:02:03-05:00",
                observations = "Incompleto", createdAt = 111L, updatedAt = 333L
            )
        )
        val historicalDb = historicalHelper.writableDatabase
        originals.forEach { draft ->
            historicalDb.insertOrThrow("inspection_drafts", null, ContentValues().apply {
                put("id", draft.id)
                put("container_identifier", draft.containerIdentifier)
                put("iso_code", draft.isoCode)
                put("inspection_type", draft.inspectionType)
                put("inspected_at", draft.inspectedAt)
                put("observations", draft.observations)
                put("created_at", draft.createdAt)
                put("updated_at", draft.updatedAt)
            })
        }
        assertEquals(1, historicalDb.version)
        val originalSchema = schemaSnapshot(historicalDb)
        historicalHelper.close()

        val migratedHelper = newHelper()
        val repo = SqliteInspectionDraftRepository(migratedHelper) { true }
        assertEquals(originals, repo.getAllDrafts().getOrThrow())
        assertEquals(2, migratedHelper.readableDatabase.version)
        assertEquals(originalSchema, schemaSnapshot(migratedHelper.readableDatabase))
        assertEquals(0, migratedHelper.readableDatabase.rawQuery("SELECT * FROM inspection_photos", null).use { it.count })

        val withPhoto = originals.first().copy(photos = listOf(photo()))
        repo.updateDraft(withPhoto).getOrThrow()
        assertEquals(withPhoto, repo.getDraftById(withPhoto.id).getOrThrow())
        assertEquals(originals[1], repo.getDraftById(originals[1].id).getOrThrow())
    }

    @Test
    fun realSqlite_unsupportedUpgradeOnOpen_preservesDataSchemaAndVersion() = runBlocking {
        val dbHelper = newHelper()
        val repo = SqliteInspectionDraftRepository(dbHelper) { true }

        val draft = InspectionDraft(
            id = "migration-001",
            containerIdentifier = "CSQU3054383",
            observations = "Conservar sin cambios",
            createdAt = 100L,
            updatedAt = 200L,
            photos = listOf(photo())
        )
        repo.createDraft(draft).getOrThrow()
        val originalSchema = schemaSnapshot(dbHelper.readableDatabase)
        val originalPhotosSchema = schemaSnapshot(dbHelper.readableDatabase, InspectionDatabaseHelper.TABLE_PHOTOS)
        assertEquals(2, dbHelper.readableDatabase.version)
        dbHelper.close()

        // La versión 3 es solo una solicitud sintética de apertura; no representa un esquema publicado.
        var upgradeAttempted = false
        val unsupportedHelper = object : SQLiteOpenHelper(context, testDbName, null, 3) {
            override fun onCreate(db: SQLiteDatabase) {
                error("La base v2 ya debe existir")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                upgradeAttempted = true
                assertEquals(2, oldVersion)
                assertEquals(3, newVersion)
                dbHelper.onUpgrade(db, oldVersion, newVersion)
            }
        }.also { openedHelpers.add(it) }

        val upgradeError = runCatching { unsupportedHelper.writableDatabase }.exceptionOrNull()
        assertTrue(upgradeAttempted)
        assertTrue(upgradeError is SQLiteException)
        unsupportedHelper.close()

        val reopenedHelper = newHelper()
        val reopenedRepo = SqliteInspectionDraftRepository(reopenedHelper)
        assertEquals(2, reopenedHelper.readableDatabase.version)
        assertEquals(originalSchema, schemaSnapshot(reopenedHelper.readableDatabase))
        assertEquals(originalPhotosSchema, schemaSnapshot(reopenedHelper.readableDatabase, InspectionDatabaseHelper.TABLE_PHOTOS))
        assertEquals(draft, reopenedRepo.getDraftById(draft.id).getOrThrow())
    }

    private fun photo() = DraftPhoto(UUID.randomUUID().toString(), "image/jpeg", 1024L, 800, 600)

    private fun schemaSnapshot(
        db: SQLiteDatabase,
        table: String = InspectionDatabaseHelper.TABLE_DRAFTS
    ): Pair<String, List<List<String?>>> {
        val tableSql = db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        val columns = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { cursor.getString(it) })
                }
            }
        }
        return tableSql to columns
    }
}
