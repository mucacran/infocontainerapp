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
import com.mucacran.infocontainer.ui.theme.InfoContainerTheme

enum class Screen {
    HOME,
    NEW_INSPECTION,
    SAVED_INSPECTIONS
}

class MainActivity : ComponentActivity() {
    private lateinit var repository: InspectionDraftRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dbHelper = InspectionDatabaseHelper(applicationContext)
        val photoStore = LocalPhotoStore(applicationContext)
        repository = SqliteInspectionDraftRepository(dbHelper, photoExists = photoStore::exists)
        val formState = ViewModelProvider(this)[InspectionFormState::class.java]

        enableEdgeToEdge()
        setContent {
            InfoContainerTheme {
                InfoContainerApp(repository = repository, formState = formState, photoStore = photoStore)
            }
        }
    }
}

@Composable
fun InfoContainerApp(
    repository: InspectionDraftRepository,
    formState: InspectionFormState,
    photoStore: LocalPhotoStore
) {
    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var selectedDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousScreen by rememberSaveable { mutableStateOf(Screen.HOME) }

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
                }
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
    }
}
