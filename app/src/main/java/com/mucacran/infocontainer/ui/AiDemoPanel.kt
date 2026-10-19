package com.mucacran.infocontainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.mucacran.infocontainer.data.AiIdentificationCoordinator
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.model.DraftPhoto

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AiDemoPanel(
    state: AiDemoState,
    coordinator: AiIdentificationCoordinator? = null,
    username: String = "",
    password: String = "",
    availablePhotos: List<DraftPhoto> = emptyList(),
    photoStore: LocalPhotoStore? = null,
    onApplyIdentification: (String, String) -> Unit = { _, _ -> }
) {
    var showRealConfirmation by remember { mutableStateOf(false) }
    var syntheticConfirmed by remember { mutableStateOf(false) }
    val selected = availablePhotos.firstOrNull { it.id == state.realSelectedPhotoId }
    val validPhoto = selected?.let { photo ->
        photo.hasValidMetadata() && photo.mimeType in setOf("image/png", "image/jpeg") &&
            photo.byteSize in 1..(10L * 1024 * 1024) && photo.width <= 10_000 && photo.height <= 10_000 &&
            photo.width.toLong() * photo.height <= 20_000_000 && photoStore?.exists(photo) == true
    } == true

    HorizontalDivider()
    Text("Identificación fotográfica real", style = MaterialTheme.typography.titleMedium)
    Text("Abre Conexión desde el formulario que contiene la fotografía sintética. Selecciónala aquí antes de iniciar la única prueba con WordPress y OpenAI.",
        style = MaterialTheme.typography.bodySmall)
    if (availablePhotos.isEmpty()) {
        Text("No hay fotografías del formulario. Añade una imagen PNG o JPEG sintética y vuelve a Conexión.")
    } else if (photoStore != null) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(availablePhotos, key = { _, photo -> photo.id }) { index, photo ->
                OutlinedCard(Modifier.widthIn(min = 128.dp)) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        PhotoPreview(photo, photoStore, 200, Modifier.size(100.dp))
                        Text("Foto ${index + 1} · ${photo.width}×${photo.height}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = { state.selectRealPhoto(photo.id) },
                            enabled = !state.realBusy && !state.realAttemptStarted,
                            modifier = Modifier.testTag("ai_real_photo_${index + 1}")
                        ) { Text(if (state.realSelectedPhotoId == photo.id) "Seleccionada" else "Seleccionar") }
                    }
                }
            }
        }
    }
    if (selected != null) {
        Text("Foto seleccionada: ${selected.mimeType}, ${selected.byteSize} bytes. " +
            if (validPhoto) "Lista para revisar antes del envío." else "Debe ser PNG/JPEG disponible, hasta 10 MiB y 20 MP.",
            style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = { syntheticConfirmed = false; showRealConfirmation = true },
        enabled = coordinator != null && validPhoto && username.isNotBlank() && password.isNotBlank() &&
            !state.realBusy && !state.realAttemptStarted,
        modifier = Modifier.fillMaxWidth().testTag("ai_real_identify")
    ) { Text("Identificar con OpenAI (real)") }
    if (showRealConfirmation && selected != null) {
        AlertDialog(
            onDismissRequest = { showRealConfirmation = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("ai_real_confirm_dialog"),
            title = { Text("Iniciar identificación real") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Se enviará esta fotografía por HTTPS a WordPress. El servidor puede realizar una solicitud a OpenAI. Esta prueba permite un solo envío; ante un resultado incierto no se repetirá automáticamente.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = syntheticConfirmed, onCheckedChange = { syntheticConfirmed = it },
                            modifier = Modifier.testTag("ai_real_synthetic_confirmation"))
                        Text("Confirmo que la fotografía es sintética y no contiene datos de clientes.")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showRealConfirmation = false; state.identifyReal(username, password, selected) },
                    enabled = syntheticConfirmed && validPhoto && !state.realBusy && !state.realAttemptStarted,
                    modifier = Modifier.testTag("ai_real_confirm")
                ) { Text("Enviar una vez") }
            },
            dismissButton = { TextButton(onClick = { showRealConfirmation = false }) { Text("Cancelar") } }
        )
    }
    if (state.realAttemptStarted) {
        Text("Intento reservado: ${state.realRequestId ?: "registro no legible"}. " +
            "La aplicación no enviará una segunda identificación durante esta verificación.",
            style = MaterialTheme.typography.bodySmall)
    }
    if (state.realKnownRunId != null) {
        OutlinedButton(
            onClick = { state.checkExisting(username, password) },
            enabled = !state.realBusy && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("ai_real_check_existing")
        ) { Text("Consultar ejecución existente ${state.realKnownRunId}") }
    }
    Text(state.realStatus, Modifier.fillMaxWidth().testTag("ai_real_identification_status")
        .semantics { liveRegion = LiveRegionMode.Polite })
    state.realRun?.let { result ->
        Text("Ejecución ${result.run.id} · estado ${result.run.status}")
        result.proposal?.let { proposal ->
            Column(Modifier.fillMaxWidth().testTag("ai_real_proposal"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Identificador visible: ${proposal.visibleIdentifierText ?: "No legible"}")
                Text("Código ISO propuesto: ${proposal.isoCode ?: "No detectado"}")
                Text("Legibilidad: ${proposal.legibility}")
                Text("Candidatos: ${if (proposal.candidates.isEmpty()) "Ninguno" else proposal.candidates.joinToString { "${it.value} (${if (it.iso6346Valid) "ISO 6346 válido" else "ISO 6346 no válido"})" }}")
                proposal.warnings.forEach { Text("Advertencia: $it") }
                Text("La propuesta requiere revisión humana. No se ha aplicado al formulario ni creado un contenedor.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    HorizontalDivider()
    Text("Demostración de inteligencia artificial", style = MaterialTheme.typography.titleMedium)
    Text("SIMULADO · usa respuestas del contrato y no contacta Hostinger ni OpenAI.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = state::runIdentification, modifier = Modifier.testTag("ai_demo_identification")) { Text("Identificación") }
        Button(onClick = state::runAnalysis, modifier = Modifier.testTag("ai_demo_analysis")) { Text("Análisis") }
    }
    state.identification?.let { proposal ->
        Text("Identificador propuesto: ${proposal.visibleIdentifierText ?: "Sin lectura"}")
        Text("ISO: ${proposal.isoCode ?: "Sin código"} · Legibilidad: ${proposal.legibility}")
        Text("Candidatos: ${proposal.candidates.joinToString { "${it.value} (${if (it.iso6346Valid) "ISO válido" else "ISO no válido"})" }}")
        proposal.warnings.forEach { Text("Advertencia: $it") }
        OutlinedButton(onClick = {
            val value = proposal.visibleIdentifierText ?: proposal.candidates.firstOrNull()?.value
            if (value != null) onApplyIdentification(value, proposal.isoCode.orEmpty())
        }, enabled = proposal.visibleIdentifierText != null || proposal.candidates.isNotEmpty(),
            modifier = Modifier.testTag("ai_demo_apply_identification")) { Text("Aplicar al formulario (acción explícita)") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { state.review("accepted") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_accept_identification")) { Text("Confirmar") }
            OutlinedButton(onClick = { state.review("edited") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_edit_identification")) { Text("Corregir") }
            OutlinedButton(onClick = { state.review("rejected") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_reject_identification")) { Text("Rechazar") }
        }
    }
    state.analysis?.let { proposal ->
        Text("Resumen: ${proposal.summary}")
        Text("Daños visibles: ${proposal.visibleDamage.joinToString { it.text }}")
        Text("Lecturas: ${proposal.visibleReadings.joinToString { "${it.kind}: ${it.value ?: "sin valor"}" }}")
        Text("Zonas documentadas: ${proposal.documentedZones.joinToString()}")
        Text("Zonas no evaluables: ${proposal.unevaluableZones.joinToString()}")
        Text("Informe: ${proposal.reportDraft}")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { state.review("accepted") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_accept_analysis")) { Text("Aceptar") }
            OutlinedButton(onClick = { state.review("edited") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_edit_analysis")) { Text("Editar") }
            OutlinedButton(onClick = { state.review("rejected") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_reject_analysis")) { Text("Rechazar") }
        }
    }
    Text(state.status, modifier = Modifier.fillMaxWidth().testTag("ai_demo_status"))
}
