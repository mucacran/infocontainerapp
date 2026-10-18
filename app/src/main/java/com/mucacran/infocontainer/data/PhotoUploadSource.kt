package com.mucacran.infocontainer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import com.mucacran.infocontainer.model.DraftPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/** Metadata measured from the private original, rather than trusted from its extension or database. */
data class PreparedPhotoUpload(
    val clientPhotoId: String,
    val file: File,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val sha256: String
)

class PhotoUploadValidationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Validates and hashes an existing original without converting it, copying it or changing metadata. */
class PhotoUploadSource(private val photos: LocalPhotoStore) {
    suspend fun prepare(photo: DraftPhoto): PreparedPhotoUpload = withContext(Dispatchers.IO) {
        try {
            val file = photos.originalForUpload(photo)
            val size = file.length()
            if (size > MAX_BYTES) {
                throw PhotoUploadValidationException(
                    "La fotografía supera los 10 MiB permitidos para subir. El original se conserva intacto; " +
                        "en esta etapa no se prepara una copia reducida."
                )
            }
            val modified = file.lastModified()
            val mime = signatureMime(file)
                ?: throw PhotoUploadValidationException("Formato no permitido. Usa una imagen JPEG, PNG o WebP válida.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outMimeType != mime || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw PhotoUploadValidationException("La fotografía está dañada o no se puede decodificar.")
            }
            if (bounds.outWidth > MAX_DIMENSION || bounds.outHeight > MAX_DIMENSION ||
                bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS
            ) {
                throw PhotoUploadValidationException(
                    "La fotografía supera 10.000 píxeles por dimensión o 20 megapíxeles. " +
                        "El original se conserva intacto; en esta etapa no se prepara una copia reducida."
                )
            }
            coroutineContext.ensureActive()
            validateDecode(file, bounds.outWidth, bounds.outHeight)
            val digest = MessageDigest.getInstance("SHA-256")
            var counted = 0L
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    counted += count
                    if (counted > MAX_BYTES) throw PhotoUploadValidationException("La fotografía cambió durante la preparación. Inténtalo otra vez.")
                    digest.update(buffer, 0, count)
                }
            }
            if (counted != size || file.length() != size || file.lastModified() != modified) {
                throw PhotoUploadValidationException("La fotografía cambió durante la preparación. Inténtalo otra vez.")
            }
            PreparedPhotoUpload(
                photo.id, file, mime, size, bounds.outWidth, bounds.outHeight,
                digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: PhotoUploadValidationException) {
            throw error
        } catch (error: PhotoStorageException) {
            throw PhotoUploadValidationException(error.message ?: "La fotografía ya no está disponible.", error)
        } catch (error: OutOfMemoryError) {
            throw PhotoUploadValidationException("No hay memoria suficiente para comprobar la fotografía. El original se conserva.", error)
        } catch (error: Exception) {
            throw PhotoUploadValidationException("No se pudo leer o decodificar la fotografía. El original se conserva intacto.", error)
        }
    }

    private fun signatureMime(file: File): String? {
        val header = ByteArray(12)
        val length = file.inputStream().use { it.read(header) }
        fun isByte(index: Int, expected: Int) = header[index].toInt() and 0xff == expected
        return when {
            length >= 3 && isByte(0, 0xff) && isByte(1, 0xd8) && isByte(2, 0xff) -> "image/jpeg"
            length >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "image/png"
            length >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }
    }

    /** A bounded decode verifies actual image data while avoiding a full-resolution allocation. */
    private fun validateDecode(file: File, width: Int, height: Int) {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = minOf(1.0, DECODE_EDGE.toDouble() / max(width, height))
                decoder.setTargetSize(max(1, (width * scale).toInt()), max(1, (height * scale).toInt()))
                decoder.setOnPartialImageListener { false }
            }
        } else {
            // API 26/27 have no ImageDecoder; avoid BitmapFactory's tolerance for truncated JPEG.
            if (signatureMime(file) == "image/jpeg" && !hasCompleteJpeg(file)) {
                throw PhotoUploadValidationException("La fotografía JPEG está incompleta o dañada.")
            }
            var sample = 1
            while (max(width, height) / sample > DECODE_EDGE) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: throw PhotoUploadValidationException("La fotografía está dañada o no se puede decodificar.")
        }
        decoded.recycle()
    }

    /** Skipping length-delimited segments avoids mistaking an EXIF thumbnail's EOI for the image's. */
    private fun hasCompleteJpeg(file: File): Boolean = try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readUnsignedShort() != 0xffd8) return false
            var pendingMarker: Int? = null
            while (true) {
                val marker = pendingMarker ?: run {
                    if (input.readUnsignedByte() != 0xff) return false
                    var value = input.readUnsignedByte()
                    while (value == 0xff) value = input.readUnsignedByte()
                    value
                }
                pendingMarker = null
                if (marker == 0xd9) return true
                if (marker == 0 || marker == 0xd8 || marker in 0xd0..0xd7) return false
                if (marker == 0x01) continue
                val length = input.readUnsignedShort() - 2
                if (length < 0) return false
                var remaining = length
                while (remaining > 0) {
                    val skipped = input.skipBytes(remaining)
                    if (skipped == 0) throw EOFException()
                    remaining -= skipped
                }
                if (marker == 0xda) {
                    // Scan entropy-coded data; FF00 is an escaped byte and FFD0..D7 are restarts.
                    while (true) {
                        if (input.readUnsignedByte() != 0xff) continue
                        var next = input.readUnsignedByte()
                        while (next == 0xff) next = input.readUnsignedByte()
                        if (next == 0 || next in 0xd0..0xd7) continue
                        pendingMarker = next
                        break
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") false
    } catch (_: EOFException) {
        false
    }

    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_DIMENSION = 10_000
        const val MAX_PIXELS = 20_000_000L
        private const val DECODE_EDGE = 256
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
