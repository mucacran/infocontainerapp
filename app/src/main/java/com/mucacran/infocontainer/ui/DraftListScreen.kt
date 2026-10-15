package com.mucacran.infocontainer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mucacran.infocontainer.R
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.data.cleanUnreferencedSafely
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.model.InspectionType
import com.mucacran.infocontainer.util.ContainerValidator
import com.mucacran.infocontainer.util.DateTimeUtils
import kotlinx.coroutines.launch

sealed class DraftListUiState {
    object Loading : DraftListUiState()
    data class Success(val drafts: List<InspectionDraft>) : DraftListUiState()
    data class Error(val message: String) : DraftListUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftListScreen(
    repository: InspectionDraftRepository,
    onSelectDraft: (String) -> Unit,
    onNewInspection: () -> Unit,
    onBack: () -> Unit,
    photoStore: LocalPhotoStore,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Textos precargados para Compose Lint
    val draftDeletedSuccessMsg = stringResource(id = R.string.draft_deleted_success)

    var uiState by remember { mutableStateOf<DraftListUiState>(DraftListUiState.Loading) }
    var draftToDelete by rememberSaveable { mutableStateOf<String?>(null) }

    fun refreshDrafts() {
        uiState = DraftListUiState.Loading
        coroutineScope.launch {
            val result = repository.getAllDrafts()
            result.fold(
                onSuccess = { list ->
                    uiState = DraftListUiState.Success(list)
                },
                onFailure = { ex ->
                    uiState = DraftListUiState.Error(ex.localizedMessage ?: "Error desconocido")
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        refreshDrafts()
    }

    BackHandler(onBack = onBack)

    if (draftToDelete != null) {
        val targetId = draftToDelete!!
        AlertDialog(
            onDismissRequest = { draftToDelete = null },
            title = { Text(text = stringResource(id = R.string.dialog_delete_draft_title)) },
            text = { Text(text = stringResource(id = R.string.dialog_delete_draft_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        val id = targetId
                        val photos = (uiState as? DraftListUiState.Success)?.drafts?.firstOrNull { it.id == id }?.photos.orEmpty()
                        draftToDelete = null
                        coroutineScope.launch {
                            val deleteResult = repository.deleteDraft(id)
                            deleteResult.fold(
                                onSuccess = {
                                    photos.forEach { photo ->
                                        photoStore.cleanUnreferencedSafely(photo.id, repository)
                                    }
                                    refreshDrafts()
                                    snackbarHostState.showSnackbar(draftDeletedSuccessMsg)
                                },
                                onFailure = {
                                    refreshDrafts()
                                }
                            )
                        }
                    }
                ) {
                    Text(text = stringResource(id = R.string.btn_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { draftToDelete = null }
                ) {
                    Text(text = stringResource(id = R.string.btn_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.title_saved_inspections)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text(text = "←")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewInspection) {
                Text(text = "+")
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Header banner
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(id = R.string.drafts_header_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when (val state = uiState) {
                DraftListUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is DraftListUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(id = R.string.drafts_list_load_error),
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
                        Button(onClick = { refreshDrafts() }) {
                            Text(text = stringResource(id = R.string.btn_retry))
                        }
                    }
                }

                is DraftListUiState.Success -> {
                    if (state.drafts.isEmpty()) {
                        // Estado vacío
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(id = R.string.empty_drafts_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(id = R.string.empty_drafts_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = onNewInspection) {
                                Text(text = stringResource(id = R.string.btn_new_inspection))
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                items = state.drafts,
                                key = { it.id }
                            ) { draft ->
                                DraftCard(
                                    draft = draft,
                                    onClick = { onSelectDraft(draft.id) },
                                    onDelete = { draftToDelete = draft.id }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftCard(
    draft: InspectionDraft,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val draftValidation = ContainerValidator.validateDraft(draft)
    val isComplete = draftValidation.isValid

    val inspectionTypeEnum = InspectionType.fromCode(draft.inspectionType)
    val inspectionTypeLabel = if (inspectionTypeEnum != null) {
        stringResource(id = inspectionTypeEnum.labelRes)
    } else {
        stringResource(id = R.string.type_unselected)
    }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Identificador del contenedor
                if (draft.containerIdentifier.isNotBlank()) {
                    Text(
                        text = draft.containerIdentifier,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = stringResource(id = R.string.no_container_identifier),
                        style = MaterialTheme.typography.titleMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // Botón eliminar
                IconButton(onClick = onDelete) {
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Subtítulo con Motivo e ISO Code si está
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Motivo: $inspectionTypeLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (draft.isoCode.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "•  ISO: ${draft.isoCode}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Fecha/Hora
            Text(
                text = DateTimeUtils.formatForDisplay(draft.inspectedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Badge Local
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = stringResource(id = R.string.local_only_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Badge de estado de validación
                Surface(
                    color = if (isComplete) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = stringResource(
                            id = if (isComplete) R.string.draft_status_complete else R.string.draft_status_incomplete
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isComplete) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
