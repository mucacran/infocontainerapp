package com.mucacran.infocontainer.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.core.content.FileProvider
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.model.PhotoLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

open class PhotoStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

class SelectionImportException(
    val readyPhotos: List<DraftPhoto>,
    message: String,
    cause: Throwable? = null
) : PhotoStorageException(message, cause)

/** Private, byte-for-byte originals. Only camera output is exposed through FileProvider. */
class LocalPhotoStore(private val context: Context) {
    private val originalsDirectory get() = File(context.noBackupFilesDir, "inspection_photos")
    private val capturesDirectory get() = File(context.filesDir, "camera_capture")
    private val sessionsDirectory get() = File(context.noBackupFilesDir, "inspection_photo_sessions")

    suspend fun importPhoto(uri: Uri): DraftPhoto = io("No se pudo copiar la fotografía. Intenta seleccionarla otra vez.") {
        copyPhoto(uri, UUID.randomUUID().toString())
    }

    private suspend fun copyPhoto(uri: Uri, id: String): DraftPhoto {
        val directory = prepareDirectory(originalsDirectory)
        requireSpace(directory)
        val partial = File(directory, "$id.part")
        val original = photoFile(id)
        var promoted = false
        try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw PhotoStorageException("No se pudo leer la fotografía seleccionada.")
            var total = 0L
            source.use { input ->
                val fileOutput = partial.outputStream()
                fileOutput.buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > PhotoLimits.MAX_BYTES_PER_PHOTO) {
                            throw PhotoStorageException("La fotografía supera el límite de 30 MiB por imagen.")
                        }
                        requireSpace(directory, count.toLong())
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            if (total == 0L) throw PhotoStorageException("La fotografía está vacía o no se pudo leer.")
            val image = inspect(partial)
            // A sampled decode rejects unreadable files without allocating the full original.
            decodePreview(partial, 320).recycle()
            if (original.exists() || !partial.renameTo(original)) {
                throw PhotoStorageException("No se pudo conservar la fotografía en el teléfono.")
            }
            promoted = true
            return DraftPhoto(id, image.mimeType, total, image.width, image.height)
        } finally {
            if (!promoted) partial.delete()
        }
    }

    /** Journal is committed before each copy, so an interrupted batch can resume after process death. */
    suspend fun importSelection(
        sessionId: String,
        uris: List<Uri> = emptyList(),
        existingPhotos: List<DraftPhoto> = emptyList()
    ): List<DraftPhoto> =
        io("No se pudo recuperar la selección de fotografías. Intenta seleccionarlas otra vez.") {
            selectionMutex.withLock {
                val previousEntries = readSession(sessionId)
                val entries = previousEntries.toMutableList()
                uris.forEach { uri ->
                    if (entries.none { it.uri == uri.toString() }) {
                        entries.add(SelectionEntry(uri.toString(), UUID.randomUUID().toString()))
                    }
                }
                if (entries.isEmpty()) return@withLock emptyList()
                if ((existingPhotos.map { it.id } + entries.map { it.fileId }).toSet().size > PhotoLimits.MAX_PHOTOS) {
                    throw SelectionImportException(
                        readyWithinLimits(previousEntries, existingPhotos),
                        "Se permiten hasta 12 fotografías por borrador. Quita alguna antes de seleccionar más."
                    )
                }
                writeSession(sessionId, entries)
                var firstFailure: Exception? = null
                val budget = existingPhotos.associateBy { it.id }.toMutableMap()
                entries.indices.forEach { index ->
                    coroutineContext.ensureActive()
                    var entry = entries[index]
                    // Take only rights this session actually needs, leaving pre-existing rights alone.
                    if (entry.photo == null && !photoFile(entry.fileId).isFile &&
                        Uri.parse(entry.uri).scheme == "content" && !entry.ownsGrant &&
                        !hasReadGrant(entry.uri)
                    ) {
                        entry = entry.copy(ownsGrant = true)
                        entries[index] = entry
                        writeSession(sessionId, entries)
                        if (!takeReadGrant(entry.uri)) {
                            entry = entry.copy(ownsGrant = false)
                            entries[index] = entry
                            writeSession(sessionId, entries)
                        }
                    }
                    try {
                        val existedBeforeCopy = photoFile(entry.fileId).isFile
                        val recovered = entry.photo?.takeIf { exists(it) } ?: recoverCopiedPhoto(entry.fileId)
                        val photo = recovered ?: copyPhoto(Uri.parse(entry.uri), entry.fileId)
                        val totalBytes = budget.values.sumOf { it.byteSize } + if (photo.id in budget) 0L else photo.byteSize
                        if (totalBytes > PhotoLimits.MAX_BYTES_PER_DRAFT) {
                            // This reserved ID was created by this attempt, never exposed to the form or SQLite.
                            // Existing/journal-ready images are retained even when the caller's budget changed.
                            if (!existedBeforeCopy && entry.photo == null && photo.id !in budget) {
                                val ownNewFile = photoFile(photo.id)
                                if (!ownNewFile.delete()) {
                                    throw PhotoStorageException("No se pudo retirar una copia que supera el límite. Reintenta la selección.")
                                }
                            }
                            throw PhotoStorageException("Las fotografías del borrador superan el límite de 150 MiB. Quita alguna antes de añadir más.")
                        }
                        budget.putIfAbsent(photo.id, photo)
                        entries[index] = entry.copy(photo = photo)
                        writeSession(sessionId, entries)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (firstFailure == null) firstFailure = error
                    }
                }
                val ready = readyWithinLimits(entries, existingPhotos)
                firstFailure?.let { error ->
                    throw SelectionImportException(
                        ready,
                        "No se pudieron recuperar todas las fotografías. Las copias disponibles se conservaron. " +
                            "Vuelve a seleccionar las que faltan o reintenta. ${error.message.orEmpty()}",
                        error
                    )
                }
                ready
            }
        }

    suspend fun hasPendingSelection(sessionId: String): Boolean =
        io("No se pudo comprobar la selección pendiente.") {
            selectionMutex.withLock { readSession(sessionId).isNotEmpty() }
        }

    /** Removing a thumbnail must also stop its journal entry from being attached again on resume. */
    suspend fun forgetSelectionPhoto(sessionId: String, photoId: String) =
        io("No se pudo retirar la fotografía de la selección pendiente.") {
            selectionMutex.withLock {
                safeId(photoId)
                val entries = readSession(sessionId)
                val removed = entries.filter { it.fileId == photoId }
                val remaining = entries.filterNot { it.fileId == photoId }
                writeSession(sessionId, remaining)
                releaseOwnedGrants(sessionId, removed, remaining)
            }
        }

    /** Only metadata/rights are removed; private originals are deliberately untouched. */
    suspend fun clearSelection(sessionId: String) = io("No se pudo cerrar la selección de fotografías.") {
        selectionMutex.withLock {
            val entries = readSession(sessionId)
            sessionFile(sessionId).delete()
            releaseOwnedGrants(sessionId, entries, emptyList())
        }
    }

    private data class SelectionEntry(
        val uri: String,
        val fileId: String,
        val photo: DraftPhoto? = null,
        val ownsGrant: Boolean = false
    )

    private fun readyWithinLimits(entries: List<SelectionEntry>, existingPhotos: List<DraftPhoto>): List<DraftPhoto> {
        val budget = existingPhotos.associateBy { it.id }.toMutableMap()
        return entries.mapNotNull { it.photo?.takeIf(::exists) }.filter { photo ->
            if (photo.id in budget) {
                true
            } else if (budget.size < PhotoLimits.MAX_PHOTOS &&
                budget.values.sumOf { it.byteSize } + photo.byteSize <= PhotoLimits.MAX_BYTES_PER_DRAFT
            ) {
                budget[photo.id] = photo
                true
            } else {
                false
            }
        }
    }

    private fun recoverCopiedPhoto(id: String): DraftPhoto? {
        val file = photoFile(id)
        if (!file.isFile) return null
        val info = inspect(file)
        val photo = DraftPhoto(id, info.mimeType, file.length(), info.width, info.height)
        if (!photo.hasValidMetadata()) throw PhotoStorageException("La copia local de la fotografía no es válida.")
        return photo
    }

    private fun sessionFile(id: String) = AtomicFile(File(sessionsDirectory, "${safeId(id)}.json"))

    private fun readSession(id: String): List<SelectionEntry> {
        val file = sessionFile(id)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val json = file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        if (json.getInt("version") != 1) throw PhotoStorageException("La selección pendiente usa una versión no admitida.")
        val entries = json.getJSONArray("entries")
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val fileId = safeId(entry.getString("fileId"))
            val photo = entry.optJSONObject("photo")?.let {
                DraftPhoto(fileId, it.getString("mimeType"), it.getLong("byteSize"), it.getInt("width"), it.getInt("height"))
                    .also { metadata -> check(metadata.hasValidMetadata()) }
            }
            SelectionEntry(entry.getString("uri"), fileId, photo, entry.optBoolean("ownsGrant", false))
        }
    }

    private fun writeSession(id: String, entries: List<SelectionEntry>) {
        prepareDirectory(sessionsDirectory)
        val json = JSONObject().put("version", 1).put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().put("uri", entry.uri).put("fileId", entry.fileId).put("ownsGrant", entry.ownsGrant).apply {
                    entry.photo?.let { photo ->
                        put("photo", JSONObject().put("mimeType", photo.mimeType).put("byteSize", photo.byteSize)
                            .put("width", photo.width).put("height", photo.height))
                    }
                })
            }
        })
        val file = sessionFile(id)
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun hasReadGrant(uri: String): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == uri }
    }.getOrDefault(false)

    private fun takeReadGrant(uri: String): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrDefault(false)

    private fun releaseOwnedGrants(sessionId: String, removed: List<SelectionEntry>, retained: List<SelectionEntry>) {
        removed.filter { it.ownsGrant && retained.none { keep -> keep.uri == it.uri } }.forEach { entry ->
            // If another app-owned session uses this right, transfer its ownership before clearing ours.
            val otherSessions = sessionsDirectory.listFiles().orEmpty().mapNotNull { file ->
                val id = file.name.removeSuffix(".bak").removeSuffix(".json")
                id.takeIf { it != sessionId && runCatching { safeId(it) }.isSuccess }
            }.distinct()
            val other = otherSessions.firstNotNullOfOrNull { id ->
                val entries = readSession(id)
                entries.indexOfFirst { it.uri == entry.uri }.takeIf { it >= 0 }?.let { Triple(id, entries, it) }
            }
            if (other != null) {
                val updated = other.second.toMutableList()
                updated[other.third] = updated[other.third].copy(ownsGrant = true)
                writeSession(other.first, updated)
            } else {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(Uri.parse(entry.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    suspend fun createCapture(): String = io("No se pudo preparar la cámara. Comprueba el espacio del teléfono.") {
        val directory = prepareDirectory(capturesDirectory)
        requireSpace(directory)
        val id = UUID.randomUUID().toString()
        if (!captureFile(id).createNewFile()) {
            throw PhotoStorageException("No se pudo preparar un archivo para la cámara.")
        }
        id
    }

    fun captureUri(id: String): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.photos",
        captureFile(id)
    )

    fun captureExists(id: String): Boolean = runCatching { captureFile(id).isFile && captureFile(id).length() > 0L }.getOrDefault(false)

    /** Failed imports retain this app's camera output so the caller can retry or cancel explicitly. */
    suspend fun completeCapture(id: String, success: Boolean): DraftPhoto? =
        io("No se pudo conservar la fotografía de la cámara.") {
            val capture = captureFile(id)
            if (!success) {
                capture.delete()
                null
            } else {
                val photo = importPhoto(Uri.fromFile(capture))
                capture.delete()
                photo
            }
        }

    suspend fun cancelCapture(id: String) = io("No se pudo retirar el archivo temporal de la cámara.") {
        val capture = captureFile(id)
        if (capture.exists() && !capture.delete()) {
            throw PhotoStorageException("No se pudo retirar el archivo temporal de la cámara.")
        }
        Unit
    }

    fun exists(photo: DraftPhoto): Boolean = runCatching {
        val file = photoFile(photo.id)
        file.isFile && file.length() == photo.byteSize && photo.byteSize > 0L
    }.getOrDefault(false)

    /** Resolves only an existing private original; uploading never creates or rewrites it. */
    internal fun originalForUpload(photo: DraftPhoto): File {
        val file = photoFile(photo.id)
        if (!file.isFile || photo.byteSize <= 0L || file.length() != photo.byteSize) {
            throw PhotoStorageException("La fotografía ya no está disponible o cambió en este teléfono.")
        }
        return file
    }

    suspend fun preview(photo: DraftPhoto, maxEdge: Int = 320): Bitmap =
        io("No se pudo mostrar la fotografía. El archivo puede estar dañado o ya no estar disponible.") {
            require(maxEdge in 1..2048) { "El tamaño de vista previa debe estar entre 1 y 2048 píxeles." }
            if (!exists(photo)) throw PhotoStorageException("La fotografía ya no está disponible en este teléfono.")
            decodePreview(photoFile(photo.id), maxEdge)
        }

    /** Call only for newly imported attachments being discarded; stored references always win. */
    suspend fun deleteUnreferenced(photoId: String, isReferenced: suspend (String) -> Boolean) =
        io("No se pudo retirar la fotografía local sin usar.") {
            val file = photoFile(photoId)
            if (!isReferenced(photoId) && file.exists() && !file.delete()) {
                throw PhotoStorageException("No se pudo retirar la fotografía local sin usar.")
            }
            Unit
        }

    private data class ImageInfo(val mimeType: String, val width: Int, val height: Int)

    private fun inspect(file: File): ImageInfo {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val mime = bounds.outMimeType
        if (mime !in PhotoLimits.SUPPORTED_MIME_TYPES) {
            throw PhotoStorageException("Formato no admitido o imagen dañada. Selecciona una fotografía JPEG, PNG o WebP.")
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw PhotoStorageException("La fotografía está dañada o no se puede leer.")
        }
        if (bounds.outWidth.toLong() * bounds.outHeight > PhotoLimits.MAX_PIXELS) {
            throw PhotoStorageException("La fotografía supera el límite de 40 millones de píxeles.")
        }
        val orientation = orientation(file)
        val swapped = orientation in setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270
        )
        return ImageInfo(
            mime!!,
            if (swapped) bounds.outHeight else bounds.outWidth,
            if (swapped) bounds.outWidth else bounds.outHeight
        )
    }

    private fun decodePreview(file: File, maxEdge: Int): Bitmap {
        val info = inspect(file)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, header, _ ->
                if (header.mimeType !in PhotoLimits.SUPPORTED_MIME_TYPES) {
                    throw PhotoStorageException("Formato no admitido. Usa JPEG, PNG o WebP.")
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
                val ratio = minOf(1.0, maxEdge.toDouble() / max(header.size.width, header.size.height))
                decoder.setTargetSize(
                    max(1, (header.size.width * ratio).roundToInt()),
                    max(1, (header.size.height * ratio).roundToInt())
                )
            }
        }
        var sample = 1
        while (max(info.width, info.height) / sample > maxEdge * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw PhotoStorageException("La fotografía está dañada o no se puede leer.")
        val oriented = orient(decoded, orientation(file))
        if (oriented !== decoded) decoded.recycle()
        val ratio = minOf(1.0, maxEdge.toDouble() / max(oriented.width, oriented.height))
        if (ratio == 1.0) return oriented
        val scaled = Bitmap.createScaledBitmap(
            oriented,
            max(1, (oriented.width * ratio).roundToInt()),
            max(1, (oriented.height * ratio).roundToInt()),
            true
        )
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    private fun orientation(file: File): Int = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                else -> return bitmap
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun photoFile(id: String) = File(originalsDirectory, "${safeId(id)}.image")
    private fun captureFile(id: String) = File(capturesDirectory, "${safeId(id)}.jpg")

    private fun safeId(id: String): String {
        if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) {
            throw PhotoStorageException("El identificador de la fotografía no es válido.")
        }
        return id
    }

    private fun prepareDirectory(directory: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw PhotoStorageException("No se pudo preparar el almacenamiento de fotografías.")
        }
        return directory
    }

    private fun requireSpace(directory: File, nextBytes: Long = 0) {
        if (directory.usableSpace < RESERVED_FREE_BYTES + nextBytes) {
            throw PhotoStorageException("No hay espacio suficiente. Libera espacio en el teléfono e inténtalo otra vez.")
        }
    }

    private suspend fun <T> io(message: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: PhotoStorageException) {
            throw error
        } catch (error: Exception) {
            throw PhotoStorageException(message, error)
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val RESERVED_FREE_BYTES = 16L * 1024 * 1024
        val selectionMutex = Mutex()
    }
}
