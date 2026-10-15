package com.mucacran.infocontainer.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.model.PhotoLimits
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32

/** All input images and store directories are synthetic, isolated fixtures in this test's UUID directory. */
@RunWith(AndroidJUnit4::class)
class LocalPhotoStoreTest {
    private lateinit var root: File
    private lateinit var isolatedContext: Context
    private lateinit var store: LocalPhotoStore

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "photo_store_test_${UUID.randomUUID()}").apply { check(mkdirs()) }
        isolatedContext = object : ContextWrapper(target) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
        }
        store = LocalPhotoStore(isolatedContext)
    }

    @After
    fun tearDown() {
        // No app database, app originals, gallery files, or pre-existing directory is touched.
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        check(root.canonicalFile.parentFile == cache && root.name.startsWith("photo_store_test_"))
        root.deleteRecursively()
    }

    @Test
    fun import_preservesOriginalBytesAndSurvivesNewStoreInstance() = runBlocking {
        val input = imageFile(900, 600)
        val bytes = input.readBytes()

        val photo = store.importPhoto(Uri.fromFile(input))

        assertEquals("image/png", photo.mimeType)
        assertEquals(900, photo.width)
        assertEquals(600, photo.height)
        assertEquals(bytes.size.toLong(), photo.byteSize)
        assertArrayEquals(bytes, input.readBytes())
        assertArrayEquals(bytes, original(photo.id).readBytes())
        assertTrue(LocalPhotoStore(isolatedContext).exists(photo))
        assertTrue(original(photo.id).canonicalPath.startsWith(isolatedContext.noBackupFilesDir.canonicalPath))
    }

    @Test
    fun preview_isSampledWithinRequestedBoundsAndRespectsExifRotation() = runBlocking {
        val input = imageFile(1200, 600, Bitmap.CompressFormat.JPEG)
        ExifInterface(input.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val bytes = input.readBytes()
        val photo = store.importPhoto(Uri.fromFile(input))

        val thumbnail = store.preview(photo, 180)
        try {
            assertEquals(600, photo.width)
            assertEquals(1200, photo.height)
            assertEquals(90, thumbnail.width)
            assertEquals(180, thumbnail.height)
            assertArrayEquals(bytes, original(photo.id).readBytes())
        } finally {
            thumbnail.recycle()
        }
    }

    @Test
    fun import_rejectsEmptyCorruptAndUnsupportedImagesWithoutLeavingOriginals() = runBlocking {
        val empty = File(root, "empty.source").apply { createNewFile() }
        val corrupt = File(root, "corrupt.source").apply { writeText("This is not an image") }
        val gif = File(root, "unsupported.source").apply {
            writeBytes(android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", android.util.Base64.DEFAULT))
        }

        for (input in listOf(empty, corrupt, gif)) {
            val error = runCatching { store.importPhoto(Uri.fromFile(input)) }.exceptionOrNull()
            assertTrue(error is PhotoStorageException)
            assertFalse(error?.message.isNullOrBlank())
        }
        assertTrue(originals().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun import_rejectsByteLimitWithStreamingAndRemovesOnlyItsPartialCopy() = runBlocking {
        val retained = store.importPhoto(Uri.fromFile(imageFile(12, 12)))
        val oversized = File(root, "oversized.source")
        RandomAccessFile(oversized, "rw").use { it.setLength(PhotoLimits.MAX_BYTES_PER_PHOTO + 1L) }

        val error = runCatching { store.importPhoto(Uri.fromFile(oversized)) }.exceptionOrNull()

        assertTrue(error is PhotoStorageException)
        assertTrue(error?.message.orEmpty().contains("30 MiB"))
        assertTrue(store.exists(retained))
        assertEquals(listOf("${retained.id}.image"), originals().listFiles().orEmpty().map { it.name })
        assertEquals(PhotoLimits.MAX_BYTES_PER_PHOTO + 1L, oversized.length())
    }

    @Test
    fun import_rejectsExcessivePixelDimensionsBeforeAllocatingBitmap() = runBlocking {
        val input = imageFile(1, 1)
        val bytes = input.readBytes()
        // Change only this synthetic PNG's IHDR dimensions and CRC; no huge bitmap is allocated.
        ByteBuffer.wrap(bytes, 16, 8).apply { putInt(50_001); putInt(1000) }
        val crc = CRC32().apply { update(bytes, 12, 17) }
        ByteBuffer.wrap(bytes, 29, 4).putInt(crc.value.toInt())
        input.writeBytes(bytes)

        val error = runCatching { store.importPhoto(Uri.fromFile(input)) }.exceptionOrNull()

        assertTrue(error is PhotoStorageException)
        assertTrue(error?.message.orEmpty().contains("40 millones"))
        assertTrue(originals().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancelledCamera_createsNoOriginalAndDoesNotTouchOtherCaptures() = runBlocking {
        val cancelled = store.createCapture()
        val retained = store.createCapture()
        assertTrue(capture(cancelled).isFile)

        assertNull(store.completeCapture(cancelled, false))

        assertFalse(capture(cancelled).exists())
        assertTrue(capture(retained).isFile)
        assertFalse(original(cancelled).exists())
        store.cancelCapture(retained)
        assertFalse(capture(retained).exists())
    }

    @Test
    fun cameraCompletion_preservesFullBytesAndFailedImportCanBeRetried() = runBlocking {
        val id = store.createCapture()
        val failed = runCatching { store.completeCapture(id, true) }.exceptionOrNull()
        assertTrue(failed is PhotoStorageException)
        assertTrue(capture(id).exists())
        val input = imageFile(600, 400, Bitmap.CompressFormat.JPEG)
        input.copyTo(capture(id), overwrite = true)

        val photo = store.completeCapture(id, true)

        assertNotNull(photo)
        assertArrayEquals(input.readBytes(), original(photo!!.id).readBytes())
        assertFalse(capture(id).exists())
        assertTrue(store.exists(photo))
    }

    @Test
    fun cleanup_keepsReferencedOriginalAndDeletesOnlyExplicitUnreferencedId() = runBlocking {
        val saved = store.importPhoto(Uri.fromFile(imageFile(10, 10)))
        val pending = store.importPhoto(Uri.fromFile(imageFile(20, 20)))

        store.deleteUnreferenced(saved.id) { true }
        store.deleteUnreferenced(pending.id) { false }

        assertTrue(store.exists(saved))
        assertFalse(store.exists(pending))
        assertEquals(listOf("${saved.id}.image"), originals().listFiles().orEmpty().map { it.name })
        assertTrue(runCatching { store.deleteUnreferenced("../outside") { false } }.isFailure)
        assertTrue(store.exists(saved))
    }

    @Test
    fun cleanup_retainsOriginalWhenReferenceCheckFails() = runBlocking {
        val photo = store.importPhoto(Uri.fromFile(imageFile(10, 10)))

        val error = runCatching { store.deleteUnreferenced(photo.id) { error("SQLite unavailable") } }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(store.exists(photo))
    }

    @Test
    fun selectionResume_newInstanceUsesPrivateCopiesWithoutSourceUris() = runBlocking {
        val session = UUID.randomUUID().toString()
        val inputs = listOf(imageFile(60, 40), imageFile(80, 50))
        val imported = store.importSelection(session, inputs.map(Uri::fromFile))
        inputs.forEach { check(it.delete()) }

        val restoredStore = LocalPhotoStore(isolatedContext)
        val restored = restoredStore.importSelection(session)

        assertEquals(imported, restored)
        assertTrue(restored.all(restoredStore::exists))
        assertTrue(restoredStore.hasPendingSelection(session))
        assertEquals(2, originals().listFiles().orEmpty().size)
    }

    @Test
    fun selectionResume_recoversFinalCopyWhenMetadataCommitWasInterrupted() = runBlocking {
        val session = UUID.randomUUID().toString()
        val input = imageFile(60, 40)
        val imported = store.importSelection(session, listOf(Uri.fromFile(input)))
        val journal = File(isolatedContext.noBackupFilesDir, "inspection_photo_sessions/$session.json")
        // Simulate process death after final rename and before metadata commit, inside this isolated fixture.
        val json = JSONObject(journal.readText())
        json.getJSONArray("entries").getJSONObject(0).remove("photo")
        journal.writeText(json.toString())
        check(input.delete())

        val restored = LocalPhotoStore(isolatedContext).importSelection(session)

        assertEquals(imported, restored)
        assertEquals(1, originals().listFiles().orEmpty().size)
    }

    @Test
    fun selectionPartialFailure_retainsReadyPhotosAndResumesMissingImage() = runBlocking {
        val session = UUID.randomUUID().toString()
        val good = imageFile(60, 40)
        val unavailable = File(root, "temporarily_unavailable.source")
        val failure = runCatching {
            store.importSelection(session, listOf(Uri.fromFile(good), Uri.fromFile(unavailable)))
        }.exceptionOrNull()
        assertTrue(failure is SelectionImportException)
        val ready = (failure as SelectionImportException).readyPhotos
        assertEquals(1, ready.size)
        assertTrue(store.exists(ready.single()))
        assertTrue(failure.message.orEmpty().contains("Vuelve a seleccionar"))
        check(good.delete())
        imageFile(80, 50).copyTo(unavailable)

        val restored = LocalPhotoStore(isolatedContext).importSelection(session)

        assertEquals(2, restored.size)
        assertEquals(ready.single(), restored.first())
        assertEquals(80, restored.last().width)
    }

    @Test
    fun selectionForget_doesNotReattachRemovedPhotoOrDeleteItsOriginal() = runBlocking {
        val session = UUID.randomUUID().toString()
        val imported = store.importSelection(session, listOf(Uri.fromFile(imageFile(60, 40)), Uri.fromFile(imageFile(80, 50))))

        store.forgetSelectionPhoto(session, imported.first().id)
        val restored = LocalPhotoStore(isolatedContext).importSelection(session)

        assertEquals(listOf(imported.last()), restored)
        assertTrue(imported.all(store::exists))
    }

    @Test
    fun selectionClear_removesOnlyJournalAndKeepsAllPrivateOriginals() = runBlocking {
        val session = UUID.randomUUID().toString()
        val imported = store.importSelection(session, listOf(Uri.fromFile(imageFile(60, 40))))

        store.clearSelection(session)

        assertFalse(store.hasPendingSelection(session))
        assertTrue(imported.all(store::exists))
        assertTrue(LocalPhotoStore(isolatedContext).importSelection(session).isEmpty())
    }

    @Test
    fun selectionCountLimit_rejectsBeforeCopyingOrCreatingJournal() = runBlocking {
        val session = UUID.randomUUID().toString()
        val existing = List(PhotoLimits.MAX_PHOTOS) { metadataPhoto(1L) }

        val failure = runCatching {
            store.importSelection(session, listOf(Uri.fromFile(imageFile(10, 10))), existing)
        }.exceptionOrNull()

        assertTrue(failure is SelectionImportException)
        assertTrue(failure?.message.orEmpty().contains("12 fotografías"))
        assertFalse(store.hasPendingSelection(session))
        assertTrue(originals().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun selectionCountLimit_keepsPreviousJournalAndReadyCopiesUnchanged() = runBlocking {
        val session = UUID.randomUUID().toString()
        val prior = store.importSelection(session, listOf(Uri.fromFile(imageFile(10, 10))))
        val journal = File(isolatedContext.noBackupFilesDir, "inspection_photo_sessions/$session.json")
        val priorBytes = journal.readBytes()
        val newUris = List(PhotoLimits.MAX_PHOTOS) { Uri.fromFile(imageFile(10, 10)) }

        val failure = runCatching { store.importSelection(session, newUris) }.exceptionOrNull()

        assertTrue(failure is SelectionImportException)
        assertEquals(prior, (failure as SelectionImportException).readyPhotos)
        assertArrayEquals(priorBytes, journal.readBytes())
        assertTrue(prior.all(store::exists))
        assertEquals(1, originals().listFiles().orEmpty().size)
    }

    @Test
    fun selectionByteLimit_removesOnlyNewOverflowCopyAndCanRetryAfterFreeingBudget() = runBlocking {
        val session = UUID.randomUUID().toString()
        val fullBudget = List(5) { metadataPhoto(PhotoLimits.MAX_BYTES_PER_PHOTO) }
        val input = imageFile(10, 10)

        val failure = runCatching {
            store.importSelection(session, listOf(Uri.fromFile(input)), fullBudget)
        }.exceptionOrNull()

        assertTrue(failure is SelectionImportException)
        assertTrue(failure?.message.orEmpty().contains("150 MiB"))
        assertTrue((failure as SelectionImportException).readyPhotos.isEmpty())
        assertTrue(originals().listFiles().orEmpty().isEmpty())
        assertTrue(store.hasPendingSelection(session))
        assertTrue(input.exists())

        val restored = LocalPhotoStore(isolatedContext).importSelection(session, existingPhotos = fullBudget.dropLast(1))
        assertEquals(1, restored.size)
        assertTrue(store.exists(restored.single()))
        assertEquals(1, originals().listFiles().orEmpty().size)
    }

    @Test
    fun selectionByteLimit_exposesValidPartialCopiesWithoutHiddenOverflowOriginal() = runBlocking {
        val session = UUID.randomUUID().toString()
        val first = imageFile(10, 10)
        val second = imageFile(20, 20)
        val budget = List(4) { metadataPhoto(PhotoLimits.MAX_BYTES_PER_PHOTO) } +
            metadataPhoto(PhotoLimits.MAX_BYTES_PER_PHOTO - first.length())

        val failure = runCatching {
            store.importSelection(session, listOf(Uri.fromFile(first), Uri.fromFile(second)), budget)
        }.exceptionOrNull()

        assertTrue(failure is SelectionImportException)
        val ready = (failure as SelectionImportException).readyPhotos
        assertEquals(1, ready.size)
        assertArrayEquals(first.readBytes(), original(ready.single().id).readBytes())
        assertEquals(1, originals().listFiles().orEmpty().size)
        assertEquals(PhotoLimits.MAX_BYTES_PER_DRAFT, budget.sumOf { it.byteSize } + ready.single().byteSize)
    }

    @Test
    fun selectionLimits_countExistingAndJournalPhotoOnlyOnce() = runBlocking {
        val session = UUID.randomUUID().toString()
        val imported = store.importSelection(session, listOf(Uri.fromFile(imageFile(10, 10))))
        val existing = List(11) { metadataPhoto(1L) } + imported

        val resumed = store.importSelection(session, existingPhotos = existing)

        assertEquals(imported, resumed)
        assertEquals(1, originals().listFiles().orEmpty().size)
    }

    private fun metadataPhoto(bytes: Long) = DraftPhoto(UUID.randomUUID().toString(), "image/png", bytes, 1, 1)

    private fun originals() = File(isolatedContext.noBackupFilesDir, "inspection_photos")
    private fun original(id: String) = File(originals(), "$id.image")
    private fun capture(id: String) = File(isolatedContext.filesDir, "camera_capture/$id.jpg")

    private fun imageFile(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val file = File(root, "fixture_${UUID.randomUUID()}.source")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(32, 96, 180))
            file.outputStream().use { check(bitmap.compress(format, 95, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
