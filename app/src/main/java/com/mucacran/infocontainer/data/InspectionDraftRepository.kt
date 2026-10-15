package com.mucacran.infocontainer.data

import android.content.ContentValues
import com.mucacran.infocontainer.model.InspectionDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface InspectionDraftRepository {
    suspend fun createDraft(draft: InspectionDraft): Result<Boolean>
    suspend fun updateDraft(draft: InspectionDraft): Result<Boolean>
    suspend fun getDraftById(id: String): Result<InspectionDraft?>
    suspend fun getAllDrafts(): Result<List<InspectionDraft>>
    suspend fun deleteDraft(id: String): Result<Boolean>
}

class DraftNotFoundException(val draftId: String) : Exception(
    "No se encontró el borrador $draftId para actualizar."
)

class SqliteInspectionDraftRepository(
    private val dbHelper: InspectionDatabaseHelper
) : InspectionDraftRepository {

    override suspend fun createDraft(draft: InspectionDraft): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val db = dbHelper.writableDatabase
            val result = db.insertOrThrow(
                InspectionDatabaseHelper.TABLE_DRAFTS,
                null,
                draft.toContentValues()
            )
            if (result != -1L) {
                Result.success(true)
            } else {
                Result.failure(Exception("Error al crear el borrador en SQLite."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateDraft(draft: InspectionDraft): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val updatedRows = dbHelper.writableDatabase.update(
                InspectionDatabaseHelper.TABLE_DRAFTS,
                draft.toContentValues(),
                "${InspectionDatabaseHelper.COLUMN_ID} = ?",
                arrayOf(draft.id)
            )
            when (updatedRows) {
                1 -> Result.success(true)
                0 -> Result.failure(DraftNotFoundException(draft.id))
                else -> Result.failure(Exception("La actualización afectó $updatedRows borradores."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun InspectionDraft.toContentValues() = ContentValues().apply {
        put(InspectionDatabaseHelper.COLUMN_ID, id)
        put(InspectionDatabaseHelper.COLUMN_CONTAINER_IDENTIFIER, containerIdentifier)
        put(InspectionDatabaseHelper.COLUMN_ISO_CODE, isoCode)
        put(InspectionDatabaseHelper.COLUMN_INSPECTION_TYPE, inspectionType)
        put(InspectionDatabaseHelper.COLUMN_INSPECTED_AT, inspectedAt)
        put(InspectionDatabaseHelper.COLUMN_OBSERVATIONS, observations)
        put(InspectionDatabaseHelper.COLUMN_CREATED_AT, createdAt)
        put(InspectionDatabaseHelper.COLUMN_UPDATED_AT, updatedAt)
    }

    override suspend fun getDraftById(id: String): Result<InspectionDraft?> = withContext(Dispatchers.IO) {
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                InspectionDatabaseHelper.TABLE_DRAFTS,
                null,
                "${InspectionDatabaseHelper.COLUMN_ID} = ?",
                arrayOf(id),
                null,
                null,
                null
            )

            val draft = cursor.use {
                if (it.moveToFirst()) {
                    InspectionDraft(
                        id = it.getString(it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_ID)),
                        containerIdentifier = it.getString(it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_CONTAINER_IDENTIFIER)),
                        isoCode = it.getString(it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_ISO_CODE)),
                        inspectionType = it.getString(it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_INSPECTION_TYPE)),
                        inspectedAt = it.getString(it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_INSPECTED_AT)),
                        observations = it.getString(it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_OBSERVATIONS)),
                        createdAt = it.getLong(it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_CREATED_AT)),
                        updatedAt = it.getLong(it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_UPDATED_AT))
                    )
                } else null
            }
            Result.success(draft)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getAllDrafts(): Result<List<InspectionDraft>> = withContext(Dispatchers.IO) {
        val drafts = mutableListOf<InspectionDraft>()
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                InspectionDatabaseHelper.TABLE_DRAFTS,
                null,
                null,
                null,
                null,
                null,
                "${InspectionDatabaseHelper.COLUMN_UPDATED_AT} DESC"
            )

            cursor.use {
                val idCol = it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_ID)
                val containerCol = it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_CONTAINER_IDENTIFIER)
                val isoCol = it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_ISO_CODE)
                val typeCol = it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_INSPECTION_TYPE)
                val inspectedAtCol = it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_INSPECTED_AT)
                val obsCol = it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_OBSERVATIONS)
                val createdCol = it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_CREATED_AT)
                val updatedCol = it.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_UPDATED_AT)

                while (it.moveToNext()) {
                    drafts.add(
                        InspectionDraft(
                            id = it.getString(idCol),
                            containerIdentifier = it.getString(containerCol),
                            isoCode = it.getString(isoCol),
                            inspectionType = it.getString(typeCol),
                            inspectedAt = it.getString(inspectedAtCol),
                            observations = it.getString(obsCol),
                            createdAt = it.getLong(createdCol),
                            updatedAt = it.getLong(updatedCol)
                        )
                    )
                }
            }
            Result.success(drafts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteDraft(id: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val db = dbHelper.writableDatabase
            val deletedRows = db.delete(
                InspectionDatabaseHelper.TABLE_DRAFTS,
                "${InspectionDatabaseHelper.COLUMN_ID} = ?",
                arrayOf(id)
            )
            if (deletedRows > 0) {
                Result.success(true)
            } else {
                Result.failure(Exception("No se encontró el registro para eliminar."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
