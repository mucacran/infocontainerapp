package com.mucacran.infocontainer.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mucacran.infocontainer.model.DraftPhoto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Only synthetic originals in this test's cache subtree are opened; no database or gallery is used. */
@RunWith(AndroidJUnit4::class)
class PhotoUploadSourceTest {
    private lateinit var root: File
    private lateinit var isolatedContext: Context
    private lateinit var source: PhotoUploadSource

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "photo_upload_source_test_${UUID.randomUUID()}").apply { check(mkdirs()) }
        isolatedContext = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
        }
        source = PhotoUploadSource(LocalPhotoStore(isolatedContext))
    }

    @After
    fun tearDown() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        check(root.canonicalFile.parentFile == cache && root.name.startsWith("photo_upload_source_test_"))
        root.deleteRecursively()
    }

    @Test
    @Suppress("DEPRECATION")
    fun prepare_supportedFormatsMeasureRealBytesAndLeaveOriginalsIdentical() = runBlocking {
        for ((format, mime) in listOf(
            Bitmap.CompressFormat.JPEG to "image/jpeg",
            Bitmap.CompressFormat.PNG to "image/png",
            Bitmap.CompressFormat.WEBP to "image/webp"
        )) {
            val photo = bitmapPhoto(80, 60, format)
            val original = original(photo.id)
            val before = original.readBytes()
            val modified = original.lastModified()

            // Valid local metadata may be stale; actual bytes determine MIME and dimensions.
            val prepared = source.prepare(photo.copy(mimeType = "image/png", width = 1, height = 1))

            assertEquals(photo.id, prepared.clientPhotoId)
            assertEquals(original.canonicalFile, prepared.file.canonicalFile)
            assertEquals(mime, prepared.mimeType)
            assertEquals(before.size.toLong(), prepared.byteSize)
            assertEquals(80, prepared.width)
            assertEquals(60, prepared.height)
            assertEquals(sha256(before), prepared.sha256)
            assertArrayEquals(before, original.readBytes())
            assertEquals(modified, original.lastModified())
        }
    }

    @Test
    fun prepare_retryAndNewStoreKeepUuidAndHashWithoutCreatingCopies() = runBlocking {
        val photo = bitmapPhoto(40, 30)
        val before = original(photo.id).readBytes()
        val first = source.prepare(photo)
        val second = PhotoUploadSource(LocalPhotoStore(isolatedContext)).prepare(photo)

        assertEquals(first, second)
        assertArrayEquals(before, original(photo.id).readBytes())
        assertEquals(listOf("${photo.id}.image"), originals().listFiles().orEmpty().map { it.name })
    }

    @Test
    fun prepare_rejectsEmptyMissingUnsupportedAndTruncatedWithoutChangingAnyFile() = runBlocking {
        val good = bitmapPhoto(80, 60)
        val corruptBytes = original(good.id).readBytes().copyOf(45)
        val fixtures = listOf(
            directPhoto(ByteArray(0)),
            directPhoto("JPEG is only a filename".toByteArray()),
            directPhoto(android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", android.util.Base64.DEFAULT)),
            directPhoto(corruptBytes),
            DraftPhoto(UUID.randomUUID().toString(), "image/png", 1, 1, 1)
        )
        for (photo in fixtures) {
            val file = original(photo.id)
            val before = file.takeIf(File::exists)?.readBytes()
            val error = runCatching { source.prepare(photo) }.exceptionOrNull()
            assertTrue(error is PhotoUploadValidationException)
            assertFalse(error?.message.isNullOrBlank())
            if (before != null) assertArrayEquals(before, file.readBytes()) else assertFalse(file.exists())
        }
        assertTrue(original(good.id).isFile)
    }

    @Test
    fun prepare_acceptsExactlyTenMiBAndRejectsOneByteMoreWithoutRewriting() = runBlocking {
        val photo = bitmapPhoto(8, 8)
        val file = original(photo.id)
        RandomAccessFile(file, "rw").use { it.setLength(PhotoUploadSource.MAX_BYTES) }
        val exact = photo.copy(byteSize = file.length())
        val beforeExact = sha256(file.readBytes())

        val prepared = source.prepare(exact)

        assertEquals(PhotoUploadSource.MAX_BYTES, prepared.byteSize)
        assertEquals(beforeExact, prepared.sha256)
        assertEquals(beforeExact, sha256(file.readBytes()))
        RandomAccessFile(file, "rw").use { it.setLength(PhotoUploadSource.MAX_BYTES + 1) }
        val oversized = exact.copy(byteSize = file.length())
        val beforeOversized = sha256(file.readBytes())

        val error = runCatching { source.prepare(oversized) }.exceptionOrNull()

        assertTrue(error is PhotoUploadValidationException)
        assertTrue(error?.message.orEmpty().contains("10 MiB"))
        assertEquals(beforeOversized, sha256(file.readBytes()))
    }

    @Test
    fun prepare_rejectsDimensionAndPixelLimitBeforeFullDecodeAndKeepsOriginals() = runBlocking {
        for ((width, height) in listOf(10_001 to 1, 1 to 10_001, 5_001 to 4_000)) {
            val photo = directPhoto(streamedPng(width, height), width, height)
            val before = original(photo.id).readBytes()

            val error = runCatching { source.prepare(photo) }.exceptionOrNull()

            assertTrue(error is PhotoUploadValidationException)
            assertTrue(error?.message.orEmpty().contains("20 megapíxeles"))
            assertArrayEquals(before, original(photo.id).readBytes())
        }
    }

    @Test
    fun prepare_acceptsExactDimensionAndTwentyMegapixelLimitsUsingSampledDecode() = runBlocking {
        val photo = directPhoto(streamedPng(10_000, 2_000), 10_000, 2_000)
        val before = original(photo.id).readBytes()

        val prepared = source.prepare(photo)

        assertEquals(10_000, prepared.width)
        assertEquals(2_000, prepared.height)
        assertEquals(20_000_000L, prepared.width.toLong() * prepared.height)
        assertEquals(sha256(before), prepared.sha256)
        assertArrayEquals(before, original(photo.id).readBytes())
    }

    @Test
    fun prepare_rejectsUnsafeIdAndMismatchedRecordedSize() = runBlocking {
        val photo = bitmapPhoto(16, 16)
        val before = original(photo.id).readBytes()
        for (invalid in listOf(photo.copy(id = "../outside"), photo.copy(byteSize = photo.byteSize + 1))) {
            assertTrue(runCatching { source.prepare(invalid) }.exceptionOrNull() is PhotoUploadValidationException)
        }
        assertArrayEquals(before, original(photo.id).readBytes())
        assertEquals(1, originals().listFiles().orEmpty().size)
    }

    private fun originals() = File(isolatedContext.noBackupFilesDir, "inspection_photos").apply { mkdirs() }
    private fun original(id: String) = File(originals(), "$id.image")

    private fun directPhoto(bytes: ByteArray, width: Int = 1, height: Int = 1): DraftPhoto {
        val id = UUID.randomUUID().toString()
        original(id).writeBytes(bytes)
        return DraftPhoto(id, "image/png", bytes.size.toLong(), width, height)
    }

    private fun bitmapPhoto(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): DraftPhoto {
        val bytes = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(40, 100, 180))
            check(bitmap.compress(format, 95, bytes))
        } finally {
            bitmap.recycle()
        }
        return directPhoto(bytes.toByteArray(), width, height)
    }

    /** Generates a fully decodable uniform PNG by rows; tests never allocate a huge bitmap. */
    private fun streamedPng(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        fun chunk(type: String, bytes: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            DataOutputStream(output).apply {
                writeInt(bytes.size)
                write(typeBytes)
                write(bytes)
                writeInt(CRC32().apply { update(typeBytes); update(bytes) }.value.toInt())
            }
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(width); writeInt(height); writeByte(8); writeByte(2)
            writeByte(0); writeByte(0); writeByte(0)
        }
        chunk("IHDR", header.toByteArray())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { zip ->
            val row = ByteArray(width * 3 + 1)
            repeat(height) { zip.write(row) }
        }
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", ByteArray(0))
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
