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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mucacran.infocontainer.ui.HomeScreen
import com.mucacran.infocontainer.ui.PlaceholderScreen
import com.mucacran.infocontainer.ui.theme.InfoContainerTheme

enum class Screen {
    HOME,
    NEW_INSPECTION,
    SAVED_INSPECTIONS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InfoContainerTheme {
                InfoContainerApp()
            }
        }
    }
}

@Composable
fun InfoContainerApp() {
    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }

    when (currentScreen) {
        Screen.HOME -> {
            HomeScreen(
                onNavigateToNewInspection = { currentScreen = Screen.NEW_INSPECTION },
                onNavigateToSavedInspections = { currentScreen = Screen.SAVED_INSPECTIONS }
            )
        }
        Screen.NEW_INSPECTION -> {
            PlaceholderScreen(
                title = stringResource(id = R.string.title_new_inspection),
                onBack = { currentScreen = Screen.HOME }
            )
        }
        Screen.SAVED_INSPECTIONS -> {
            PlaceholderScreen(
                title = stringResource(id = R.string.title_saved_inspections),
                onBack = { currentScreen = Screen.HOME }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    InfoContainerTheme {
        HomeScreen(
            onNavigateToNewInspection = {},
            onNavigateToSavedInspections = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PlaceholderScreenPreview() {
    InfoContainerTheme {
        PlaceholderScreen(
            title = stringResource(id = R.string.title_new_inspection),
            onBack = {}
        )
    }
}
