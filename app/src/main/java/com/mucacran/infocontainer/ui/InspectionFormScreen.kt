package com.mucacran.infocontainer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.mucacran.infocontainer.R
import com.mucacran.infocontainer.data.DraftNotFoundException
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.data.cleanUnreferencedSafely
import com.mucacran.infocontainer.model.InspectionType
import com.mucacran.infocontainer.util.ContainerValidator
import com.mucacran.infocontainer.util.DateTimeUtils
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InspectionFormScreen(
    repository: InspectionDraftRepository,
    draftId: String?,
    onBack: () -> Unit,
    formState: InspectionFormState,
    photoStore: LocalPhotoStore,
    modifier: Modifier = Modifier,
    onOpenConnection: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val photoActions = rememberPhotoActions(formState, photoStore, repository)

    // Textos precargados para Compose Lint
    val savedSuccessMsg = stringResource(id = R.string.draft_saved_success)
    val savedErrorMsg = stringResource(id = R.string.draft_save_error)
    val missingDraftMsg = stringResource(id = R.string.draft_update_not_found)
    val containerIdentifier = formState.content.containerIdentifier
    val isoCode = formState.content.isoCode
    val inspectionType = formState.content.inspectionType
    val inspectedAt = formState.content.inspectedAt
    val observations = formState.content.observations
    val loadState = formState.loadState
    val isSaving = formState.isSaving
    val hasUnsavedChanges = formState.hasUnsavedChanges
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    var exitAfterSave by rememberSaveable { mutableStateOf(false) }
    var showRecoveryDialog by rememberSaveable { mutableStateOf(formState.recoveryPending) }

    LaunchedEffect(formState.photoErrorMessage) {
        formState.photoErrorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(formState, draftId) {
        formState.ensureOpened(draftId)
        formState.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            formState.loadIfNeeded(repository)
        }
    }

    if (showRecoveryDialog && formState.recoveryPending) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Trabajo pendiente recuperado") },
            text = { Text("Se recuperaron los campos y fotografías que estaban pendientes. ¿Quieres continuar editándolos?") },
            confirmButton = {
                Button(onClick = { showRecoveryDialog = false; formState.recoverPersistent() }) { Text("Recuperar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val pending = formState.discardPersistent()
                    showRecoveryDialog = false
                    formState.viewModelScope.launch {
                        runCatching { photoStore.clearSelection(formState.photoSessionId) }
                        pending.forEach { photo -> photoStore.cleanUnreferencedSafely(photo.id, repository) }
                    }
                }) { Text("Descartar") }
            }
        )
    }

    LaunchedEffect(formState.completedSaveCount) {
        val result = formState.consumeSaveResult() ?: return@LaunchedEffect
        val shouldExit = exitAfterSave && result.getOrDefault(false) && !formState.hasUnsavedChanges
        exitAfterSave = false
        if (shouldExit) {
            onBack()
        } else {
            val message = when {
                result.exceptionOrNull() is DraftNotFoundException -> missingDraftMsg
                result.getOrDefault(false) -> savedSuccessMsg
                else -> savedErrorMsg
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val containerValidation = ContainerValidator.validateContainerIdentifier(containerIdentifier)
    val isoCodeValidation = ContainerValidator.validateIsoCode(isoCode)
    val inspectionTypeValidation = ContainerValidator.validateInspectionType(inspectionType)
    val observationsValidation = ContainerValidator.validateObservations(observations)

    fun performSave(leaveAfterSuccess: Boolean = false) {
        if (!formState.canEdit) return
        exitAfterSave = leaveAfterSuccess
        showExitDialog = false
        formState.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val previousPhotos = formState.lastSavedContent.photos
            val photoSession = formState.photoSessionId
            val result = formState.save(repository)
            if (result?.getOrDefault(false) == true) {
                runCatching { photoStore.clearSelection(photoSession) }
                val savedIds = formState.lastSavedContent.photos.map { it.id }.toSet()
                previousPhotos.filterNot { it.id in savedIds }.forEach { photo ->
                    photoStore.cleanUnreferencedSafely(photo.id, repository)
                }
            }
        }
    }

    BackHandler(enabled = true) {
        if (!formState.canLeave) {
            // Consume Back while SQLite is writing.
        } else if (hasUnsavedChanges) {
            showExitDialog = true
        } else {
            onBack()
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { if (formState.canLeave) showExitDialog = false },
            title = { Text(text = stringResource(id = R.string.dialog_unsaved_changes_title)) },
            text = { Text(text = stringResource(id = R.string.dialog_unsaved_changes_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        performSave(leaveAfterSuccess = true)
                    },
                    enabled = formState.canEdit
                ) {
                    Text(text = stringResource(id = R.string.btn_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            if (formState.canLeave) {
                                showExitDialog = false
                                val captureId = formState.pendingCaptureId
                                val photoSession = formState.photoSessionId
                                val pendingPhotos = formState.discardChanges()
                                formState.viewModelScope.launch {
                                    if (runCatching { photoStore.clearSelection(photoSession) }.isSuccess) {
                                        pendingPhotos.forEach { photo ->
                                            photoStore.cleanUnreferencedSafely(photo.id, repository)
                                        }
                                    }
                                    captureId?.let { runCatching { photoStore.cancelCapture(it) } }
                                }
                                onBack()
                            }
                        },
                        enabled = formState.canLeave
                    ) {
                        Text(text = stringResource(id = R.string.btn_discard))
                    }
                    TextButton(
                        onClick = { if (formState.canLeave) showExitDialog = false },
                        enabled = formState.canLeave
                    ) {
                        Text(text = stringResource(id = R.string.btn_cancel))
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            id = if (loadState is FormLoadState.Success) R.string.title_edit_inspection else R.string.title_new_inspection
                        )
                    )
                },
                actions = {
                    if (onOpenConnection != null) {
                        TextButton(
                            onClick = onOpenConnection,
                            enabled = formState.canEdit && !formState.recoveryPending,
                            modifier = Modifier.testTag("form_connection")
                        ) { Text(stringResource(R.string.connection_title)) }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!formState.canLeave) return@IconButton
                            if (hasUnsavedChanges) {
                                showExitDialog = true
                            } else {
                                onBack()
                            }
                        },
                        enabled = formState.canLeave,
                        modifier = Modifier.testTag("form_back")
                    ) {
                        Text(text = "←")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        when (val state = loadState) {
            FormLoadState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            FormLoadState.NotFound -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(id = R.string.draft_not_found),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onBack) {
                        Text(text = stringResource(id = R.string.btn_back_to_home))
                    }
                }
            }

            is FormLoadState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(id = R.string.draft_load_error),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            formState.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                formState.retryLoad(repository)
                            }
                        }) {
                            Text(text = stringResource(id = R.string.btn_retry))
                        }
                        Button(onClick = onBack) {
                            Text(text = stringResource(id = R.string.btn_back_to_home))
                        }
                    }
                }
            }

            FormLoadState.NewDraft, is FormLoadState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PhotographsSection(formState, photoStore, photoActions) { photo ->
                        formState.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            val forgotten = formState.addPhotos {
                                photoStore.forgetSelectionPhoto(formState.photoSessionId, photo.id)
                                emptyList()
                            }
                            if (forgotten.isSuccess && formState.removePhoto(photo.id) && formState.lastSavedContent.photos.none { it.id == photo.id }) {
                                photoStore.cleanUnreferencedSafely(photo.id, repository)
                            }
                        }
                    }
                    // Banner informativo
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(id = R.string.local_only_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (hasUnsavedChanges) {
                                    Text(
                                        text = "• Cambios sin guardar",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(id = R.string.draft_warning_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Campo 3: Motivo de la inspección
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(id = R.string.label_inspection_type),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            InspectionType.entries.forEach { typeOption ->
                                val isSelected = inspectionType.equals(typeOption.code, ignoreCase = true)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        formState.updateContent(formState.content.copy(inspectionType = typeOption.code))
                                    },
                                    enabled = formState.canEdit,
                                    modifier = Modifier.testTag("form_type_${typeOption.code}"),
                                    label = {
                                        Text(
                                            text = stringResource(id = typeOption.labelRes),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }

                        if (inspectionType.isBlank() && inspectionTypeValidation.errorMessageRes != null) {
                            Text(
                                text = stringResource(id = inspectionTypeValidation.errorMessageRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )
                        }
                    }

                    // Campo 4: Fecha y hora de la inspección
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.label_inspected_at),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = DateTimeUtils.formatForDisplay(inspectedAt),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.inspection_time_automatic),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    if (!formState.canEdit) return@OutlinedButton
                                    DateTimeUtils.showDateTimePicker(
                                        context = context,
                                        currentIsoString = inspectedAt,
                                        onDateTimeSelected = { updatedIso ->
                                            formState.updateContent(formState.content.copy(inspectedAt = updatedIso))
                                        }
                                    )
                                },
                                enabled = formState.canEdit,
                                modifier = Modifier.align(Alignment.End).testTag("form_datetime")
                            ) {
                                Text(text = stringResource(id = R.string.btn_change_date_time))
                            }
                        }
                    }

                    PhotoAnalysisSection(formState)

                    // Botón de guardar borrador
                    Button(
                        onClick = { performSave() },
                        enabled = formState.canEdit,
                        modifier = Modifier
                            .testTag("form_save")
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.height(24.dp).width(24.dp)
                            )
                        } else {
                            Text(
                                text = stringResource(id = R.string.btn_save_draft),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.manual_details_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.manual_details_notice),
                        style = MaterialTheme.typography.bodySmall
                    )

                    // Campo 1: Identificador del contenedor
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = containerIdentifier,
                            onValueChange = {
                                formState.updateContent(formState.content.copy(containerIdentifier = it))
                            },
                            enabled = formState.canEdit,
                            label = { Text(text = stringResource(id = R.string.label_container_id)) },
                            placeholder = { Text(text = stringResource(id = R.string.hint_container_id)) },
                            singleLine = true,
                            isError = containerIdentifier.isNotBlank() && !containerValidation.isValid,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.fillMaxWidth().testTag("form_container")
                        )

                        val normalizedContainer = ContainerValidator.normalizeContainerIdentifier(containerIdentifier)
                        if (containerIdentifier.isNotBlank() && normalizedContainer != containerIdentifier) {
                            Text(
                                text = stringResource(id = R.string.normalized_preview, normalizedContainer),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }

                        if (containerIdentifier.isNotBlank() && containerValidation.errorMessageRes != null) {
                            val errorText = if (containerValidation.errorArgs.isNotEmpty()) {
                                stringResource(id = containerValidation.errorMessageRes, *containerValidation.errorArgs)
                            } else {
                                stringResource(id = containerValidation.errorMessageRes)
                            }
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                    }

                    // Campo 2: Código ISO (Opcional)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = isoCode,
                            onValueChange = { formState.updateContent(formState.content.copy(isoCode = it)) },
                            enabled = formState.canEdit,
                            label = { Text(text = stringResource(id = R.string.label_iso_code)) },
                            placeholder = { Text(text = stringResource(id = R.string.hint_iso_code)) },
                            singleLine = true,
                            isError = !isoCodeValidation.isValid,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.fillMaxWidth().testTag("form_iso")
                        )

                        val normalizedIso = ContainerValidator.normalizeIsoCode(isoCode)
                        if (isoCode.isNotBlank() && normalizedIso != isoCode) {
                            Text(
                                text = stringResource(id = R.string.normalized_preview, normalizedIso),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }

                        if (!isoCodeValidation.isValid && isoCodeValidation.errorMessageRes != null) {
                            Text(
                                text = stringResource(id = isoCodeValidation.errorMessageRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                    }

                    // Campo 5: Observaciones (Opcional)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val currentCodePoints = ContainerValidator.countUnicodeCodePoints(observations)

                        OutlinedTextField(
                            value = observations,
                            onValueChange = { formState.updateContent(formState.content.copy(observations = it)) },
                            enabled = formState.canEdit,
                            label = { Text(text = stringResource(id = R.string.label_observations)) },
                            placeholder = { Text(text = stringResource(id = R.string.hint_observations)) },
                            minLines = 3,
                            maxLines = 6,
                            isError = !observationsValidation.isValid,
                            supportingText = {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (!observationsValidation.isValid && observationsValidation.errorMessageRes != null) {
                                        Text(
                                            text = stringResource(id = observationsValidation.errorMessageRes),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.width(1.dp))
                                    }
                                    Text(
                                        text = "$currentCodePoints / 10000",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (currentCodePoints > 10000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("form_observations")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}
