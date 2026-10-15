package com.mucacran.infocontainer.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.model.PhotoLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface InspectionDraftRepository {
    suspend fun createDraft(draft: InspectionDraft): Result<Boolean>
    suspend fun updateDraft(draft: InspectionDraft): Result<Boolean>
    suspend fun getDraftById(id: String): Result<InspectionDraft?>
    suspend fun getAllDrafts(): Result<List<InspectionDraft>>
    suspend fun deleteDraft(id: String): Result<Boolean>

    suspend fun isPhotoReferenced(id: String): Result<Boolean> = getAllDrafts().map { drafts ->
        drafts.any { draft -> draft.photos.any { it.id == id } }
    }
}

class DraftNotFoundException(val draftId: String) : Exception(
    "No se encontró el borrador $draftId para actualizar."
)

class SqliteInspectionDraftRepository(
    private val dbHelper: InspectionDatabaseHelper,
    private val photoExists: (DraftPhoto) -> Boolean = { false }
) : InspectionDraftRepository {

    override suspend fun createDraft(draft: InspectionDraft): Result<Boolean> = writeDraft(draft, create = true)

    override suspend fun updateDraft(draft: InspectionDraft): Result<Boolean> = writeDraft(draft, create = false)

    private suspend fun writeDraft(draft: InspectionDraft, create: Boolean): Result<Boolean> = onIo {
        val snapshot = draft.copy(photos = draft.photos.toList())
        validatePhotos(snapshot.photos)
        val db = dbHelper.writableDatabase
        db.transaction {
            if (create) {
                db.insertOrThrow(InspectionDatabaseHelper.TABLE_DRAFTS, null, snapshot.toContentValues())
            } else {
                val updatedRows = db.update(
                    InspectionDatabaseHelper.TABLE_DRAFTS,
                    snapshot.toContentValues(),
                    "${InspectionDatabaseHelper.COLUMN_ID} = ?",
                    arrayOf(snapshot.id)
                )
                when (updatedRows) {
                    1 -> Unit
                    0 -> throw DraftNotFoundException(snapshot.id)
                    else -> error("La actualización afectó $updatedRows borradores.")
                }
            }

            // Only metadata is replaced. Files remain available if any statement rolls back.
            db.delete(
                InspectionDatabaseHelper.TABLE_PHOTOS,
                "${InspectionDatabaseHelper.COLUMN_DRAFT_ID} = ?",
                arrayOf(snapshot.id)
            )
            snapshot.photos.forEachIndexed { position, photo ->
                check(photoExists(photo)) { "No está disponible la fotografía ${photo.id}. Vuelve a intentarlo." }
                db.insertOrThrow(
                    InspectionDatabaseHelper.TABLE_PHOTOS,
                    null,
                    photo.toContentValues(snapshot.id, position)
                )
            }
        }
        true
    }

    private fun validatePhotos(photos: List<DraftPhoto>) {
        require(photos.size <= PhotoLimits.MAX_PHOTOS) { "Se permiten hasta ${PhotoLimits.MAX_PHOTOS} fotografías." }
        require(photos.all { it.hasValidMetadata() }) { "Hay una fotografía con datos o formato no admitidos." }
        require(photos.map { it.id }.toSet().size == photos.size) { "Una fotografía está repetida en el borrador." }
        require(photos.sumOf { it.byteSize } <= PhotoLimits.MAX_BYTES_PER_DRAFT) {
            "Las fotografías del borrador superan el límite de 150 MiB."
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

    private fun DraftPhoto.toContentValues(draftId: String, position: Int) = ContentValues().apply {
        put(InspectionDatabaseHelper.COLUMN_ID, id)
        put(InspectionDatabaseHelper.COLUMN_DRAFT_ID, draftId)
        put(InspectionDatabaseHelper.COLUMN_POSITION, position)
        put(InspectionDatabaseHelper.COLUMN_MIME_TYPE, mimeType)
        put(InspectionDatabaseHelper.COLUMN_BYTE_SIZE, byteSize)
        put(InspectionDatabaseHelper.COLUMN_WIDTH, width)
        put(InspectionDatabaseHelper.COLUMN_HEIGHT, height)
    }

    override suspend fun getDraftById(id: String): Result<InspectionDraft?> = onIo {
        val db = dbHelper.readableDatabase
        db.transaction {
            db.query(
                InspectionDatabaseHelper.TABLE_DRAFTS,
                null,
                "${InspectionDatabaseHelper.COLUMN_ID} = ?",
                arrayOf(id), null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.readDraft(db) else null
            }
        }
    }

    override suspend fun getAllDrafts(): Result<List<InspectionDraft>> = onIo {
        val db = dbHelper.readableDatabase
        db.transaction {
            db.query(
                InspectionDatabaseHelper.TABLE_DRAFTS,
                null, null, null, null, null,
                "${InspectionDatabaseHelper.COLUMN_UPDATED_AT} DESC"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.readDraft(db))
                }
            }
        }
    }

    private fun Cursor.readDraft(db: SQLiteDatabase): InspectionDraft {
        val id = getString(getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_ID))
        return InspectionDraft(
            id = id,
            containerIdentifier = getString(getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_CONTAINER_IDENTIFIER)),
            isoCode = getString(getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_ISO_CODE)),
            inspectionType = getString(getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_INSPECTION_TYPE)),
            inspectedAt = getString(getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_INSPECTED_AT)),
            observations = getString(getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_OBSERVATIONS)),
            createdAt = getLong(getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_CREATED_AT)),
            updatedAt = getLong(getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_UPDATED_AT)),
            photos = db.readPhotos(id)
        )
    }

    private fun SQLiteDatabase.readPhotos(draftId: String): List<DraftPhoto> = query(
        InspectionDatabaseHelper.TABLE_PHOTOS,
        null,
        "${InspectionDatabaseHelper.COLUMN_DRAFT_ID} = ?",
        arrayOf(draftId), null, null,
        "${InspectionDatabaseHelper.COLUMN_POSITION} ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(DraftPhoto(
                    id = cursor.getString(cursor.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_ID)),
                    mimeType = cursor.getString(cursor.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_MIME_TYPE)),
                    byteSize = cursor.getLong(cursor.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_BYTE_SIZE)),
                    width = cursor.getInt(cursor.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_WIDTH)),
                    height = cursor.getInt(cursor.getColumnIndexOrThrow(InspectionDatabaseHelper.COLUMN_HEIGHT))
                ))
            }
        }
    }

    override suspend fun isPhotoReferenced(id: String): Result<Boolean> = onIo {
        dbHelper.readableDatabase.query(
            InspectionDatabaseHelper.TABLE_PHOTOS,
            arrayOf(InspectionDatabaseHelper.COLUMN_ID),
            "${InspectionDatabaseHelper.COLUMN_ID} = ?",
            arrayOf(id), null, null, null, "1"
        ).use { it.moveToFirst() }
    }

    override suspend fun deleteDraft(id: String): Result<Boolean> = onIo {
        val db = dbHelper.writableDatabase
        db.transaction {
            val deletedRows = db.delete(
                InspectionDatabaseHelper.TABLE_DRAFTS,
                "${InspectionDatabaseHelper.COLUMN_ID} = ?",
                arrayOf(id)
            )
            check(deletedRows == 1) { "No se encontró el registro para eliminar." }
        }
        true
    }

    private inline fun <T> SQLiteDatabase.transaction(block: () -> T): T {
        beginTransaction()
        try {
            val value = block()
            setTransactionSuccessful()
            return value
        } finally {
            endTransaction()
        }
    }

    private suspend fun <T> onIo(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }
}
