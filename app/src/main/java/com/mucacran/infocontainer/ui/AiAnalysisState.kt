package com.mucacran.infocontainer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mucacran.infocontainer.network.AiApi
import com.mucacran.infocontainer.network.AiExecutionGate
import com.mucacran.infocontainer.network.AnalysisRun
import com.mucacran.infocontainer.data.AiAnalysisCoordinator
import com.mucacran.infocontainer.network.RemotePhoto
import com.mucacran.infocontainer.network.AiReviewResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class AiAnalysisState(
    private val api: AiApi,
    private val gate: AiExecutionGate,
    private val reviewTimeoutMillis: Long = 15_000
) : ViewModel() {
    private var coordinator: AiAnalysisCoordinator? = null
    var remotePhotos by mutableStateOf<List<RemotePhoto>>(emptyList()); private set
    var realReviewStatus by mutableStateOf<String?>(null); private set
    // This ViewModel survives Activity recreation; preserve the coordinator's idempotency keys too.
    fun attachCoordinator(value: AiAnalysisCoordinator) { if (coordinator == null) coordinator = value }
    var inspectionId by mutableStateOf<Long?>(null); private set
    var selectedPhotoIds by mutableStateOf<List<Long>>(emptyList()); private set
    var run by mutableStateOf<AnalysisRun?>(null); private set
    var editedReport by mutableStateOf(""); private set
    var isBusy by mutableStateOf(false); private set
    var lastReview by mutableStateOf<AiReviewResponse?>(null); private set
    var status by mutableStateOf("Análisis sin ejecutar"); private set
    fun selectInspection(id: Long) { if (id > 0 && !isBusy && id != inspectionId) { inspectionId = id; selectedPhotoIds = emptyList(); run = null; editedReport = ""; lastReview = null; realReviewStatus = null } }
    fun updateEditedReport(value: String) {
        if (!isBusy && value.codePointCount(0, value.length) <= 12_000) editedReport = value
    }
    private fun receiveRun(value: AnalysisRun) {
        run = value
        editedReport = value.proposal.reportDraft
        lastReview = null
        realReviewStatus = null
    }
    fun selectPhotos(ids: List<Long>, sizes: Map<Long, Long>) { val unique = ids.distinct(); if (unique.size <= 12 && unique.all { it > 0 } && unique.sumOf { sizes[it] ?: Long.MAX_VALUE } <= 30L * 1024 * 1024) selectedPhotoIds = unique }
    fun analyze(username: String, password: String, clientRequestId: String) {
        val id = inspectionId ?: return
        if (!gate.authorized || clientRequestId.isBlank() || selectedPhotoIds.isEmpty()) { status = "Análisis bloqueado: autorización, UUID o selección incompleta."; return }
        status = "Procesando…"
        viewModelScope.launch { try { receiveRun(api.analyze(username, password, id, clientRequestId, selectedPhotoIds)); status = "Propuesta recibida; requiere revisión humana." } catch (_: Exception) { status = "Resultado fallido o incierto; conserva el mismo UUID." } }
    }
    fun loadRemotePhotos(username: String, password: String) {
        val id = inspectionId ?: return
        val c = coordinator ?: return
        status = "Consultando fotografías remotas…"
        viewModelScope.launch { try { remotePhotos = c.listPhotos(username, password, id); status = "${remotePhotos.size} fotografías remotas consultadas." } catch (_: Exception) { status = "Consulta bloqueada o fallida; no se sustituyeron datos locales." } }
    }
    fun analyzeThroughCoordinator(username: String, password: String) {
        val id = inspectionId ?: return; val c = coordinator ?: return
        if (selectedPhotoIds.isEmpty()) { status = "Selecciona al menos una fotografía remota."; return }
        status = "Procesando análisis…"
        viewModelScope.launch { try { receiveRun(c.analyze(username, password, id, selectedPhotoIds)); status = "Propuesta recibida; requiere revisión humana." } catch (_: Exception) { status = "Análisis bloqueado o incierto; conserva el mismo UUID." } }
    }
    fun runSimulated() {
        if (isBusy) return
        val c = coordinator
        if (c?.isSimulated != true) { status = "La sesión simulada no está disponible. La ejecución real permanece bloqueada."; return }
        isBusy = true
        inspectionId = 17
        selectedPhotoIds = listOf(12, 15)
        status = "Consultando fotografías simuladas…"
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                remotePhotos = c.listPhotos("simulated", "simulated", 17)
                status = "Fotografías simuladas consultadas; procesando análisis…"
                receiveRun(c.analyze("simulated", "simulated", 17, selectedPhotoIds))
                status = "SIMULADO · propuesta recibida; requiere revisión humana."
            } catch (cancelled: CancellationException) {
                status = "SIMULADO · análisis interrumpido."
                throw cancelled
            } catch (_: Exception) { status = "SIMULADO · fallo inesperado del transporte falso." }
            finally { isBusy = false }
        }
    }
    /** Retain every structured field; only the operator's report text changes. */
    fun editedReviewContent(): Map<String, Any>? = run?.proposal?.let { p ->
        mapOf(
            "photo_observations" to p.photoObservations.map { mapOf("text" to it.text, "photo_ids" to it.photoIds) },
            "visible_readings" to p.visibleReadings.map { mapOf("kind" to it.kind, "value" to it.value, "photo_ids" to it.photoIds) },
            "visible_damage" to p.visibleDamage.map { mapOf("text" to it.text, "photo_ids" to it.photoIds) },
            "needs_review" to p.needsReview.map { mapOf("text" to it.text, "photo_ids" to it.photoIds) },
            "documented_zones" to p.documentedZones,
            "unevaluable_zones" to p.unevaluableZones,
            "analyzed_photo_ids" to p.analyzedPhotoIds,
            "excluded_photo_ids" to p.excludedPhotoIds,
            "summary" to p.summary,
            "report_draft" to editedReport
        )
    }
    fun reviewThroughCoordinator(username: String, password: String, action: String, content: Any?, resolution: String? = null) {
        if (isBusy) return
        val c = coordinator ?: run { status = "No hay coordinador de análisis disponible."; return }
        val current = run ?: run { status = "No hay una propuesta para revisar."; return }
        if (current.run.status != "completed" || current.run.stale) {
            status = "La propuesta no está completada o está obsoleta; no se registró la revisión."
            return
        }
        isBusy = true
        realReviewStatus = null
        status = "Registrando revisión…"
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val received = withTimeout(reviewTimeoutMillis) { c.review(username, password, current.run.id, action, content, resolution) }
                check(received.review.runId == current.run.id && received.review.action == action)
                lastReview = received
                realReviewStatus = "Revisión registrada; la inspección original no se modificó automáticamente."
                val decision = when (action) { "accepted" -> "aceptación"; "edited" -> "edición"; "rejected" -> "rechazo"; else -> action }
                status = (if (c.isSimulated) "SIMULADO · " else "") + "Revisión de $decision confirmada."
            } catch (_: TimeoutCancellationException) {
                status = "Tiempo de espera agotado al registrar la revisión; conserva el mismo UUID para reintentar."
                realReviewStatus = status
            } catch (cancelled: CancellationException) {
                status = "Revisión interrumpida; conserva el mismo UUID."
                throw cancelled
            } catch (_: Exception) {
                realReviewStatus = "Revisión bloqueada o incierta; conserva el mismo client_review_id."
                status = "Revisión fallida o incierta; conserva el mismo UUID."
            } finally { isBusy = false }
        }
    }
}
