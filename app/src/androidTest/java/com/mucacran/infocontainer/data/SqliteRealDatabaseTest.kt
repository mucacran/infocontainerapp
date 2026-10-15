package com.mucacran.infocontainer.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mucacran.infocontainer.model.InspectionDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun realSqlite_unsupportedUpgradeOnOpen_preservesDataSchemaAndVersion() = runBlocking {
        val dbHelper = newHelper()
        val repo = SqliteInspectionDraftRepository(dbHelper)

        val draft = InspectionDraft(
            id = "migration-001",
            containerIdentifier = "CSQU3054383",
            observations = "Conservar sin cambios",
            createdAt = 100L,
            updatedAt = 200L
        )
        repo.createDraft(draft).getOrThrow()
        val originalSchema = schemaSnapshot(dbHelper.readableDatabase)
        assertEquals(1, dbHelper.readableDatabase.version)
        dbHelper.close()

        // La versión 2 es solo una solicitud sintética de apertura; no representa un esquema publicado.
        var upgradeAttempted = false
        val unsupportedHelper = object : SQLiteOpenHelper(context, testDbName, null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                error("La base v1 ya debe existir")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                upgradeAttempted = true
                assertEquals(1, oldVersion)
                assertEquals(2, newVersion)
                dbHelper.onUpgrade(db, oldVersion, newVersion)
            }
        }.also { openedHelpers.add(it) }

        val upgradeError = runCatching { unsupportedHelper.writableDatabase }.exceptionOrNull()
        assertTrue(upgradeAttempted)
        assertTrue(upgradeError is SQLiteException)
        unsupportedHelper.close()

        val reopenedHelper = newHelper()
        val reopenedRepo = SqliteInspectionDraftRepository(reopenedHelper)
        assertEquals(1, reopenedHelper.readableDatabase.version)
        assertEquals(originalSchema, schemaSnapshot(reopenedHelper.readableDatabase))
        assertEquals(draft, reopenedRepo.getDraftById(draft.id).getOrThrow())
    }

    private fun schemaSnapshot(db: SQLiteDatabase): Pair<String, List<List<String?>>> {
        val tableSql = db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(InspectionDatabaseHelper.TABLE_DRAFTS)
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        val columns = db.rawQuery("PRAGMA table_info(${InspectionDatabaseHelper.TABLE_DRAFTS})", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { cursor.getString(it) })
                }
            }
        }
        return tableSql to columns
    }
}
