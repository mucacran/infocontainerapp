package com.mucacran.infocontainer.ui

import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mucacran.infocontainer.InfoContainerApp
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.network.ConnectionProbe
import com.mucacran.infocontainer.network.ConnectionResult
import com.mucacran.infocontainer.ui.theme.InfoContainerTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** Debug-only host: real app navigation, in-memory drafts, and a probe that never opens a socket. */
class ConnectionNavigationTestActivity : ComponentActivity() {
    lateinit var formState: InspectionFormState
        private set
    lateinit var connectionState: ConnectionState
        private set
    lateinit var fixture: ConnectionNavigationFixture
        private set
    val instanceId: String = UUID.randomUUID().toString()

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caseId = requireNotNull(intent.getStringExtra(EXTRA_CASE_ID))
        require(UUID.fromString(caseId).toString() == caseId)
        val fixtureRoot = File(cacheDir, "connection_ui_test_$caseId").apply { check(mkdirs() || isDirectory) }
        val storageContext = object : ContextWrapper(this) {
            override fun getFilesDir() = File(fixtureRoot, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixtureRoot, "no_backup").apply { mkdirs() }
        }
        val photoStore = LocalPhotoStore(storageContext)
        fixture = ViewModelProvider(this)[ConnectionNavigationFixture::class.java]
        formState = ViewModelProvider(this)[InspectionFormState::class.java]
        connectionState = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ConnectionState::class.java)
                return ConnectionState(fixture.probe) as T
            }
        })[ConnectionState::class.java]
        setContent {
            InfoContainerTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    InfoContainerApp(fixture, formState, photoStore, connectionState)
                }
            }
        }
    }

    companion object {
        const val EXTRA_CASE_ID = "connection_test_case_id"
    }
}

/** No credential is retained by the fake probe, and no production repository is constructed. */
class ConnectionNavigationFixture : ViewModel(), InspectionDraftRepository {
    val requests = AtomicInteger(0)
    val cancellations = AtomicInteger(0)
    val writes = AtomicInteger(0)
    val response = CompletableDeferred<ConnectionResult>()
    val probe = object : ConnectionProbe {
        override suspend fun check(username: String, applicationPassword: String): ConnectionResult {
            requests.incrementAndGet()
            return try {
                response.await()
            } catch (cancelled: CancellationException) {
                cancellations.incrementAndGet()
                throw cancelled
            }
        }
    }
    val existingDraft = InspectionDraft(
        id = "isolated-navigation-fixture",
        containerIdentifier = "CSQU3054383",
        isoCode = "45G1",
        inspectionType = "inventory",
        inspectedAt = "2026-10-17T10:11:12-05:00",
        observations = "Referencia guardada sólo en memoria",
        createdAt = 100L,
        updatedAt = 200L
    )

    override suspend fun getDraftById(id: String): Result<InspectionDraft?> =
        Result.success(existingDraft.takeIf { it.id == id })

    override suspend fun getAllDrafts(): Result<List<InspectionDraft>> = Result.success(listOf(existingDraft))

    override suspend fun createDraft(draft: InspectionDraft): Result<Boolean> = rejectWrite()
    override suspend fun updateDraft(draft: InspectionDraft): Result<Boolean> = rejectWrite()
    override suspend fun deleteDraft(id: String): Result<Boolean> = rejectWrite()

    private fun rejectWrite(): Result<Boolean> {
        writes.incrementAndGet()
        return Result.failure(IllegalStateException("Navigation tests must not write drafts."))
    }
}
