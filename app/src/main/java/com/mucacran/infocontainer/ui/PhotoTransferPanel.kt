package com.mucacran.infocontainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.network.WordPressPhotoApi

@Composable
fun PhotoTransferPanel(state: PhotoTransferState, connection: ConnectionState, store: LocalPhotoStore) {
    HorizontalDivider()
    Text("Prueba de fotografías con WordPress", style = MaterialTheme.typography.titleMedium)
    if (!state.isOpen) {
        Text("Prepara una sesión con un borrador guardado y una inspección remota de prueba. No se envía nada al abrirla.")
        OutlinedButton(onClick = state::open, enabled = !connection.isChecking, modifier = Modifier.testTag("transfer_open")) {
            Text("Preparar prueba de fotografía")
        }
        return
    }
    val enabled = !state.isBusy && !connection.isChecking
    val credentials = connection.username.isNotBlank() && connection.password.isNotBlank()
    var showDrafts by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showCreateConfirmation by remember { mutableStateOf(false) }
    Text("Selecciona una fotografía ya guardada. Los cambios pendientes del formulario permanecen en el teléfono.")
    if (state.isDevelopmentBuild) {
        Text("La creación sólo está disponible en desarrollo y nunca sube fotografías.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { if (!connection.isChecking) showCreateConfirmation = true },
            enabled = enabled && credentials && state.testAssociation == null && !state.testCreationNeedsReview,
            modifier = Modifier.fillMaxWidth().testTag("transfer_create_test")
        ) { Text(if (state.testAssociation == null) "Crear inspección de prueba" else "Inspección de prueba conservada") }
        state.testAssociation?.let { Text("Prueba conservada: contenedor ${it.containerIdentifier}, inspección ${it.inspectionId} (draft)", style = MaterialTheme.typography.bodySmall) }
        if (state.testCreationNeedsReview) Text("La creación anterior pudo haber sido procesada. Consulta el estado remoto antes de permitir otra creación; puedes introducir manualmente un ID existente.", style = MaterialTheme.typography.bodySmall)
    }
    if (showCreateConfirmation) {
        AlertDialog(
            onDismissRequest = { showCreateConfirmation = false },
            title = { Text("Crear registros de prueba") },
            text = { Text("Se creará un contenedor sintético y una inspección draft en Hostinger usando las credenciales en memoria. No se modificará ningún borrador ni se subirá ninguna fotografía.") },
            confirmButton = { TextButton(onClick = { showCreateConfirmation = false; state.createTestInspection(connection.username, connection.password) }) { Text("Crear") } },
            dismissButton = { TextButton(onClick = { showCreateConfirmation = false }) { Text("Cancelar") } }
        )
    }
    Box {
        OutlinedButton(onClick = { showDrafts = true }, enabled = enabled && state.drafts.isNotEmpty(), modifier = Modifier.testTag("transfer_draft")) {
            Text(state.selectedDraft?.let { "Borrador: ${it.containerIdentifier.ifBlank { "Sin identificador" }} · ${it.id.take(8)}" } ?: "Seleccionar borrador guardado")
        }
        DropdownMenu(expanded = showDrafts, onDismissRequest = { showDrafts = false }, modifier = Modifier.heightIn(max = 280.dp)) {
            state.drafts.forEach { draft ->
                DropdownMenuItem(
                    text = { Text("${draft.containerIdentifier.ifBlank { "Sin identificador" }} · ${draft.photos.size} fotos · ${draft.id.take(8)}") },
                    onClick = { showDrafts = false; state.selectDraft(draft.id) }
                )
            }
        }
    }
    state.selectedDraft?.let { draft ->
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(draft.photos, key = { _, photo -> photo.id }) { index, photo ->
                OutlinedCard {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        PhotoPreview(photo, store, 200, Modifier.size(100.dp))
                        Text("Foto ${index + 1} · ${photo.width}×${photo.height}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { state.selectPhoto(photo.id) }, enabled = enabled,
                            modifier = Modifier.testTag("transfer_photo_${index + 1}")) {
                            Text(if (state.selectedPhotoId == photo.id) "Seleccionada" else "Seleccionar")
                        }
                    }
                }
            }
        }
    }
    OutlinedTextField(
        value = state.inspectionInput, onValueChange = state::updateInspection,
        label = { Text("ID de inspección remota de prueba (draft)") },
        supportingText = { Text(if (state.testAssociation == null) "Puedes introducir un ID existente o crear una prueba desde el botón anterior." else "ID creado y conservado localmente; no se volverá a crear al reabrir la pantalla.") },
        enabled = enabled && state.binding == null, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("transfer_inspection")
    )
    if (state.binding != null) Text("Destino asociado: inspección ${state.inspectionId}. El destino y el UUID se conservan para reintentos.")
    Box {
        OutlinedButton(onClick = { showCategories = true }, enabled = enabled && !state.isPrepared) {
            Text("Categoría: ${categoryLabel(state.category)}")
        }
        DropdownMenu(expanded = showCategories, onDismissRequest = { showCategories = false }, modifier = Modifier.heightIn(max = 280.dp)) {
            WordPressPhotoApi.CATEGORIES.forEach { category ->
                DropdownMenuItem(text = { Text(categoryLabel(category)) }, onClick = {
                    showCategories = false; state.updateCategory(category)
                })
            }
        }
    }
    OutlinedTextField(
        value = state.description, onValueChange = state::updateDescription,
        label = { Text("Descripción opcional") },
        supportingText = { Text("${state.description.codePointCount(0, state.description.length)}/2000") },
        enabled = enabled && !state.isPrepared, modifier = Modifier.fillMaxWidth()
    )
    state.selectedRecord?.let { record ->
        Text("UUID estable: ${record.clientPhotoId}\nEstado local: ${if (record.remotePhotoId != null) "confirmada, foto ${record.remotePhotoId}" else "preparada o pendiente de confirmación"}", style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(state.confirmedTestInspection, state::confirmTestInspection, enabled = enabled)
        Text("Confirmo que el ID corresponde a una inspección de prueba aislada en estado draft.", modifier = Modifier.weight(1f))
    }
    Button(
        onClick = { if (!connection.isChecking) state.upload(connection.username, connection.password) },
        enabled = enabled && credentials && state.canUpload,
        modifier = Modifier.fillMaxWidth().testTag("transfer_upload")
    ) { Text(if (state.isPrepared) "Reintentar mismo envío (mismo UUID)" else "Subir fotografía seleccionada") }
    OutlinedButton(
        onClick = { if (!connection.isChecking) state.listRemote(connection.username, connection.password) },
        enabled = enabled && credentials && state.inspectionId != null,
        modifier = Modifier.fillMaxWidth().testTag("transfer_list")
    ) { Text("Consultar fotografías remotas") }
    state.remotePhotos.take(25).forEach { photo ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Foto ${photo.id} · ${categoryLabel(photo.category)} · ${photo.byteSize} bytes")
                Text("Inspección ${photo.inspectionId} · UUID ${photo.clientPhotoId}", style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = { if (!connection.isChecking) state.verifyRemote(photo, connection.username, connection.password) },
                    enabled = enabled && credentials
                ) { Text("Recuperar bytes y verificar integridad") }
            }
        }
    }
    if (state.remotePhotos.size > 25) Text("Se muestran las primeras 25 fotografías de la respuesta.")
    state.message?.let {
        Text(it, Modifier.testTag("transfer_status").semantics { liveRegion = LiveRegionMode.Polite })
    }
    Text("Límites: JPEG, PNG o WebP; hasta 10 MiB, 10.000 píxeles por lado y 20 MP. Los originales se envían sin convertirlos. La recuperación verifica los bytes sin sustituir fotos locales.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = state::close, modifier = Modifier.testTag("transfer_close")) {
        Text(if (state.isBusy) "Cancelar y cerrar prueba de fotografías" else "Cerrar prueba de fotografías")
    }
}

private fun categoryLabel(category: String): String = when (category) {
    "identifier" -> "Identificador"
    "technical_plate" -> "Placa técnica"
    "doors" -> "Puertas"
    "front" -> "Frente"
    "left_side" -> "Lateral izquierdo"
    "right_side" -> "Lateral derecho"
    "interior" -> "Interior"
    "floor" -> "Piso"
    "damage" -> "Daño"
    else -> "Otra"
}
