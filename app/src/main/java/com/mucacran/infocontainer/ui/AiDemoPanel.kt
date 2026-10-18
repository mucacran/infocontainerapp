package com.mucacran.infocontainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mucacran.infocontainer.data.AiIdentificationCoordinator
import com.mucacran.infocontainer.model.DraftPhoto

@Composable
fun AiDemoPanel(state: AiDemoState, coordinator: AiIdentificationCoordinator? = null, username: String = "", password: String = "", selectedPhoto: DraftPhoto? = null, onApplyIdentification: (String, String) -> Unit = { _, _ -> }) {
    Text("Demostración de inteligencia artificial", style = MaterialTheme.typography.titleMedium)
    Text("SIMULADO · usa respuestas del contrato y no contacta Hostinger ni OpenAI.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = state::runIdentification, modifier = Modifier.testTag("ai_demo_identification")) { Text("Identificación") }
        Button(onClick = state::runAnalysis, modifier = Modifier.testTag("ai_demo_analysis")) { Text("Análisis") }
    }
    OutlinedButton(onClick = { if (coordinator != null && selectedPhoto != null) state.identifyReal(coordinator, username, password, selectedPhoto) }, enabled = false, modifier = Modifier.testTag("ai_real_identify")) { Text("Identificación real bloqueada") }
    Text("La ejecución real está bloqueada por seguridad hasta autorización explícita.", style = MaterialTheme.typography.bodySmall)
    state.identification?.let { p ->
        Text("Identificador propuesto: ${p.visibleIdentifierText ?: "Sin lectura"}")
        Text("ISO: ${p.isoCode ?: "Sin código"} · Legibilidad: ${p.legibility}")
        Text("Candidatos: ${p.candidates.joinToString { "${it.value} (${if (it.iso6346Valid) "ISO válido" else "ISO no válido"})" }}")
        p.warnings.forEach { Text("Advertencia: $it") }
        OutlinedButton(onClick = { val value = p.visibleIdentifierText ?: p.candidates.firstOrNull()?.value; if (value != null) onApplyIdentification(value, p.isoCode.orEmpty()) }, enabled = p.visibleIdentifierText != null || p.candidates.isNotEmpty(), modifier = Modifier.testTag("ai_demo_apply_identification")) { Text("Aplicar al formulario (acción explícita)") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { state.review("accepted") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_accept_identification")) { Text("Confirmar") }
            OutlinedButton(onClick = { state.review("edited") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_edit_identification")) { Text("Corregir") }
            OutlinedButton(onClick = { state.review("rejected") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_reject_identification")) { Text("Rechazar") }
        }
    }
    state.analysis?.let { p ->
        Text("Resumen: ${p.summary}")
        Text("Daños visibles: ${p.visibleDamage.joinToString { it.text }}")
        Text("Lecturas: ${p.visibleReadings.joinToString { "${it.kind}: ${it.value ?: "sin valor"}" }}")
        Text("Zonas documentadas: ${p.documentedZones.joinToString()}")
        Text("Zonas no evaluables: ${p.unevaluableZones.joinToString()}")
        Text("Informe: ${p.reportDraft}")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { state.review("accepted") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_accept_analysis")) { Text("Aceptar") }
            OutlinedButton(onClick = { state.review("edited") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_edit_analysis")) { Text("Editar") }
            OutlinedButton(onClick = { state.review("rejected") }, modifier = Modifier.fillMaxWidth().testTag("ai_demo_reject_analysis")) { Text("Rechazar") }
        }
    }
    Text(state.status, modifier = Modifier.fillMaxWidth().testTag("ai_demo_status"))
}
