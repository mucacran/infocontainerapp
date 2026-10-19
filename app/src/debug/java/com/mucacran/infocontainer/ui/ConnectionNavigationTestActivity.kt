package com.mucacran.infocontainer.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.core.graphics.createBitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mucacran.infocontainer.InfoContainerApp
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.data.AiAnalysisCoordinator
import com.mucacran.infocontainer.data.AiIdentificationCoordinator
import com.mucacran.infocontainer.data.IdentificationAttemptStore
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.network.ConnectionProbe
import com.mucacran.infocontainer.network.ConnectionResult
import com.mucacran.infocontainer.network.AiExecutionGate
import com.mucacran.infocontainer.network.SimulatedAiApi
import com.mucacran.infocontainer.network.AiApi
import com.mucacran.infocontainer.network.AiRun
import com.mucacran.infocontainer.network.IdentificationCandidate
import com.mucacran.infocontainer.network.IdentificationProposal
import com.mucacran.infocontainer.network.IdentificationRun
import com.mucacran.infocontainer.network.AnalysisRun
import com.mucacran.infocontainer.network.AiReviewResponse
import com.mucacran.infocontainer.ui.theme.InfoContainerTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/** Debug-only host: real app navigation, in-memory drafts, and a probe that never opens a socket. */
class ConnectionNavigationTestActivity : ComponentActivity() {
    lateinit var formState: InspectionFormState
        private set
    lateinit var connectionState: ConnectionState
        private set
    lateinit var fixture: ConnectionNavigationFixture
        private set
    var aiAnalysis: AiAnalysisState? = null
        private set
    var aiDemo: AiDemoState? = null
        private set
    val instanceId: String = UUID.randomUUID().toString()

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caseId = requireNotNull(intent.getStringExtra(EXTRA_CASE_ID))
        require(UUID.fromString(caseId).toString() == caseId)
        val fixtureRoot = File(cacheDir, "connection_ui_test_$caseId").apply { check(mkdirs() || isDirectory) }
        val storageContext = object : ContextWrapper(applicationContext) {
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
        val testAnalysis = intent.getBooleanExtra(EXTRA_ANALYSIS, false)
        val testIdentification = intent.getBooleanExtra(EXTRA_IDENTIFICATION, false)
        check(!(testAnalysis && testIdentification))
        if (testAnalysis || testIdentification) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            aiDemo = if (testIdentification) ViewModelProvider(this, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == AiDemoState::class.java)
                    return AiDemoState(realTimeoutMillis = intent.getLongExtra(EXTRA_IDENTIFICATION_TIMEOUT_MILLIS, 2_000L)) as T
                }
            })[AiDemoState::class.java] else ViewModelProvider(this)[AiDemoState::class.java]
        }
        if (testAnalysis) {
            aiAnalysis = ViewModelProvider(this, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == AiAnalysisState::class.java)
                    return AiAnalysisState(fixture.aiApi, AiExecutionGate(false), reviewTimeoutMillis = 2_000) as T
                }
            })[AiAnalysisState::class.java].also { it.attachCoordinator(AiAnalysisCoordinator.simulated(fixture.aiApi)) }
        }
        if (testIdentification) {
            fixture.ensureSyntheticPhoto(photoStore, fixtureRoot)
            val coordinator = fixture.identificationCoordinator ?: AiIdentificationCoordinator(
                fixture.identificationApi, photoStore, AiExecutionGate(false), IdentificationAttemptStore(storageContext)
            ).also { fixture.identificationCoordinator = it }
            aiDemo!!.attachRealCoordinator(coordinator)
        }
        setContent {
            InfoContainerTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    Box(if (testAnalysis || testIdentification) Modifier.widthIn(max = 300.dp).heightIn(max = 480.dp) else Modifier) {
                        InfoContainerApp(fixture, formState, photoStore, connectionState, aiDemo = aiDemo,
                            aiCoordinator = fixture.identificationCoordinator, aiAnalysis = aiAnalysis)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_CASE_ID = "connection_test_case_id"
        const val EXTRA_ANALYSIS = "connection_test_analysis"
        const val EXTRA_IDENTIFICATION = "connection_test_identification"
        const val EXTRA_IDENTIFICATION_TIMEOUT_MILLIS = "connection_test_identification_timeout_millis"
    }
}

/** No credential is retained by the fake probe, and no production repository is constructed. */
class ConnectionNavigationFixture : ViewModel(), InspectionDraftRepository {
    val aiApi = SimulatedAiApi()
    val identificationApi = UiIdentificationFakeApi()
    var identificationCoordinator: AiIdentificationCoordinator? = null
    var syntheticPhoto: DraftPhoto? = null
        private set
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

    /** Created inside the UUID test root; production photographs and databases are never opened. */
    fun ensureSyntheticPhoto(photoStore: LocalPhotoStore, fixtureRoot: File): DraftPhoto {
        syntheticPhoto?.let { check(photoStore.exists(it)); return it }
        val source = File(fixtureRoot, "synthetic_identifier.png")
        val bitmap = createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 58f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            canvas.drawText("TSTU 485593 3", 45f, 215f, paint)
            source.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        } finally {
            bitmap.recycle()
        }
        return runBlocking { photoStore.importPhoto(Uri.fromFile(source)) }.also {
            check(it.mimeType == "image/png" && photoStore.exists(it))
            syntheticPhoto = it
        }
    }
}

/** Counts every synthetic identification POST; it has no HTTP client or production delegate. */
class UiIdentificationFakeApi : AiApi {
    val identifyCalls = AtomicInteger(0)
    val lookupCalls = AtomicInteger(0)
    val clientRequestIds = mutableListOf<String>()
    var holdResponse: CompletableDeferred<Unit>? = null
    var failure: Exception? = null
    var nextStatus: String = "completed"
    var lookupStatus: String? = null
    private val runs = mutableMapOf<Long, IdentificationRun>()

    override suspend fun identify(username: String, password: String, file: File, clientRequestId: String): IdentificationRun {
        check(username.isNotBlank() && password.isNotBlank())
        check(file.isFile && file.length() > 0L && file.name.endsWith(".image"))
        val originals = file.canonicalFile.parentFile
        check(originals.name == "inspection_photos" && originals.parentFile.name == "no_backup" &&
            originals.parentFile.parentFile.name.startsWith("connection_ui_test_"))
        check(UUID.fromString(clientRequestId).toString() == clientRequestId)
        identifyCalls.incrementAndGet()
        synchronized(clientRequestIds) { clientRequestIds.add(clientRequestId) }
        val proposal = if (nextStatus == "completed") completedProposal() else null
        val response = IdentificationRun(
            AiRun(701L, "identification", null, clientRequestId, nextStatus, proposal,
                if (nextStatus == "failed") "Fallo sintético" else null, false, true),
            proposal
        )
        synchronized(runs) { runs[response.run.id] = response }
        holdResponse?.await()
        failure?.let { throw it }
        return response
    }

    override suspend fun getIdentificationRun(username: String, password: String, runId: Long): IdentificationRun {
        check(username.isNotBlank() && password.isNotBlank())
        lookupCalls.incrementAndGet()
        val existing = synchronized(runs) { requireNotNull(runs[runId]) }
        val status = lookupStatus ?: return existing
        val proposal = if (status == "completed") completedProposal() else null
        return existing.copy(run = existing.run.copy(status = status, proposal = proposal), proposal = proposal)
    }

    private fun completedProposal() = IdentificationProposal(
        "TSTU4855933", listOf(IdentificationCandidate("TSTU4855933", true)), "22G1",
        "clear", listOf("Fotografía sintética; verificar visualmente.")
    )

    override suspend fun analyze(username: String, password: String, inspectionId: Long, clientRequestId: String, photoIds: List<Long>?): AnalysisRun =
        error("Identification fixture must not analyze inspections")
    override suspend fun getRun(username: String, password: String, runId: Long): AiRun =
        error("Identification fixture must not call analysis run lookup")
    override suspend fun listRuns(username: String, password: String, inspectionId: Long): List<AiRun> =
        error("Identification fixture must not list analysis runs")
    override suspend fun review(username: String, password: String, runId: Long, clientReviewId: String, action: String, reviewedContent: Any?, identificationResolution: String?): AiReviewResponse =
        error("Identification fixture must not create reviews")
}
