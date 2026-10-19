package com.mucacran.infocontainer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mucacran.infocontainer.data.AiIdentificationCoordinator
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.network.AiHttpException
import com.mucacran.infocontainer.network.AiInvalidException
import com.mucacran.infocontainer.network.AnalysisProposal
import com.mucacran.infocontainer.network.IdentificationCandidate
import com.mucacran.infocontainer.network.IdentificationProposal
import com.mucacran.infocontainer.network.IdentificationRun
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The demonstration and the one permitted real identification have separate, observable state. */
class AiDemoState(private val realTimeoutMillis: Long = 130_000) : ViewModel() {
    private var realCoordinator: AiIdentificationCoordinator? = null

    var realRun by mutableStateOf<IdentificationRun?>(null)
        private set
    var realBusy by mutableStateOf(false)
        private set
    var realStatus by mutableStateOf("REAL · selecciona una fotografía sintética desde el formulario.")
        private set
    var realReviewStatus by mutableStateOf<String?>(null)
        private set
    var realAttemptStarted by mutableStateOf(false)
        private set
    var realKnownRunId by mutableStateOf<Long?>(null)
        private set
    var realRequestId by mutableStateOf<String?>(null)
        private set
    var realSelectedPhotoId by mutableStateOf<String?>(null)
        private set

    var identification by mutableStateOf<IdentificationProposal?>(null)
        private set
    var analysis by mutableStateOf<AnalysisProposal?>(null)
        private set
    var status by mutableStateOf("Modo SIMULADO: no se realizan solicitudes de red.")
        private set
    var reviewed by mutableStateOf<String?>(null)
        private set
    var editedReport by mutableStateOf("")
        private set

    fun attachRealCoordinator(coordinator: AiIdentificationCoordinator) {
        if (realCoordinator == null) realCoordinator = coordinator
        refreshAttempt()
    }

    fun selectRealPhoto(id: String) {
        if (!realBusy && !realAttemptStarted) realSelectedPhotoId = id
    }

    /** Called only by the confirmation dialog; the button merely opens that dialog. */
    fun identifyReal(username: String, password: String, photo: DraftPhoto) {
        val coordinator = realCoordinator ?: return
        if (realBusy || realAttemptStarted || realSelectedPhotoId != photo.id) return
        if (username.isBlank() || password.isBlank()) {
            realStatus = "REAL · introduce usuario y contraseña de aplicación de WordPress."
            return
        }
        realBusy = true
        realRun = null
        realStatus = "REAL · enviando una única identificación; espera la respuesta…"
        coordinator.authorizeIdentificationForSession()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withTimeout(realTimeoutMillis) { coordinator.identify(username, password, photo) }
                realRun = result
                realStatus = describeRun(result)
            } catch (_: TimeoutCancellationException) {
                realStatus = "REAL · tiempo de espera agotado. El resultado es incierto; no se enviará otro POST."
            } catch (error: AiHttpException) {
                realStatus = describeHttp(error.status, error.wpCode)
            } catch (_: SSLException) {
                realStatus = "REAL · fallo de seguridad TLS. No se enviará otro POST."
            } catch (_: SocketTimeoutException) {
                realStatus = "REAL · timeout; el resultado es incierto. No se enviará otro POST."
            } catch (_: AiInvalidException) {
                realStatus = "REAL · respuesta de WordPress inválida. El intento queda reservado."
            } catch (_: IOException) {
                realStatus = "REAL · conexión fallida o respuesta incierta. El intento queda reservado."
            } catch (cancelled: CancellationException) {
                realStatus = "REAL · operación interrumpida; consulta el intento antes de continuar."
                throw cancelled
            } catch (_: Exception) {
                realStatus = "REAL · la identificación no pudo confirmarse. El intento queda reservado."
            } finally {
                refreshAttempt(announce = false)
                realBusy = false
            }
        }
    }

    /** GET of a known run only. It never starts another billable identification. */
    fun checkExisting(username: String, password: String) {
        val coordinator = realCoordinator ?: return
        if (realBusy || realKnownRunId == null) return
        if (username.isBlank() || password.isBlank()) {
            realStatus = "REAL · introduce las credenciales para consultar la ejecución existente."
            return
        }
        realBusy = true
        realStatus = "REAL · consultando la ejecución existente…"
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withTimeout(130_000) { coordinator.checkExisting(username, password) }
                if (result == null) {
                    realStatus = "REAL · no se conoce el ID remoto; no se enviará otro POST."
                } else {
                    realRun = result
                    realStatus = describeRun(result)
                }
            } catch (_: TimeoutCancellationException) {
                realStatus = "REAL · consulta agotó el tiempo de espera. No se envió otro POST."
            } catch (error: AiHttpException) {
                realStatus = describeHttp(error.status, error.wpCode)
            } catch (_: SSLException) {
                realStatus = "REAL · fallo TLS al consultar la ejecución existente."
            } catch (_: Exception) {
                realStatus = "REAL · no se pudo consultar la ejecución existente."
            } finally {
                refreshAttempt(announce = false)
                realBusy = false
            }
        }
    }

    private fun refreshAttempt(announce: Boolean = true) {
        val coordinator = realCoordinator ?: return
        try {
            val attempt = coordinator.attempt()
            realAttemptStarted = attempt != null
            realKnownRunId = attempt?.runId
            realRequestId = attempt?.clientRequestId
            if (announce && attempt != null && realRun == null && !realBusy) {
                realStatus = if (attempt.runId == null)
                    "REAL · existe un intento previo de resultado incierto; se bloqueó cualquier nuevo envío."
                else "REAL · ejecución existente ${attempt.runId}; consulta su estado con las credenciales en memoria."
            }
        } catch (_: Exception) {
            // A damaged journal must fail closed; never offer a second provider call.
            realAttemptStarted = true
            realKnownRunId = null
            realStatus = "REAL · no se pudo leer el registro del intento. Se bloqueó un nuevo envío."
        }
    }

    private fun describeRun(result: IdentificationRun): String = when (result.run.status) {
        "completed" -> if (result.proposal != null)
            "REAL · identificación completada; la propuesta requiere revisión humana."
        else "REAL · WordPress indicó completada, pero la propuesta es inválida."
        "processing" -> "REAL · procesamiento pendiente. Consulta esta misma ejecución más tarde."
        "failed" -> "REAL · la identificación falló en el servidor${safeError(result.run.error)}."
        "unknown" -> "REAL · el servidor no conoce el resultado; no se iniciará otra identificación."
        "interrupted" -> "REAL · ejecución interrumpida; no se iniciará otra identificación."
        else -> "REAL · estado inesperado de WordPress; el intento queda reservado."
    }

    private fun safeError(value: String?): String {
        val code = value?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{2,100}")) }
        return code?.let { " ($it)" } ?: ""
    }

    private fun describeHttp(status: Int, wpCode: String?): String = (when (status) {
        400 -> "REAL · solicitud rechazada por WordPress (HTTP 400). El intento queda reservado."
        401 -> "REAL · credenciales rechazadas por WordPress (HTTP 401). El intento queda reservado."
        403 -> "REAL · permisos insuficientes (HTTP 403). El intento queda reservado."
        413 -> "REAL · la fotografía supera el límite del servidor (HTTP 413)."
        422 -> "REAL · el proveedor rechazó el contenido (HTTP 422)."
        429 -> "REAL · cuota agotada (HTTP 429)."
        502 -> "REAL · falló la respuesta del proveedor (HTTP 502); el resultado puede ser incierto."
        503 -> "REAL · proveedor no configurado en WordPress (HTTP 503)."
        504 -> "REAL · timeout del servidor (HTTP 504); el resultado es incierto."
        else -> "REAL · WordPress devolvió HTTP $status. El intento queda reservado."
    }) + (wpCode?.let { " Código: $it." } ?: "")

    // The established demonstration remains entirely local and separate from the real request.
    fun runIdentification() {
        identification = IdentificationProposal("TSTU4855933", listOf(IdentificationCandidate("TSTU4855933", true), IdentificationCandidate("TSTU4855938", false)), "22G1", "uncertain", listOf("La placa está parcialmente oculta."))
        reviewed = null
        status = "SIMULADO · propuesta de identificación lista para revisión humana."
    }
    fun runAnalysis() {
        analysis = AnalysisProposal(emptyList(), listOf(com.mucacran.infocontainer.network.VisibleReading("identifier", "TSTU4855933", listOf(12))), listOf(com.mucacran.infocontainer.network.AnalysisItem("Abolladura visible en la puerta derecha.", listOf(15))), listOf(com.mucacran.infocontainer.network.AnalysisItem("La iluminación no permite confirmar el sello.", listOf(15))), listOf("puerta derecha"), listOf("techo"), listOf(12, 15), emptyList(), "Se observan daños visibles en la puerta derecha.", "Borrador sujeto a revisión humana.", null)
        editedReport = analysis?.reportDraft.orEmpty()
        reviewed = null
        status = "SIMULADO · análisis listo para revisión humana."
    }
    fun updateEditedReport(value: String) { if (value.length <= 12000) editedReport = value }
    fun review(action: String) { reviewed = action; status = "SIMULADO · revisión $action registrada localmente; no se modificaron datos del servidor." }
    fun clear() { identification = null; analysis = null; reviewed = null; status = "Modo SIMULADO: no se realizan solicitudes de red." }
}
