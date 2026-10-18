package com.mucacran.infocontainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun AiAnalysisPanel(analysisState: AiAnalysisState, username: String = "", password: String = "") {
    Text("Análisis de inspección remota")
    Text("SIMULADO · la respuesta se presenta para revisión; no se contacta Hostinger ni OpenAI.")
    OutlinedTextField(value = analysisState.inspectionId?.toString().orEmpty(), onValueChange = { it.toLongOrNull()?.let(analysisState::selectInspection) }, label = { Text("ID de inspección draft") }, enabled = !analysisState.isBusy, modifier = Modifier.fillMaxWidth().testTag("ai_analysis_inspection"))
    Button(onClick = { analysisState.runSimulated() }, enabled = !analysisState.isBusy, modifier = Modifier.fillMaxWidth().testTag("ai_analysis_simulate")) { Text("Ejecutar flujo simulado") }
    OutlinedButton(onClick = { analysisState.loadRemotePhotos(username, password) }, enabled = false, modifier = Modifier.testTag("ai_analysis_remote_photos")) { Text("Consultar fotografías REST (bloqueado)") }
    OutlinedButton(onClick = { analysisState.analyzeThroughCoordinator(username, password) }, enabled = false, modifier = Modifier.testTag("ai_analysis_remote_run")) { Text("Solicitar análisis REST (bloqueado)") }
    analysisState.run?.proposal?.let { p ->
        Text("SIMULADO · propuesta generada por IA · requiere revisión humana", modifier = Modifier.testTag("ai_analysis_proposal"))
        Text("Observaciones: ${p.photoObservations.joinToString { it.text.ifBlank { "Sin observación" } }}", Modifier.testTag("ai_analysis_observations"))
        Text("Lecturas: ${p.visibleReadings.joinToString { "${it.kind}: ${it.value ?: "sin valor"}" }}", Modifier.testTag("ai_analysis_readings"))
        Text("Daños: ${p.visibleDamage.joinToString { it.text }}", Modifier.testTag("ai_analysis_damage"))
        Text("Requieren revisión: ${p.needsReview.joinToString { it.text }}", Modifier.testTag("ai_analysis_needs_review"))
        Text("Zonas documentadas: ${p.documentedZones.joinToString()}", Modifier.testTag("ai_analysis_zones"))
        Text("Zonas no evaluables: ${p.unevaluableZones.joinToString()}", Modifier.testTag("ai_analysis_unevaluable"))
        Text("Resumen: ${p.summary}", Modifier.testTag("ai_analysis_summary"))
        OutlinedTextField(value = analysisState.editedReport, onValueChange = analysisState::updateEditedReport, label = { Text("Borrador de informe editable") }, enabled = !analysisState.isBusy, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth().testTag("ai_analysis_report"))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { analysisState.reviewThroughCoordinator("simulated", "simulated", "accepted", null) }, enabled = !analysisState.isBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai_analysis_accept")) { Text("Aceptar") }
            OutlinedButton(onClick = { analysisState.reviewThroughCoordinator("simulated", "simulated", "edited", analysisState.editedReviewContent()) }, enabled = !analysisState.isBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai_analysis_edit")) { Text("Registrar edición") }
            OutlinedButton(onClick = { analysisState.reviewThroughCoordinator("simulated", "simulated", "rejected", "La propuesta requiere revisión adicional.") }, enabled = !analysisState.isBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai_analysis_reject")) { Text("Rechazar") }
        }
        Text("La revisión simulada no modifica la inspección original. La ejecución real permanece bloqueada.")
    }
    Text(analysisState.status, Modifier.testTag("ai_analysis_status").semantics { liveRegion = LiveRegionMode.Polite })
}
