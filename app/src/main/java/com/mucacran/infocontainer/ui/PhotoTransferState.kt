package com.mucacran.infocontainer.ui


import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.data.PhotoTransferCoordinator
import com.mucacran.infocontainer.data.PhotoUploadSource
import com.mucacran.infocontainer.data.PhotoUploadValidationException
import com.mucacran.infocontainer.data.transfer.DraftTransferRecord
import com.mucacran.infocontainer.data.transfer.TransferJournal
import com.mucacran.infocontainer.data.transfer.TransferJournalException
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.network.PhotoApi
import com.mucacran.infocontainer.network.PhotoTransferException
import com.mucacran.infocontainer.network.PhotoTransferFailure
import com.mucacran.infocontainer.network.RemotePhoto
import com.mucacran.infocontainer.network.WordPressPhotoApi
import com.mucacran.infocontainer.network.TestInspectionApi
import com.mucacran.infocontainer.data.TestInspectionStore
import com.mucacran.infocontainer.data.TestInspectionAssociation
import com.mucacran.infocontainer.util.ContainerValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Session UI only; credentials are supplied per action by ConnectionState and never stored here. */
class PhotoTransferState(private val api: PhotoApi = WordPressPhotoApi(), private val debugEnabled: Boolean = false) : ViewModel() {
    private var repository: InspectionDraftRepository? = null
    private var journal: TransferJournal? = null
    private var coordinator: PhotoTransferCoordinator? = null
    private var testInspectionStore: TestInspectionStore? = null
    private var operation: Job? = null
    private var generation = 0L
    var isOpen by mutableStateOf(false)
        private set
    var isBusy by mutableStateOf(false)
        private set
    var drafts by mutableStateOf<List<InspectionDraft>>(emptyList())
        private set
    var selectedDraft by mutableStateOf<InspectionDraft?>(null)
        private set
    var selectedPhotoId by mutableStateOf<String?>(null)
        private set
    var inspectionInput by mutableStateOf("")
        private set
    var category by mutableStateOf("other")
        private set
    var description by mutableStateOf("")
        private set
    var confirmedTestInspection by mutableStateOf(false)
        private set
    var binding by mutableStateOf<DraftTransferRecord?>(null)
        private set
    var remotePhotos by mutableStateOf<List<RemotePhoto>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var testAssociation by mutableStateOf<TestInspectionAssociation?>(null)
        private set
    var testCreationNeedsReview by mutableStateOf(false)
        private set
    val isDevelopmentBuild: Boolean get() = debugEnabled

    val inspectionId: Long? get() = inspectionInput.toLongOrNull()?.takeIf { it > 0 }
    val selectedRecord get() = binding?.photos?.singleOrNull { it.metadata.localPhotoId == selectedPhotoId }
    val selectedPhoto get() = selectedDraft?.photos?.singleOrNull { it.id == selectedPhotoId }
    val isPrepared: Boolean get() = selectedRecord != null
    val canUpload: Boolean get() = !isBusy && selectedDraft != null && selectedPhotoId != null &&
        inspectionId != null && confirmedTestInspection && description.codePointCount(0, description.length) <= 2000

    fun attach(repository: InspectionDraftRepository, source: PhotoUploadSource, journal: TransferJournal) {
        if (this.repository != null) return
        this.repository = repository
        this.journal = journal
        coordinator = PhotoTransferCoordinator(repository, journal, source::prepare, api)
    }

    fun attachTestStore(store: TestInspectionStore) { if (testInspectionStore == null) { testInspectionStore = store; testAssociation = store.load(); testCreationNeedsReview = testAssociation == null && store.hasUncertainAttempt() } }

    fun createTestInspection(username: String, password: String) {
        if (!debugEnabled || isBusy || username.isBlank() || password.isBlank() || testAssociation != null || api !is TestInspectionApi) return
        runOperation("Creando contenedor e inspección sintéticos…") {
            val created = try { (api as TestInspectionApi).createTestInspection(username, password) }
            catch (failure: Exception) { requireNotNull(testInspectionStore).markUncertain(); testCreationNeedsReview = true; throw failure }
            val association = TestInspectionAssociation(created.containerId, created.containerIdentifier, created.inspectionId)
            require(ContainerValidator.validateContainerIdentifier(association.containerIdentifier).isValid)
            requireNotNull(testInspectionStore).save(association)
            testAssociation = association
            inspectionInput = created.inspectionId.toString()
            confirmedTestInspection = false
            message = "Prueba creada en WordPress: contenedor ${created.containerIdentifier}, inspección draft ${created.inspectionId}. No se subió ninguna fotografía."
        }
    }

    fun open() {
        if (isBusy || isOpen) return
        isOpen = true
        runOperation("Leyendo borradores guardados…") {
            val savedDrafts = requireNotNull(repository).getAllDrafts().getOrThrow().filter { it.photos.isNotEmpty() }
            currentCoroutineContext().ensureActive()
            drafts = savedDrafts
            message = if (drafts.isEmpty()) "Guarda un borrador con una fotografía para preparar esta prueba." else null
        }
    }

    fun selectDraft(id: String) {
        if (isBusy) return
        // Failed selection must not leave the previous draft armed for an upload.
        selectedDraft = null
        selectedPhotoId = null
        binding = null
        inspectionInput = ""
        remotePhotos = emptyList()
        confirmedTestInspection = false
        runOperation("Leyendo la asociación local…") {
            val draft = requireNotNull(repository).getDraftById(id).getOrThrow()
                ?: error("Missing local draft")
            val record = requireNotNull(journal).load(id)
            currentCoroutineContext().ensureActive()
            selectedDraft = draft
            binding = record
            inspectionInput = record?.remoteInspectionId?.toString().orEmpty()
            remotePhotos = emptyList()
            confirmedTestInspection = false
            selectPhotoValues(draft.photos.firstOrNull()?.id)
            message = if (record != null) "Asociación recuperada. Se conservará el mismo ID de inspección y UUID de fotografía al reintentar." else null
        }
    }

    fun selectPhoto(id: String) {
        if (isBusy || selectedDraft?.photos?.none { it.id == id } != false) return
        selectPhotoValues(id)
        message = null
    }

    private fun selectPhotoValues(id: String?) {
        selectedPhotoId = id
        category = selectedRecord?.metadata?.category ?: "other"
        description = selectedRecord?.metadata?.description.orEmpty()
    }

    fun updateInspection(value: String) {
        if (!isBusy && binding == null && value.length <= 18 && value.all(Char::isDigit)) {
            inspectionInput = value
            confirmedTestInspection = false
            remotePhotos = emptyList()
        }
    }

    fun updateCategory(value: String) { if (!isBusy && !isPrepared && value in categories) category = value }
    fun updateDescription(value: String) {
        if (!isBusy && !isPrepared && value.codePointCount(0, value.length) <= 2000) description = value
    }
    fun confirmTestInspection(value: Boolean) { if (!isBusy) confirmedTestInspection = value }

    fun upload(username: String, password: String) {
        if (!canUpload || !credentialsPresent(username, password)) return
        val draftId = requireNotNull(selectedDraft).id
        val remoteId = requireNotNull(inspectionId)
        val photoId = requireNotNull(selectedPhotoId)
        val ticket = generation
        runOperation("Subiendo una fotografía…") {
            try {
                val record = requireNotNull(coordinator).upload(draftId, remoteId, photoId, category, description, username, password)
                currentCoroutineContext().ensureActive()
                message = "Fotografía confirmada en WordPress. ID remoto: ${record.remotePhotoId}. El original local se conserva."
            } finally {
                // A timeout/cancellation may still have reached the server: retain the durable plan.
                if (generation == ticket) {
                    val durable = requireNotNull(journal).load(draftId)
                    currentCoroutineContext().ensureActive()
                    binding = durable
                    binding?.let { inspectionInput = it.remoteInspectionId.toString() }
                    selectPhotoValues(photoId)
                }
            }
        }
    }

    fun listRemote(username: String, password: String) {
        val remoteId = inspectionId ?: return
        if (isBusy || !credentialsPresent(username, password)) return
        runOperation("Consultando metadatos de la inspección…") {
            val received = api.list(username, password, remoteId)
            currentCoroutineContext().ensureActive()
            remotePhotos = received
            message = "Consulta correcta: ${remotePhotos.size} fotografías en la inspección $remoteId."
        }
    }

    fun verifyRemote(photo: RemotePhoto, username: String, password: String) {
        if (isBusy || !credentialsPresent(username, password)) return
        runOperation("Recuperando bytes y comprobando integridad…") {
            val metadata = api.metadata(username, password, photo.id)
            if (metadata.id != photo.id || metadata.inspectionId != photo.inspectionId ||
                !metadata.clientPhotoId.equals(photo.clientPhotoId, ignoreCase = true) ||
                !metadata.sha256.equals(photo.sha256, ignoreCase = true) || metadata.byteSize != photo.byteSize ||
                metadata.mimeType != photo.mimeType || metadata.category != photo.category) {
                throw PhotoTransferException(PhotoTransferFailure.INTEGRITY_MISMATCH)
            }
            val verified = api.downloadVerified(username, password, metadata)
            currentCoroutineContext().ensureActive()
            val local = binding?.photos?.singleOrNull { it.clientPhotoId.equals(metadata.clientPhotoId, ignoreCase = true) }
            if (local != null) {
                if (!local.metadata.sha256.equals(metadata.sha256, ignoreCase = true) ||
                    local.metadata.byteSize != metadata.byteSize || local.metadata.mimeType != metadata.mimeType ||
                    local.metadata.category != metadata.category || binding?.remoteInspectionId != metadata.inspectionId ||
                    (local.remotePhotoId != null && local.remotePhotoId != metadata.id)) {
                    throw PhotoTransferException(PhotoTransferFailure.INTEGRITY_MISMATCH)
                }
            }
            message = "Integridad verificada: fotografía ${verified.photoId}, ${verified.byteSize} bytes y SHA-256 coincidentes con los metadatos remotos" +
                (if (local != null) " y la asociación local." else ". No hay asociación local para este UUID.") +
                " No se sustituyó ningún archivo local."
        }
    }

    fun close() {
        generation++
        operation?.cancel()
        operation = null
        isOpen = false
        isBusy = false
        drafts = emptyList()
        selectedDraft = null
        selectedPhotoId = null
        binding = null
        inspectionInput = ""
        category = "other"
        description = ""
        confirmedTestInspection = false
        remotePhotos = emptyList()
        message = null
    }

    private fun runOperation(progress: String, action: suspend () -> Unit) {
        if (isBusy) return
        isBusy = true
        message = progress
        val ticket = generation
        operation = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PhotoTransferException) {
                if (generation == ticket) message = failureMessage(failure.reason)
            } catch (_: TransferJournalException) {
                if (generation == ticket) message = "No se pudo confirmar la asociación local o entra en conflicto con un envío anterior. Conserva el borrador y reabre esta sesión; no cambies el destino para reintentar."
            } catch (_: PhotoUploadValidationException) {
                if (generation == ticket) message = "Esta fotografía no cumple los límites de subida: JPEG, PNG o WebP decodificable, hasta 10 MiB, 10.000 píxeles por lado y 20 megapíxeles. El original se conserva sin modificar."
            } catch (_: Exception) {
                if (generation == ticket) message = "No se pudo completar o confirmar la operación. El original se conserva; un envío incierto debe reintentarse con la misma asociación."
            } finally {
                if (generation == ticket) isBusy = false
            }
        }
    }

    override fun onCleared() { close() }

    companion object {
        val categories = WordPressPhotoApi.CATEGORIES
        private fun credentialsPresent(username: String, password: String) = username.isNotBlank() && password.isNotBlank()
        private fun failureMessage(reason: PhotoTransferFailure): String = when (reason) {
            PhotoTransferFailure.CREDENTIALS_REJECTED -> "Credenciales rechazadas (401). Revisa la contraseña de aplicación."
            PhotoTransferFailure.PERMISSION_DENIED -> "Permisos insuficientes (403). Se requiere manage_infocontainer."
            PhotoTransferFailure.NOT_FOUND -> "Inspección o fotografía no encontrada (404). Comprueba el ID de prueba."
            PhotoTransferFailure.NOT_EDITABLE -> "La inspección no admite fotografías (409). Debe permanecer en estado draft."
            PhotoTransferFailure.RETRY_CONFLICT -> "Conflicto de reintento (409): el servidor tiene otro contenido para este UUID. Conserva el original y la asociación para revisar la causa."
            PhotoTransferFailure.TOO_LARGE -> "Límite de tamaño o recursos (413). El límite PHP puede ser menor que 10 MiB."
            PhotoTransferFailure.UNSUPPORTED_FORMAT -> "Formato de imagen no permitido (415)."
            PhotoTransferFailure.INVALID_REQUEST -> "Solicitud no aceptada (400). Revisa categoría e inspección de prueba."
            PhotoTransferFailure.TLS_FAILURE -> "Fallo TLS. No se pudo validar el servidor HTTPS."
            PhotoTransferFailure.UNAVAILABLE -> "Conexión o servicio no disponible. Reintenta manualmente con la misma asociación."
            PhotoTransferFailure.TIMEOUT -> "Tiempo de espera agotado. El servidor podría haber recibido la fotografía. Reintenta el mismo envío: se conservará su UUID."
            PhotoTransferFailure.REDIRECT_BLOCKED -> "Redirección bloqueada. Las credenciales no se enviaron a otro destino."
            PhotoTransferFailure.INVALID_RESPONSE -> "Respuesta inválida. La subida no se confirmó localmente; conserva el mismo UUID para reintentar."
            PhotoTransferFailure.INTEGRITY_MISMATCH -> "La integridad recibida no coincide. No se sustituyó ningún original local."
            PhotoTransferFailure.LOCAL_FILE_CHANGED -> "El archivo local cambió desde su preparación. Se detuvo el envío para proteger el reintento."
        }
    }
}
