package com.mucacran.infocontainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.mucacran.infocontainer.data.InspectionDatabaseHelper
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.data.SqliteInspectionDraftRepository
import com.mucacran.infocontainer.ui.DraftListScreen
import com.mucacran.infocontainer.ui.HomeScreen
import com.mucacran.infocontainer.ui.InspectionFormScreen
import com.mucacran.infocontainer.ui.InspectionFormState
import com.mucacran.infocontainer.ui.FormRecoveryStore
import com.mucacran.infocontainer.ui.ConnectionScreen
import com.mucacran.infocontainer.ui.ConnectionState
import com.mucacran.infocontainer.ui.PhotoTransferState
import com.mucacran.infocontainer.ui.AiDemoState
import com.mucacran.infocontainer.ui.AiAnalysisState
import com.mucacran.infocontainer.data.AiIdentificationCoordinator
import com.mucacran.infocontainer.data.AiAnalysisCoordinator
import com.mucacran.infocontainer.network.AiExecutionGate
import com.mucacran.infocontainer.network.WordPressAiApi
import com.mucacran.infocontainer.data.PhotoUploadSource
import com.mucacran.infocontainer.data.transfer.PhotoTransferStore
import com.mucacran.infocontainer.data.TestInspectionStore
import com.mucacran.infocontainer.ui.theme.InfoContainerTheme

enum class Screen {
    HOME,
    NEW_INSPECTION,
    SAVED_INSPECTIONS,
    CONNECTION
}

class MainActivity : ComponentActivity() {
    private lateinit var repository: InspectionDraftRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dbHelper = InspectionDatabaseHelper(applicationContext)
        val photoStore = LocalPhotoStore(applicationContext)
        repository = SqliteInspectionDraftRepository(dbHelper, photoExists = photoStore::exists)
        val formState = ViewModelProvider(this)[InspectionFormState::class.java]
        val connectionState = ViewModelProvider(this)[ConnectionState::class.java]
        val photoTransfers = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                PhotoTransferState(debugEnabled = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) as T
        })[PhotoTransferState::class.java]
        val aiDemo = ViewModelProvider(this)[AiDemoState::class.java]
        val aiCoordinator = AiIdentificationCoordinator(WordPressAiApi(), photoStore, AiExecutionGate(false))
        val aiAnalysis = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = AiAnalysisState(WordPressAiApi(), AiExecutionGate(false)) as T
        })[AiAnalysisState::class.java]
        val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) aiAnalysis.attachCoordinator(AiAnalysisCoordinator.simulated())
        photoTransfers.attach(repository, PhotoUploadSource(photoStore), PhotoTransferStore(applicationContext))
        photoTransfers.attachTestStore(TestInspectionStore(applicationContext))
        formState.attachRecoveryStore(FormRecoveryStore(applicationContext), photoStore)

        enableEdgeToEdge()
        setContent {
            InfoContainerTheme {
                InfoContainerApp(repository = repository, formState = formState, photoStore = photoStore, connectionState = connectionState, photoTransfers = photoTransfers, aiDemo = if (isDebug) aiDemo else null, aiCoordinator = aiCoordinator, aiAnalysis = if (isDebug) aiAnalysis else null)
            }
        }
    }
}

@Composable
fun InfoContainerApp(
    repository: InspectionDraftRepository,
    formState: InspectionFormState,
    photoStore: LocalPhotoStore,
    connectionState: ConnectionState,
    photoTransfers: PhotoTransferState? = null,
    aiDemo: AiDemoState? = null
    , aiCoordinator: AiIdentificationCoordinator? = null
    , aiAnalysis: AiAnalysisState? = null
) {
    var currentScreen by rememberSaveable { mutableStateOf(if (formState.recoveryPending) Screen.NEW_INSPECTION else Screen.HOME) }
    var selectedDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var connectionReturnScreen by rememberSaveable { mutableStateOf(Screen.HOME) }

    fun openConnection(from: Screen) {
        photoTransfers?.close()
        connectionState.open()
        connectionReturnScreen = from
        currentScreen = Screen.CONNECTION
    }

    when (currentScreen) {
        Screen.HOME -> {
            HomeScreen(
                onNavigateToNewInspection = {
                    if (!formState.open(null)) return@HomeScreen
                    selectedDraftId = null
                    previousScreen = Screen.HOME
                    currentScreen = Screen.NEW_INSPECTION
                },
                onNavigateToSavedInspections = {
                    currentScreen = Screen.SAVED_INSPECTIONS
                },
                onNavigateToConnection = { openConnection(Screen.HOME) }
            )
        }
        Screen.NEW_INSPECTION -> {
            InspectionFormScreen(
                repository = repository,
                formState = formState,
                photoStore = photoStore,
                draftId = selectedDraftId,
                onBack = {
                    if (formState.canLeave) currentScreen = previousScreen
                },
                onOpenConnection = {
                    if (formState.canEdit && !formState.recoveryPending) openConnection(Screen.NEW_INSPECTION)
                }
            )
        }
        Screen.SAVED_INSPECTIONS -> {
            DraftListScreen(
                repository = repository,
                photoStore = photoStore,
                onSelectDraft = { draftId ->
                    if (!formState.open(draftId)) return@DraftListScreen
                    selectedDraftId = draftId
                    previousScreen = Screen.SAVED_INSPECTIONS
                    currentScreen = Screen.NEW_INSPECTION
                },
                onNewInspection = {
                    if (!formState.open(null)) return@DraftListScreen
                    selectedDraftId = null
                    previousScreen = Screen.SAVED_INSPECTIONS
                    currentScreen = Screen.NEW_INSPECTION
                },
                onBack = { currentScreen = Screen.HOME }
            )
        }
        Screen.CONNECTION -> {
            ConnectionScreen(connectionState, photoTransfers = photoTransfers, photoStore = photoStore, aiDemo = aiDemo, aiCoordinator = aiCoordinator, aiAnalysis = aiAnalysis, onApplyIdentification = { identifier, iso -> formState.updateContent(formState.content.copy(containerIdentifier = identifier, isoCode = iso)) }, onBack = {
                photoTransfers?.close()
                connectionState.close()
                currentScreen = connectionReturnScreen
            })
        }
    }
}
