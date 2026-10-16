package com.mucacran.infocontainer.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewModelScope
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.data.SelectionImportException
import com.mucacran.infocontainer.data.cleanUnreferencedSafely
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.model.PhotoLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

class PhotoActions(
    val takePhoto: () -> Unit,
    val selectPhotos: () -> Unit,
    val retryCapture: () -> Unit,
    val cancelRequest: () -> Unit,
    val retrySelection: () -> Unit,
    val cancelSelection: () -> Unit,
    val hasPendingSelection: Boolean
)

/** Register launchers outside the loading branch so results survive Activity recreation. */
@Composable
fun rememberPhotoActions(
    state: InspectionFormState,
    store: LocalPhotoStore,
    repository: InspectionDraftRepository
): PhotoActions {

    suspend fun cancelCaptureSafely(id: String): Boolean = try {
        store.cancelCapture(id)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        state.reportPhotoError(error.localizedMessage ?: "No se pudo limpiar la captura temporal. Puede reintentarlo.")
        false
    }

    fun importJournal(uris: List<Uri>, captureId: String? = null) {
        if (!state.canEdit) return
        val session = state.photoSessionId
        state.setPendingPhotoSelection(true)
        state.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var partialError: String? = null
            val result = state.addPhotos {
                try {
                    store.importSelection(session, uris, state.content.photos)
                } catch (partial: SelectionImportException) {
                    partialError = partial.localizedMessage
                    partial.readyPhotos
                }
            }
            state.setPendingPhotoSelection(runCatching { store.hasPendingSelection(session) }.getOrDefault(true))
            partialError?.let { state.reportPhotoError(it) }
            if (result.isSuccess && partialError == null && state.content == state.lastSavedContent) {
                if (runCatching { store.clearSelection(session) }.isSuccess) state.setPendingPhotoSelection(false)
            }
            if (result.isSuccess && partialError == null && captureId != null) {
                state.setPendingCapture(null)
                cancelCaptureSafely(captureId)
            }
        }
    }

    fun acceptCapture(success: Boolean) {
        val id = state.pendingCaptureId ?: return
        state.finishPhotoRequest(clearCapture = false)
        if (success) importJournal(listOf(store.captureUri(id)), id)
        else state.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (cancelCaptureSafely(id)) state.setPendingCapture(null)
        }
    }

    // Wait for the saved form and any external result before recovering its journal.
    // A restored picker request must keep its identity until its callback arrives.
    LaunchedEffect(state.photoSessionId, state.isOpened, state.loadState is FormLoadState.Loading, state.externalPhotoRequest) {
        if (!state.isOpened || state.loadState is FormLoadState.Loading || state.externalPhotoRequest != null) {
            return@LaunchedEffect
        }
        val pendingSelection = runCatching { store.hasPendingSelection(state.photoSessionId) }.getOrElse {
            state.reportPhotoError("No se pudo recuperar la selección pendiente. Vuelve a intentarlo.")
            false
        }
        state.setPendingPhotoSelection(pendingSelection)
        if (pendingSelection && state.canEdit) {
            if (state.canEdit) importJournal(emptyList())
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (state.externalPhotoRequest == "camera") acceptCapture(success)
    }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(PhotoLimits.MAX_PHOTOS)
    ) { uris ->
        if (state.externalPhotoRequest != "picker") return@rememberLauncherForActivityResult
        state.finishPhotoRequest(clearCapture = false)
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (state.content.photos.size + uris.size > PhotoLimits.MAX_PHOTOS) {
            state.reportPhotoError("Puede adjuntar como máximo ${PhotoLimits.MAX_PHOTOS} fotografías. Seleccione menos imágenes.")
        } else {
            importJournal(uris)
        }
    }

    return PhotoActions(
        takePhoto = {
            val previousCapture = state.pendingCaptureId
            if (state.content.photos.size >= PhotoLimits.MAX_PHOTOS) {
                state.reportPhotoError("Puede adjuntar como máximo ${PhotoLimits.MAX_PHOTOS} fotografías por borrador.")
            } else if (state.beginPhotoRequest("camera")) {
                state.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        previousCapture?.let {
                            if (!cancelCaptureSafely(it)) {
                                state.setPendingCapture(it)
                                error("No se pudo retirar la captura pendiente.")
                            }
                        }
                        val id = store.createCapture()
                        state.setPendingCapture(id)
                        camera.launch(store.captureUri(id))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        state.finishPhotoRequest(clearCapture = false)
                        state.reportPhotoError(error.localizedMessage ?: "No se pudo abrir la cámara del teléfono.")
                    }
                }
            }
        },
        selectPhotos = {
            if (state.beginPhotoRequest("picker", state.pendingCaptureId)) {
                try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (error: Exception) {
                    state.finishPhotoRequest(clearCapture = false)
                    state.reportPhotoError(error.localizedMessage ?: "No se pudo abrir el selector de fotografías.")
                }
            }
        },
        retryCapture = { if (state.canEdit && state.pendingCaptureId != null) acceptCapture(true) },
        cancelRequest = {
            if (!state.isImportingPhotos && !state.isSaving) {
                state.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val id = state.pendingCaptureId
                    state.finishPhotoRequest(clearCapture = false)
                    if (id != null && cancelCaptureSafely(id)) state.setPendingCapture(null)
                }
            }
        },
        retrySelection = { if (state.canEdit) importJournal(emptyList()) },
        cancelSelection = {
            if (state.canEdit) state.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { store.clearSelection(state.photoSessionId) }
                    .onSuccess { state.setPendingPhotoSelection(false) }
                    .onFailure { state.reportPhotoError("No se pudo cerrar la selección pendiente. Puede reintentarlo.") }
            }
        },
        hasPendingSelection = state.hasPendingPhotoSelection
    )
}

@Composable
fun PhotographsSection(
    state: InspectionFormState,
    store: LocalPhotoStore,
    actions: PhotoActions,
    onRemovePhoto: (DraftPhoto) -> Unit
) {
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth().testTag("form_photos")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Fotografías (${state.content.photos.size}/${PhotoLimits.MAX_PHOTOS})", style = MaterialTheme.typography.titleLarge)
            Text("Añada fotografías del mismo contenedor. Puede empezar con una y añadir más después.")
            Text("Solo en este teléfono. Estas imágenes aún no se han analizado ni enviado.", style = MaterialTheme.typography.bodySmall)
            Text("JPEG, PNG o WebP · hasta 30 MiB por imagen y 150 MiB por borrador.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = actions.takePhoto, enabled = state.canEdit, modifier = Modifier.testTag("photo_camera")) {
                Text("Tomar foto")
            }
            OutlinedButton(onClick = actions.selectPhotos, enabled = state.canEdit, modifier = Modifier.testTag("photo_picker")) {
                Text("Seleccionar fotografías")
            }
            if (state.isImportingPhotos) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("Copiando fotografías al teléfono…")
                }
            }
            if (state.externalPhotoRequest != null && !state.isImportingPhotos) {
                TextButton(onClick = actions.cancelRequest) { Text("Cancelar adjuntar fotografías") }
            }
            if (state.pendingCaptureId != null && state.canEdit) {
                Text("Hay una captura pendiente. Puede reintentar adjuntarla si falló la copia.")
                TextButton(onClick = actions.retryCapture) { Text("Reintentar captura pendiente") }
                TextButton(onClick = actions.cancelRequest) { Text("Quitar captura pendiente") }
            }
            if (actions.hasPendingSelection && state.canEdit && state.photoErrorMessage != null) {
                TextButton(onClick = actions.retrySelection) { Text("Reintentar selección pendiente") }
                TextButton(onClick = actions.cancelSelection) { Text("Cerrar selección pendiente") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.content.photos, key = { it.id }) { photo ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PhotoPreview(photo, store, 320, Modifier.size(112.dp).clickable(enabled = state.canEdit) { expandedId = photo.id })
                        TextButton(
                            onClick = { onRemovePhoto(photo) },
                            enabled = state.canEdit,
                            modifier = Modifier.testTag("photo_remove_${photo.id}")
                        ) { Text("Quitar") }
                    }
                }
            }
        }
    }
    val expanded = state.content.photos.firstOrNull { it.id == expandedId }
    if (expanded != null) {
        Dialog(onDismissRequest = { expandedId = null }) {
            Card {
                Column(Modifier.padding(12.dp)) {
                    PhotoPreview(expanded, store, 1600, Modifier.fillMaxWidth().height(420.dp))
                    TextButton(onClick = { expandedId = null }) { Text("Cerrar fotografía") }
                }
            }
        }
    }
}

@Composable
private fun PhotoPreview(photo: DraftPhoto, store: LocalPhotoStore, maxEdge: Int, modifier: Modifier) {
    var failed by rememberSaveable(photo.id, maxEdge) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, photo.id, maxEdge) {
        try {
            value = store.preview(photo, maxEdge)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it.asImageBitmap(), "Fotografía del contenedor", Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        } ?: if (failed) Text("Fotografía no disponible", style = MaterialTheme.typography.bodySmall)
        else CircularProgressIndicator(Modifier.size(24.dp))
    }
}
