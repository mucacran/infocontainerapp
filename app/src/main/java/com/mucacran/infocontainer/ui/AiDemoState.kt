package com.mucacran.infocontainer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mucacran.infocontainer.network.AnalysisProposal
import com.mucacran.infocontainer.network.IdentificationCandidate
import com.mucacran.infocontainer.network.IdentificationProposal
import com.mucacran.infocontainer.data.AiIdentificationCoordinator
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.network.IdentificationRun
import kotlinx.coroutines.launch

class AiDemoState : ViewModel() {
    var realRun by mutableStateOf<IdentificationRun?>(null); private set
    var realBusy by mutableStateOf(false); private set
    var realReviewStatus by mutableStateOf<String?>(null); private set
    var identification by mutableStateOf<IdentificationProposal?>(null); private set
    var analysis by mutableStateOf<AnalysisProposal?>(null); private set
    var status by mutableStateOf("Modo SIMULADO: no se realizan solicitudes de red."); private set
    var reviewed by mutableStateOf<String?>(null); private set
    var editedReport by mutableStateOf(""); private set
    fun runIdentification() { identification = IdentificationProposal("TSTU4855933", listOf(IdentificationCandidate("TSTU4855933", true), IdentificationCandidate("TSTU4855938", false)), "22G1", "uncertain", listOf("La placa está parcialmente oculta.")); reviewed = null; status = "SIMULADO · propuesta de identificación lista para revisión humana." }
    fun runAnalysis() { analysis = AnalysisProposal(emptyList(), listOf(com.mucacran.infocontainer.network.VisibleReading("identifier", "TSTU4855933", listOf(12))), listOf(com.mucacran.infocontainer.network.AnalysisItem("Abolladura visible en la puerta derecha.", listOf(15))), listOf(com.mucacran.infocontainer.network.AnalysisItem("La iluminación no permite confirmar el sello.", listOf(15))), listOf("puerta derecha"), listOf("techo"), listOf(12,15), emptyList(), "Se observan daños visibles en la puerta derecha.", "Borrador sujeto a revisión humana.", null); editedReport = analysis?.reportDraft.orEmpty(); reviewed = null; status = "SIMULADO · análisis listo para revisión humana." }
    fun updateEditedReport(value: String) { if (value.length <= 12000) editedReport = value }
    fun review(action: String) { reviewed = action; status = "SIMULADO · revisión $action registrada localmente; no se modificaron datos del servidor." }
    fun clear() { identification = null; analysis = null; reviewed = null; status = "Modo SIMULADO: no se realizan solicitudes de red." }
    fun identifyReal(coordinator: AiIdentificationCoordinator, username: String, password: String, photo: DraftPhoto) {
        if (realBusy) return
        realBusy = true; status = "REAL · procesando identificación…"
        viewModelScope.launch {
            try { realRun = coordinator.identify(username, password, photo); status = "REAL · propuesta recibida; requiere revisión humana." }
            catch (_: IllegalStateException) { status = "REAL · ejecución bloqueada: autorización no habilitada." }
            catch (_: Exception) { status = "REAL · identificación fallida o incierta; conserva el mismo UUID para consultar o reintentar." }
            finally { realBusy = false }
        }
    }
    fun reviewReal(coordinator: AiIdentificationCoordinator, username: String, password: String, runId: Long, action: String, content: Any? = null, resolution: String? = null) {
        if (realBusy) return
        realBusy = true; realReviewStatus = "REAL · registrando revisión…"
        viewModelScope.launch {
            try { coordinator.review(username, password, runId, action, content, resolution); realReviewStatus = "REAL · revisión registrada; no se guardaron cambios automáticos en la inspección." }
            catch (_: Exception) { realReviewStatus = "REAL · revisión fallida o incierta; conserva el mismo client_review_id." }
            finally { realBusy = false }
        }
    }
}
