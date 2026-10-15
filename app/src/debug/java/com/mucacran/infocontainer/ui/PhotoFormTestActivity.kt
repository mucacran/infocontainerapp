package com.mucacran.infocontainer.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.mucacran.infocontainer.data.InspectionDatabaseHelper
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.data.SqliteInspectionDraftRepository
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.ui.theme.InfoContainerTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only UI fixture. Its caller must provide a unique UUID; production storage is never selected. */
class PhotoFormTestActivity : ComponentActivity() {
    lateinit var formState: InspectionFormState
        private set
    lateinit var photoStore: LocalPhotoStore
        private set
    lateinit var repository: SqliteInspectionDraftRepository
        private set
    lateinit var fixtureRoot: File
        private set
    lateinit var fixtureDraftId: String
        private set
    private lateinit var helper: InspectionDatabaseHelper
    private var ready by mutableStateOf(false)
    val instanceId: String = UUID.randomUUID().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caseId = requireNotNull(intent.getStringExtra(EXTRA_CASE_ID))
        require(UUID.fromString(caseId).toString() == caseId)
        fixtureRoot = File(cacheDir, "form_photo_ui_test_$caseId").apply { check(mkdirs() || isDirectory) }
        val storageContext = object : ContextWrapper(this) {
            override fun getFilesDir() = File(fixtureRoot, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixtureRoot, "no_backup").apply { mkdirs() }
        }
        photoStore = LocalPhotoStore(storageContext)
        helper = InspectionDatabaseHelper(this, databaseName(caseId))
        repository = SqliteInspectionDraftRepository(helper, photoStore::exists)
        formState = ViewModelProvider(this)[InspectionFormState::class.java]
        fixtureDraftId = "fixture-$caseId"
        val existing = intent.getBooleanExtra(EXTRA_EXISTING, false)
        val requestedDraft = intent.getStringExtra(EXTRA_DRAFT_ID) ?: fixtureDraftId.takeIf { existing }

        // Always install the Compose hierarchy, including after a real orientation change.
        setContent {
            InfoContainerTheme(dynamicColor = false) {
                if (ready) {
                    InspectionFormScreen(repository, requestedDraft, { backCalls.incrementAndGet() }, formState, photoStore)
                } else CircularProgressIndicator()
            }
        }
        lifecycleScope.launch {
            if (existing && savedInstanceState == null && repository.getDraftById(fixtureDraftId).getOrThrow() == null) {
                val photo = photoStore.importPhoto(Uri.fromFile(createSyntheticImage()))
                repository.createDraft(
                    InspectionDraft(
                        id = fixtureDraftId,
                        containerIdentifier = "CSQU3054383",
                        isoCode = "45G1",
                        inspectionType = "inventory",
                        inspectedAt = "2026-10-15T10:11:12-05:00",
                        observations = "Texto guardado de la prueba",
                        createdAt = 100L,
                        updatedAt = 200L,
                        photos = listOf(photo)
                    )
                ).getOrThrow()
            }
            ready = true
        }
    }

    /** Imports real PNG bytes through the production store; no device gallery is accessed. */
    fun attachSyntheticPhoto() {
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            formState.addPhotos {
                listOf(photoStore.importPhoto(Uri.fromFile(createSyntheticImage())))
            }
        }
    }

    private suspend fun createSyntheticImage(): File = withContext(Dispatchers.IO) {
        val file = File(fixtureRoot, "fixture_${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(72, 48, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(16, 96, 176))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        file
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::helper.isInitialized) helper.close()
    }

    companion object {
        const val EXTRA_CASE_ID = "photo_test_case_id"
        const val EXTRA_EXISTING = "photo_test_existing"
        const val EXTRA_DRAFT_ID = "photo_test_draft_id"
        val backCalls = AtomicInteger(0)
        fun databaseName(caseId: String): String {
            require(UUID.fromString(caseId).toString() == caseId)
            return "form_photo_ui_test_$caseId.db"
        }
    }
}
