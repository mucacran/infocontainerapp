package com.mucacran.infocontainer.data.transfer

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.mucacran.infocontainer.network.WordPressConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Non-secret, exact request metadata. The original photograph is never opened by this store. */
data class PhotoTransferMetadata(
    val localPhotoId: String,
    val sha256: String,
    val byteSize: Long,
    val mimeType: String,
    val category: String,
    val description: String = "",
    val capturedAt: String? = null
)

enum class PhotoTransferStatus { PENDING, COMPLETED }

data class PhotoTransferRecord(
    val metadata: PhotoTransferMetadata,
    val clientPhotoId: String,
    val status: PhotoTransferStatus = PhotoTransferStatus.PENDING,
    val remotePhotoId: Long? = null
)

data class DraftTransferRecord(
    val origin: String,
    val localDraftId: String,
    val remoteInspectionId: Long,
    val photos: List<PhotoTransferRecord>
)

class TransferJournalException(val reason: Reason) : IOException("Photo transfer journal: $reason") {
    enum class Reason {
        INVALID_INPUT, CORRUPT, STORAGE_FAILURE, BINDING_CONFLICT, PHOTO_CONFLICT,
        REMOTE_ID_CONFLICT, NOT_PREPARED
    }
}

interface TransferJournal {
    suspend fun load(draftId: String): DraftTransferRecord?

    /** Must finish successfully before any upload; an existing request cannot be changed. */
    suspend fun prepare(
        draftId: String,
        inspectionId: Long,
        photo: PhotoTransferMetadata
    ): PhotoTransferRecord

    /** Called only after the transport has checked the response against the prepared request. */
    suspend fun complete(draftId: String, localPhotoId: String, remotePhotoId: Long): PhotoTransferRecord
}

/**
 * Durable upload intentions, separate from SQLite and original photographs. An interrupted upload
 * remains PENDING and uses the same local UUID and exact payload when explicitly retried. Absence
 * of a successful response never implies completion. No credentials or file bytes are persisted.
 *
 * All instances share a process mutex, including new instances after Activity recreation. Android's
 * AtomicFile makes each complete draft record replace its prior version together. Corrupt records
 * are rejected and never replaced with a new binding or a new upload intention.
 */
class PhotoTransferStore(private val rootDirectory: File) : TransferJournal {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "photo_transfers"))

    override suspend fun load(draftId: String): DraftTransferRecord? = withContext(Dispatchers.IO) {
        processMutex.withLock {
            validInput { requireUuid(draftId) }
            read(draftId)
        }
    }

    override suspend fun prepare(
        draftId: String,
        inspectionId: Long,
        photo: PhotoTransferMetadata
    ): PhotoTransferRecord = withContext(Dispatchers.IO) {
        processMutex.withLock {
            validInput { requireUuid(draftId); require(inspectionId > 0); requireValid(photo) }
            val previous = read(draftId)
            if (previous != null && previous.remoteInspectionId != inspectionId) {
                fail(TransferJournalException.Reason.BINDING_CONFLICT)
            }
            val existing = previous?.photos?.firstOrNull { it.metadata.localPhotoId == photo.localPhotoId }
            if (existing != null) {
                if (existing.metadata != photo) fail(TransferJournalException.Reason.PHOTO_CONFLICT)
                return@withLock existing
            }
            val pending = PhotoTransferRecord(photo, clientPhotoId = photo.localPhotoId)
            val updated = previous?.copy(photos = previous.photos + pending) ?: DraftTransferRecord(
                WordPressConfig.BASE_URL, draftId, inspectionId, listOf(pending)
            )
            if (updated.photos.size > MAX_PHOTOS) fail(TransferJournalException.Reason.INVALID_INPUT)
            write(updated)
            pending
        }
    }

    override suspend fun complete(
        draftId: String,
        localPhotoId: String,
        remotePhotoId: Long
    ): PhotoTransferRecord = withContext(Dispatchers.IO) {
        processMutex.withLock {
            validInput { requireUuid(draftId); requireUuid(localPhotoId); require(remotePhotoId > 0) }
            val previous = read(draftId) ?: fail(TransferJournalException.Reason.NOT_PREPARED)
            val existing = previous.photos.firstOrNull { it.metadata.localPhotoId == localPhotoId }
                ?: fail(TransferJournalException.Reason.NOT_PREPARED)
            if (existing.remotePhotoId != null && existing.remotePhotoId != remotePhotoId) {
                fail(TransferJournalException.Reason.REMOTE_ID_CONFLICT)
            }
            if (existing.status == PhotoTransferStatus.COMPLETED) return@withLock existing
            if (previous.photos.any { it.remotePhotoId == remotePhotoId && it != existing }) {
                fail(TransferJournalException.Reason.REMOTE_ID_CONFLICT)
            }
            val completed = existing.copy(status = PhotoTransferStatus.COMPLETED, remotePhotoId = remotePhotoId)
            write(previous.copy(photos = previous.photos.map { if (it == existing) completed else it }))
            completed
        }
    }

    private fun atomicFile(draftId: String) = AtomicFile(File(rootDirectory, "$draftId.json"))

    private fun read(draftId: String): DraftTransferRecord? {
        val file = atomicFile(draftId)
        val base = file.baseFile
        if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) {
            return null
        }
        val bytes = try {
            file.openRead().use { it.readBytesBounded() }
        } catch (failure: TransferJournalException) {
            throw failure
        } catch (_: IOException) {
            fail(TransferJournalException.Reason.STORAGE_FAILURE)
        }
        return try {
            val json = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            decode(json).also { require(it.localDraftId == draftId); requireValid(it) }
        } catch (_: Exception) {
            fail(TransferJournalException.Reason.CORRUPT)
        }
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > MAX_JOURNAL_BYTES) fail(TransferJournalException.Reason.CORRUPT)
            output.write(buffer, 0, count)
        }
    }

    private fun write(record: DraftTransferRecord) {
        val bytes = encode(record).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_JOURNAL_BYTES) fail(TransferJournalException.Reason.INVALID_INPUT)
        val file = atomicFile(record.localDraftId)
        try {
            if (!rootDirectory.isDirectory && !rootDirectory.mkdirs()) throw IOException()
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            }
            // Fail closed even if an Android implementation failed to finish an AtomicFile write.
            if (read(record.localDraftId) != record) throw IOException()
        } catch (_: Exception) {
            fail(TransferJournalException.Reason.STORAGE_FAILURE)
        }
    }

    private fun encode(record: DraftTransferRecord): String = StringWriter().also { output ->
        JsonWriter(output).use { writer ->
            writer.beginObject()
            writer.name("version").value(1)
            writer.name("origin").value(record.origin)
            writer.name("localDraftId").value(record.localDraftId)
            writer.name("remoteInspectionId").value(record.remoteInspectionId)
            writer.name("photos").beginArray()
            record.photos.forEach { photo ->
                writer.beginObject()
                writer.name("localPhotoId").value(photo.metadata.localPhotoId)
                writer.name("clientPhotoId").value(photo.clientPhotoId)
                writer.name("sha256").value(photo.metadata.sha256)
                writer.name("byteSize").value(photo.metadata.byteSize)
                writer.name("mimeType").value(photo.metadata.mimeType)
                writer.name("category").value(photo.metadata.category)
                writer.name("description").value(photo.metadata.description)
                writer.name("capturedAt").value(photo.metadata.capturedAt)
                writer.name("status").value(photo.status.name)
                writer.name("remotePhotoId").value(photo.remotePhotoId)
                writer.endObject()
            }
            writer.endArray().endObject()
        }
    }.toString()

    /** Only this version's complete schema is accepted, including exact tokens and no trailing JSON. */
    private fun decode(json: String): DraftTransferRecord = JsonReader(StringReader(json)).use { reader ->
        reader.strictness = Strictness.STRICT
        reader.beginObject()
        reader.name("version"); require(reader.long() == 1L)
        reader.name("origin"); val origin = reader.string()
        reader.name("localDraftId"); val draftId = reader.string()
        reader.name("remoteInspectionId"); val inspectionId = reader.long()
        reader.name("photos"); reader.beginArray()
        val photos = mutableListOf<PhotoTransferRecord>()
        while (reader.hasNext()) {
            require(photos.size < MAX_PHOTOS)
            reader.beginObject()
            reader.name("localPhotoId"); val localId = reader.string()
            reader.name("clientPhotoId"); val clientId = reader.string()
            reader.name("sha256"); val sha256 = reader.string()
            reader.name("byteSize"); val byteSize = reader.long()
            reader.name("mimeType"); val mimeType = reader.string()
            reader.name("category"); val category = reader.string()
            reader.name("description"); val description = reader.string()
            reader.name("capturedAt"); val capturedAt = reader.nullableString()
            reader.name("status"); val status = PhotoTransferStatus.valueOf(reader.string())
            reader.name("remotePhotoId")
            val remoteId = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.long()
            reader.endObject()
            photos += PhotoTransferRecord(
                PhotoTransferMetadata(localId, sha256, byteSize, mimeType, category, description, capturedAt),
                clientId, status, remoteId
            )
        }
        reader.endArray(); reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT)
        DraftTransferRecord(origin, draftId, inspectionId, photos)
    }

    private fun JsonReader.name(expected: String) { require(nextName() == expected) }
    private fun JsonReader.string(): String { require(peek() == JsonToken.STRING); return nextString() }
    private fun JsonReader.long(): Long { require(peek() == JsonToken.NUMBER); return nextLong() }
    private fun JsonReader.nullableString(): String? = if (peek() == JsonToken.NULL) {
        nextNull(); null
    } else string()

    private fun requireValid(record: DraftTransferRecord) {
        require(record.origin == WordPressConfig.BASE_URL)
        requireUuid(record.localDraftId)
        require(record.remoteInspectionId > 0 && record.photos.isNotEmpty() && record.photos.size <= MAX_PHOTOS)
        require(record.photos.map { it.metadata.localPhotoId }.distinct().size == record.photos.size)
        val remoteIds = record.photos.mapNotNull { it.remotePhotoId }
        require(remoteIds.distinct().size == remoteIds.size)
        record.photos.forEach {
            requireValid(it.metadata)
            require(it.clientPhotoId == it.metadata.localPhotoId)
            require(if (it.status == PhotoTransferStatus.PENDING) it.remotePhotoId == null else (it.remotePhotoId ?: 0) > 0)
        }
    }

    private fun requireValid(photo: PhotoTransferMetadata) {
        requireUuid(photo.localPhotoId)
        require(photo.sha256.matches(Regex("[0-9a-f]{64}")))
        require(photo.byteSize in 1..10L * 1024 * 1024)
        require(photo.mimeType in setOf("image/jpeg", "image/png", "image/webp"))
        require(photo.category in CATEGORIES)
        require(photo.description.codePointCount(0, photo.description.length) <= 2000)
        photo.capturedAt?.let {
            require(it.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})")))
            OffsetDateTime.parse(it, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
    }

    private fun requireUuid(value: String) { require(UUID.fromString(value).toString() == value) }
    private inline fun validInput(block: () -> Unit) {
        try { block() }
        catch (_: IllegalArgumentException) { fail(TransferJournalException.Reason.INVALID_INPUT) }
        catch (_: java.time.DateTimeException) { fail(TransferJournalException.Reason.INVALID_INPUT) }
    }

    private fun fail(reason: TransferJournalException.Reason): Nothing = throw TransferJournalException(reason)

    companion object {
        private val processMutex = Mutex()
        private const val MAX_JOURNAL_BYTES = 1024 * 1024
        private const val MAX_PHOTOS = 256
        private val CATEGORIES = setOf(
            "identifier", "technical_plate", "doors", "front", "left_side", "right_side",
            "interior", "floor", "damage", "other"
        )
    }
}
