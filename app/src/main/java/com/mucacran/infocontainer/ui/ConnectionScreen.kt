package com.mucacran.infocontainer.ui

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mucacran.infocontainer.R
import com.mucacran.infocontainer.network.ConnectionResult
import com.mucacran.infocontainer.network.WordPressConfig
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.data.AiIdentificationCoordinator
import com.mucacran.infocontainer.model.DraftPhoto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    state: ConnectionState,
    onBack: () -> Unit,
    photoTransfers: PhotoTransferState? = null,
    photoStore: LocalPhotoStore? = null,
    aiDemo: AiDemoState? = null
    , aiCoordinator: AiIdentificationCoordinator? = null
    , identificationPhotos: List<DraftPhoto> = emptyList()
    , onApplyIdentification: (String, String) -> Unit = { _, _ -> }
    , aiAnalysis: AiAnalysisState? = null
) {
    val context = LocalContext.current
    val realIdentificationBusy = aiDemo?.realBusy == true
    // Exclude credentials from screenshots/recents and Android's autofill hierarchy.
    DisposableEffect(context) {
        val activity = context as? Activity
        val window = activity?.window
        val wasSecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        val decor = window?.decorView
        val previousAutofill = decor?.importantForAutofill
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        decor?.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose {
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (previousAutofill != null) decor.importantForAutofill = previousAutofill
        }
    }
    BackHandler(onBack = { if (!realIdentificationBusy) onBack() })
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connection_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = !realIdentificationBusy,
                        modifier = Modifier.testTag("connection_back")) {
                        Text(stringResource(R.string.cd_back_button))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState()).testTag("connection_scroll").padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(WordPressConfig.BASE_URL, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.connection_instructions))
            OutlinedTextField(
                value = state.username,
                onValueChange = state::updateUsername,
                enabled = !state.isChecking && !realIdentificationBusy && photoTransfers?.isBusy != true,
                label = { Text(stringResource(R.string.connection_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().testTag("connection_username")
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = state::updatePassword,
                enabled = !state.isChecking && !realIdentificationBusy && photoTransfers?.isBusy != true,
                label = { Text(stringResource(R.string.connection_application_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("connection_password")
            )
            Button(
                onClick = { if (photoTransfers?.isBusy != true && !realIdentificationBusy) state.testConnection() },
                enabled = state.canTest && !realIdentificationBusy && photoTransfers?.isBusy != true,
                modifier = Modifier.fillMaxWidth().testTag("connection_test")
            ) { Text(stringResource(R.string.connection_test)) }
            if (state.isChecking) CircularProgressIndicator()
            val status = if (state.isChecking) R.string.connection_checking else when (state.result) {
                ConnectionResult.SUCCESS -> R.string.connection_success
                ConnectionResult.CREDENTIALS_REJECTED -> R.string.connection_rejected
                ConnectionResult.PERMISSION_DENIED -> R.string.connection_forbidden
                ConnectionResult.TLS_FAILURE -> R.string.connection_tls_failure
                ConnectionResult.UNAVAILABLE -> R.string.connection_unavailable
                ConnectionResult.TIMEOUT -> R.string.connection_timeout
                ConnectionResult.INVALID_RESPONSE -> R.string.connection_invalid_response
                null -> null
            }
            if (status != null) {
                Text(
                    stringResource(status),
                    modifier = Modifier.testTag("connection_status").semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Text(stringResource(R.string.connection_memory_notice), style = MaterialTheme.typography.bodySmall)
            if (photoTransfers != null && photoStore != null) {
                PhotoTransferPanel(photoTransfers, state, photoStore)
            }
            if (aiDemo != null) AiDemoPanel(aiDemo, aiCoordinator, state.username, state.password,
                identificationPhotos, photoStore, onApplyIdentification)
            if (aiAnalysis != null) AiAnalysisPanel(aiAnalysis, state.username, state.password)
        }
    }
}
