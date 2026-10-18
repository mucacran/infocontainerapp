package com.mucacran.infocontainer.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.data.PhotoUploadSource
import com.mucacran.infocontainer.data.transfer.PhotoTransferMetadata
import com.mucacran.infocontainer.data.transfer.PhotoTransferStatus
import com.mucacran.infocontainer.data.transfer.PhotoTransferStore
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.network.ConnectionProbe
import com.mucacran.infocontainer.network.ConnectionResult
import com.mucacran.infocontainer.network.PhotoApi
import com.mucacran.infocontainer.network.RemotePhoto
import com.mucacran.infocontainer.network.UploadPhotoPayload
import com.mucacran.infocontainer.network.VerifiedPhotoDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real validation/journal, synthetic private PNG and memory-only drafts; the fake API opens no socket. */
@RunWith(AndroidJUnit4::class)
class PhotoTransferSessionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val states = mutableListOf<PhotoTransferState>()
    private lateinit var root: File
    private lateinit var photoStore: LocalPhotoStore
    private lateinit var source: PhotoUploadSource
    private lateinit var journal: PhotoTransferStore
    private lateinit var repository: ReadOnlyDraftFixture
    private lateinit var draft: InspectionDraft
    private lateinit var api: GatedPhotoApi
    private lateinit var originalBytes: ByteArray

    @Before
    fun setUp() = runBlocking {
        val app = instrumentation.targetContext.applicationContext
        root = File(app.cacheDir, "photo_transfer_session_test_${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
        }
        photoStore = LocalPhotoStore(isolated)
        source = PhotoUploadSource(photoStore)
        journal = newJournal()
        val fixture = File(root, "synthetic.png")
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(45, 100, 160))
            fixture.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        originalBytes = fixture.readBytes()
        val photo = photoStore.importPhoto(Uri.fromFile(fixture))
        draft = InspectionDraft(
            id = UUID.randomUUID().toString(),
            observations = "Borrador sintético inmutable de la prueba de sesión",
            createdAt = 100L,
            updatedAt = 200L,
            photos = listOf(photo)
        )
        repository = ReadOnlyDraftFixture(draft)
        api = GatedPhotoApi()
    }

    @After
    fun tearDown() {
        onMain { states.forEach(PhotoTransferState::close) }
        val cache = instrumentation.targetContext.cacheDir.canonicalFile
        check(root.canonicalFile.parentFile == cache && root.name.startsWith("photo_transfer_session_test_"))
        root.deleteRecursively()
    }

    @Test
    fun doubleUpload_usesOneRequestAndOneDurableUuid_andConfirmsWithoutChangingDraftOrOriginal() = runBlocking {
        val state = newState()
        selectDraft(state)
        onMain {
            state.updateInspection(INSPECTION_ID.toString())
            state.updateCategory("other")
            state.updateDescription("Fotografía sintética 📦")
            state.confirmTestInspection(true)
            state.upload(SYNTHETIC_USER, SYNTHETIC_PASSWORD)
            state.upload(SYNTHETIC_USER, SYNTHETIC_PASSWORD)
            // Retaining/reopening this same ViewModel while busy must not enqueue any action.
            state.open()
            assertTrue(state.isBusy)
        }
        await { api.uploads.get() == 1 }
        val pending = requireNotNull(journal.load(draft.id))
        assertEquals(INSPECTION_ID, pending.remoteInspectionId)
        assertEquals(1, pending.photos.size)
        assertEquals(draft.photos.single().id, pending.photos.single().clientPhotoId)
        assertEquals(PhotoTransferStatus.PENDING, pending.photos.single().status)
        assertEquals(1, File(root, "journal").listFiles().orEmpty().count { it.name.endsWith(".json") })
        val payload = requireNotNull(api.lastPayload)
        assertEquals(pending.photos.single().clientPhotoId, payload.clientPhotoId)
        api.uploadResponse.complete(api.responseFor(payload))
        awaitIdle(state)

        onMain {
            assertEquals(PhotoTransferStatus.COMPLETED, state.selectedRecord?.status)
            assertEquals(REMOTE_PHOTO_ID, state.selectedRecord?.remotePhotoId)
            assertTrue(state.message.orEmpty().startsWith("Fotografía confirmada"))
        }
        assertEquals(1, api.uploads.get())
        assertEquals(1, requireNotNull(journal.load(draft.id)).photos.size)
        assertUnchanged()
    }

    @Test
    fun close_cancelsAndClearsSession_newStateRecoversPendingUuidWithoutSendingOrKeepingCredentials() = runBlocking {
        val state = newState()
        selectDraft(state)
        val credentials = onMain { ConnectionState(ConnectionProbe { _, _ -> ConnectionResult.SUCCESS }) }
        onMain {
            credentials.updateUsername(SYNTHETIC_USER)
            credentials.updatePassword(SYNTHETIC_PASSWORD)
            state.updateInspection(INSPECTION_ID.toString())
            state.confirmTestInspection(true)
            state.upload(credentials.username, credentials.password)
        }
        await { api.uploads.get() == 1 }
        val pending = requireNotNull(journal.load(draft.id))
        onMain {
            state.close()
            credentials.close()
            assertEquals("", credentials.username)
            assertEquals("", credentials.password)
            assertFalse(state.isOpen)
            assertFalse(state.isBusy)
            assertNull(state.selectedDraft)
            assertNull(state.selectedPhotoId)
            assertNull(state.binding)
            assertEquals("", state.inspectionInput)
            assertEquals("", state.description)
            assertTrue(state.remotePhotos.isEmpty())
            assertNull(state.message)
        }
        await { api.cancellations.get() == 1 }
        // A response becoming available after cancellation must not confirm or repopulate the closed screen.
        api.uploadResponse.complete(api.responseFor(requireNotNull(api.lastPayload)))
        onMain {
            assertNull(state.binding)
            assertNull(state.message)
            assertFalse(state.isOpen)
        }
        // A different screen-session ViewModel reads the durable plan, without an automatic retry.
        val reopened = newState(newJournal())
        selectDraft(reopened)
        onMain {
            assertEquals(pending, reopened.binding)
            assertEquals(pending.photos.single().clientPhotoId, reopened.selectedRecord?.clientPhotoId)
            assertEquals(PhotoTransferStatus.PENDING, reopened.selectedRecord?.status)
            assertEquals(INSPECTION_ID, reopened.inspectionId)
            assertFalse(reopened.confirmedTestInspection)
            assertFalse(reopened.canUpload)
            credentials.open()
            assertEquals("", credentials.username)
            assertEquals("", credentials.password)
            assertFalse(credentials.canTest)
        }
        assertEquals(1, api.uploads.get())
        assertEquals(pending, journal.load(draft.id))
        val journalText = File(root, "journal/${draft.id}.json").readText()
        assertFalse(journalText.contains(SYNTHETIC_USER))
        assertFalse(journalText.contains(SYNTHETIC_PASSWORD))
        assertUnchanged()
    }

    @Test
    fun verify_acceptsUuidAndHashCase_andRejectsWrongRemoteIdCategoryOrInspectionAssociation() = runBlocking {
        val measured = source.prepare(draft.photos.single())
        journal.prepare(
            draft.id, INSPECTION_ID,
            PhotoTransferMetadata(measured.clientPhotoId, measured.sha256, measured.byteSize, measured.mimeType, "other")
        )
        journal.complete(draft.id, measured.clientPhotoId, REMOTE_PHOTO_ID)
        val binding = requireNotNull(journal.load(draft.id))
        val state = newState()
        selectDraft(state)
        val valid = RemotePhoto(
            REMOTE_PHOTO_ID, INSPECTION_ID, measured.clientPhotoId.uppercase(), "other",
            measured.mimeType, measured.byteSize, measured.sha256.uppercase()
        )
        api.remoteMetadata = valid
        onMain { state.verifyRemote(valid, SYNTHETIC_USER, SYNTHETIC_PASSWORD) }
        awaitIdle(state)
        onMain { assertTrue(state.message.orEmpty().startsWith("Integridad verificada")) }

        for (wrong in listOf(
            valid.copy(id = REMOTE_PHOTO_ID + 1),
            valid.copy(category = "front"),
            valid.copy(inspectionId = INSPECTION_ID + 1)
        )) {
            api.remoteMetadata = wrong
            onMain { state.verifyRemote(wrong, SYNTHETIC_USER, SYNTHETIC_PASSWORD) }
            awaitIdle(state)
            onMain {
                assertNotNull(state.message)
                assertFalse(state.message.orEmpty().startsWith("Integridad verificada"))
                assertFalse(state.message.orEmpty().startsWith("Recuperando bytes"))
                assertEquals(binding, state.binding)
            }
        }
        assertEquals(0, api.uploads.get())
        assertEquals(4, api.metadataReads.get())
        assertEquals(binding, journal.load(draft.id))
        assertUnchanged()
    }

    @Test
    fun failedDraftSelection_disarmsPreviousUploadWithoutChangingAnySavedData() = runBlocking {
        val state = newState()
        selectDraft(state)
        onMain {
            state.updateInspection(INSPECTION_ID.toString())
            state.confirmTestInspection(true)
            assertTrue(state.canUpload)
            state.selectDraft(UUID.randomUUID().toString())
        }
        awaitIdle(state)
        onMain {
            assertNull(state.selectedDraft)
            assertNull(state.selectedPhotoId)
            assertNull(state.binding)
            assertEquals("", state.inspectionInput)
            assertFalse(state.confirmedTestInspection)
            assertFalse(state.canUpload)
            state.upload(SYNTHETIC_USER, SYNTHETIC_PASSWORD)
        }
        assertEquals(0, api.uploads.get())
        assertNull(journal.load(draft.id))
        assertUnchanged()
    }

    private fun newJournal() = PhotoTransferStore(File(root, "journal"))

    private fun newState(store: PhotoTransferStore = journal): PhotoTransferState = onMain {
        PhotoTransferState(api).also {
            it.attach(repository, source, store)
            states += it
        }
    }

    private suspend fun selectDraft(state: PhotoTransferState) {
        onMain { state.open() }
        awaitIdle(state)
        onMain { state.selectDraft(draft.id) }
        awaitIdle(state)
        onMain { assertEquals(draft.id, state.selectedDraft?.id) }
    }

    private suspend fun awaitIdle(state: PhotoTransferState) = await { onMain { !state.isBusy } }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) delay(10)
        }
    }

    /** Catch assertions on main so any failure reaches the test runner instead of crashing its Looper. */
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private suspend fun assertUnchanged() {
        assertEquals(0, repository.writes.get())
        assertEquals(draft, repository.getDraftById(draft.id).getOrThrow())
        assertArrayEquals(originalBytes, source.prepare(draft.photos.single()).file.readBytes())
    }

    private class ReadOnlyDraftFixture(private val draft: InspectionDraft) : InspectionDraftRepository {
        val writes = AtomicInteger()
        override suspend fun getDraftById(id: String): Result<InspectionDraft?> = Result.success(draft.takeIf { it.id == id })
        override suspend fun getAllDrafts(): Result<List<InspectionDraft>> = Result.success(listOf(draft))
        override suspend fun createDraft(draft: InspectionDraft): Result<Boolean> = rejectWrite()
        override suspend fun updateDraft(draft: InspectionDraft): Result<Boolean> = rejectWrite()
        override suspend fun deleteDraft(id: String): Result<Boolean> = rejectWrite()
        private fun rejectWrite(): Result<Boolean> {
            writes.incrementAndGet()
            return Result.failure(IllegalStateException("Session fixture forbids draft writes"))
        }
    }

    /** Stores no supplied username/password. Its counters are simulated calls, never HTTP exchanges. */
    private class GatedPhotoApi : PhotoApi {
        val uploads = AtomicInteger()
        val cancellations = AtomicInteger()
        val metadataReads = AtomicInteger()
        val uploadResponse = CompletableDeferred<RemotePhoto>()
        var lastPayload: UploadPhotoPayload? = null
        var remoteMetadata: RemotePhoto? = null

        override suspend fun upload(
            username: String, applicationPassword: String, inspectionId: Long, payload: UploadPhotoPayload
        ): RemotePhoto {
            lastPayload = payload
            uploads.incrementAndGet()
            return try {
                uploadResponse.await()
            } catch (cancelled: CancellationException) {
                cancellations.incrementAndGet()
                throw cancelled
            }
        }

        fun responseFor(payload: UploadPhotoPayload) = RemotePhoto(
            REMOTE_PHOTO_ID, INSPECTION_ID, payload.clientPhotoId, payload.category,
            payload.mimeType, payload.byteSize, payload.sha256
        )

        override suspend fun list(username: String, applicationPassword: String, inspectionId: Long): List<RemotePhoto> =
            listOfNotNull(remoteMetadata)

        override suspend fun metadata(username: String, applicationPassword: String, photoId: Long): RemotePhoto {
            metadataReads.incrementAndGet()
            return requireNotNull(remoteMetadata)
        }

        override suspend fun downloadVerified(
            username: String, applicationPassword: String, photo: RemotePhoto
        ) = VerifiedPhotoDownload(photo.id, photo.byteSize, photo.sha256.lowercase())
    }

    private companion object {
        const val INSPECTION_ID = 501L
        const val REMOTE_PHOTO_ID = 7001L
        const val SYNTHETIC_USER = "synthetic-user-no-server"
        const val SYNTHETIC_PASSWORD = "synthetic-value-for-socket-free-test"
    }
}
