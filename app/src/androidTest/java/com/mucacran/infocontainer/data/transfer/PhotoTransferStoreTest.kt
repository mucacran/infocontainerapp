package com.mucacran.infocontainer.data.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mucacran.infocontainer.network.WordPressConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Only UUID cache fixtures are used. No production database, photograph or credentials are read. */
@RunWith(AndroidJUnit4::class)
class PhotoTransferStoreTest {
    private lateinit var fixture: File
    private lateinit var directory: File
    private lateinit var store: PhotoTransferStore
    private lateinit var draftId: String

    @Before
    fun setUp() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        fixture = File(cache, "photo_transfer_test_${UUID.randomUUID()}").apply { check(mkdirs()) }
        directory = File(fixture, "journal")
        store = PhotoTransferStore(directory)
        draftId = UUID.randomUUID().toString()
    }

    @After
    fun tearDown() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        check(fixture.canonicalFile.parentFile == cache && fixture.name.startsWith("photo_transfer_test_"))
        fixture.deleteRecursively()
    }

    @Test
    fun missingJournal_doesNotCreateOrInferAnUpload() = runBlocking {
        assertNull(store.load(draftId))
        assertFalse(directory.exists())
        expect(TransferJournalException.Reason.NOT_PREPARED) {
            store.complete(draftId, UUID.randomUUID().toString(), 73)
        }
        assertFalse(directory.exists())
    }

    @Test
    fun prepare_isDurableBeforeReturnAndRetryReusesExactUuidAndPayload() = runBlocking {
        val photo = metadata()
        val first = store.prepare(draftId, 71, photo)
        val written = file().readBytes()

        val reopened = PhotoTransferStore(directory)
        val record = reopened.load(draftId)!!
        assertEquals(WordPressConfig.BASE_URL, record.origin)
        assertEquals(draftId, record.localDraftId)
        assertEquals(71L, record.remoteInspectionId)
        assertEquals(listOf(first), record.photos)
        assertEquals(photo.localPhotoId, first.clientPhotoId)
        assertEquals(PhotoTransferStatus.PENDING, first.status)
        assertNull(first.remotePhotoId)
        assertEquals(first, reopened.prepare(draftId, 71, photo))
        assertArrayEquals(written, file().readBytes())
    }

    @Test
    fun interruptedRequest_leavesPendingAndManualRetryDoesNotInventRemoteSuccess() = runBlocking {
        val photo = metadata()
        val preparedBeforeNetwork = store.prepare(draftId, 71, photo)
        // A timeout/terminated process has no successful response, so complete is deliberately absent.
        val reopened = PhotoTransferStore(directory)
        assertEquals(preparedBeforeNetwork, reopened.prepare(draftId, 71, photo))
        assertEquals(PhotoTransferStatus.PENDING, reopened.load(draftId)!!.photos.single().status)
        assertNull(reopened.load(draftId)!!.photos.single().remotePhotoId)
    }

    @Test
    fun complete_persistsRemoteAssociationAndRepeatsIdempotently() = runBlocking {
        val photo = metadata()
        store.prepare(draftId, 71, photo)
        val complete = store.complete(draftId, photo.localPhotoId, 73)
        assertEquals(PhotoTransferStatus.COMPLETED, complete.status)
        assertEquals(73L, complete.remotePhotoId)
        val persisted = file().readBytes()
        val reopened = PhotoTransferStore(directory)
        assertEquals(complete, reopened.load(draftId)!!.photos.single())
        assertEquals(complete, reopened.complete(draftId, photo.localPhotoId, 73))
        assertEquals(complete, reopened.prepare(draftId, 71, photo))
        assertArrayEquals(persisted, file().readBytes())
        expect(TransferJournalException.Reason.REMOTE_ID_CONFLICT) {
            reopened.complete(draftId, photo.localPhotoId, 74)
        }
        assertArrayEquals(persisted, file().readBytes())
    }

    @Test
    fun existingBindingAndEveryPayloadField_areImmutable() = runBlocking {
        val photo = metadata()
        store.prepare(draftId, 71, photo)
        val persisted = file().readBytes()
        expect(TransferJournalException.Reason.BINDING_CONFLICT) { store.prepare(draftId, 72, photo) }
        val variants = listOf(
            photo.copy(sha256 = "b".repeat(64)), photo.copy(byteSize = photo.byteSize + 1),
            photo.copy(mimeType = "image/jpeg"), photo.copy(category = "damage"),
            photo.copy(description = "Changed"), photo.copy(capturedAt = "2026-10-17T17:00:00Z")
        )
        variants.forEach { altered ->
            expect(TransferJournalException.Reason.PHOTO_CONFLICT) { store.prepare(draftId, 71, altered) }
            assertArrayEquals(persisted, file().readBytes())
        }
        assertEquals(photo, store.load(draftId)!!.photos.single().metadata)
    }

    @Test
    fun oneRemotePhotoCannotBeBoundToTwoLocalPhotos() = runBlocking {
        val first = metadata()
        val second = metadata()
        store.prepare(draftId, 71, first)
        store.prepare(draftId, 71, second)
        store.complete(draftId, first.localPhotoId, 73)
        val persisted = file().readBytes()
        expect(TransferJournalException.Reason.REMOTE_ID_CONFLICT) {
            store.complete(draftId, second.localPhotoId, 73)
        }
        assertArrayEquals(persisted, file().readBytes())
        assertEquals(PhotoTransferStatus.PENDING, store.load(draftId)!!.photos.last().status)
    }

    @Test
    fun malformedOrConflictingJournal_failsClosedWithoutReplacingEvidence() = runBlocking {
        val photo = metadata()
        store.prepare(draftId, 71, photo)
        val valid = file().readText()
        val invalid = listOf(
            "{", "$valid {}", valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"byteSize\":37", "\"byteSize\":\"37\""),
            valid.replace("\"origin\":\"${WordPressConfig.BASE_URL}\"", "\"origin\":\"https://invalid.example\""),
            valid.replace("\"clientPhotoId\":\"${photo.localPhotoId}\"", "\"clientPhotoId\":\"${UUID.randomUUID()}\""),
            valid.replace("\"status\":\"PENDING\"", "\"status\":\"COMPLETED\""),
            valid.replace("\"remotePhotoId\":null", "\"remotePhotoId\":73"),
            valid.replace("\"remoteInspectionId\":71", "\"remoteInspectionId\":0"),
            valid.replace("\"localDraftId\":\"$draftId\"", "\"localDraftId\":\"${UUID.randomUUID()}\""),
            valid.replace("\"version\":1", "version:1")
        )
        invalid.forEach { corrupt ->
            assertTrue("Fixture must actually differ", corrupt != valid)
            file().writeText(corrupt)
            val bytes = file().readBytes()
            val reopened = PhotoTransferStore(directory)
            expect(TransferJournalException.Reason.CORRUPT) { reopened.load(draftId) }
            expect(TransferJournalException.Reason.CORRUPT) { reopened.prepare(draftId, 71, photo) }
            expect(TransferJournalException.Reason.CORRUPT) { reopened.complete(draftId, photo.localPhotoId, 73) }
            assertArrayEquals(bytes, file().readBytes())
        }
    }

    @Test
    fun concurrentInstances_serializeReadModifyWriteAndSamePhotoPreparation() = runBlocking {
        val photos = (0 until 8).map { metadata() }
        coroutineScope {
            photos.flatMap { photo ->
                (0 until 3).map { async { PhotoTransferStore(directory).prepare(draftId, 71, photo) } }
            }.awaitAll()
        }
        val record = store.load(draftId)!!
        assertEquals(8, record.photos.size)
        assertEquals(photos.toSet(), record.photos.map { it.metadata }.toSet())
        assertEquals(photos.map { it.localPhotoId }.toSet(), record.photos.map { it.clientPhotoId }.toSet())
    }

    @Test
    fun invalidInput_isRejectedWithoutCreatingFilesOrTouchingOriginal() = runBlocking {
        val original = File(fixture, "synthetic-original.png").apply { writeBytes(ByteArray(37) { it.toByte() }) }
        val bytes = original.readBytes()
        val photo = metadata().copy(sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val invalid = listOf(
            photo.copy(localPhotoId = "../original"), photo.copy(sha256 = "invalid"),
            photo.copy(byteSize = 10L * 1024 * 1024 + 1), photo.copy(byteSize = 0),
            photo.copy(mimeType = "image/gif"), photo.copy(category = "unknown"),
            photo.copy(description = "a".repeat(2001)), photo.copy(capturedAt = "2026-10-17T12:00Z"),
            photo.copy(capturedAt = "2026-15-17T12:00:00Z")
        )
        invalid.forEach { expect(TransferJournalException.Reason.INVALID_INPUT) { store.prepare(draftId, 71, it) } }
        expect(TransferJournalException.Reason.INVALID_INPUT) { store.prepare("../original", 71, photo) }
        expect(TransferJournalException.Reason.INVALID_INPUT) { store.prepare(draftId, 0, photo) }
        assertFalse(directory.exists())
        store.prepare(draftId, 71, photo)
        store.complete(draftId, photo.localPhotoId, 73)
        assertArrayEquals(bytes, original.readBytes())
        assertEquals(listOf("journal", "synthetic-original.png"), fixture.list()!!.sorted())
    }

    @Test
    fun legacyAtomicBackup_isRecoveredWithoutChangingPreparedIdentity() = runBlocking {
        val photo = metadata()
        val prepared = store.prepare(draftId, 71, photo)
        val valid = file().readBytes()
        check(file().renameTo(File(file().path + ".bak")))
        file().writeText("interrupted write")
        val reopened = PhotoTransferStore(directory)
        assertEquals(prepared, reopened.prepare(draftId, 71, photo))
        assertArrayEquals(valid, file().readBytes())
    }

    private fun file() = File(directory, "$draftId.json")
    private fun metadata() = PhotoTransferMetadata(
        UUID.randomUUID().toString(), "a".repeat(64), 37, "image/png", "other", "Synthetic test image"
    )

    private suspend fun expect(reason: TransferJournalException.Reason, action: suspend () -> Any?) {
        try {
            action()
            throw AssertionError("Expected $reason")
        } catch (failure: TransferJournalException) {
            assertEquals(reason, failure.reason)
        }
    }
}
