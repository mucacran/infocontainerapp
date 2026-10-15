package com.mucacran.infocontainer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.mucacran.infocontainer.data.DraftNotFoundException
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.model.PhotoLimits
import com.mucacran.infocontainer.util.ContainerValidator
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException

data class FormContent(
    val containerIdentifier: String = "",
    val isoCode: String = "",
    val inspectionType: String = "",
    val inspectedAt: String = "",
    val observations: String = "",
    val photos: List<DraftPhoto> = emptyList()
)

sealed class FormLoadState {
    object NewDraft : FormLoadState()
    object Loading : FormLoadState()
    data class Success(val draft: InspectionDraft) : FormLoadState()
    object NotFound : FormLoadState()
    data class Error(val message: String) : FormLoadState()
}

class InspectionFormState(private val savedState: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    var content by mutableStateOf(readContent("content") ?: FormContent())
        private set
    var lastSavedContent by mutableStateOf(readContent("reference") ?: FormContent())
        private set
    var draftId by mutableStateOf(savedState.get<String>("draftId"))
        private set
    var createdAt by mutableLongStateOf(savedState.get<Long>("createdAt") ?: 0L)
        private set
    var loadState by mutableStateOf<FormLoadState>(restoreLoadState())
        private set
    var isSaving by mutableStateOf(false)
        private set
    var isOpened by mutableStateOf(savedState.get<Boolean>("opened") ?: false)
        private set
    var completedSaveCount by mutableIntStateOf(0)
        private set
    var pendingSaveResult by mutableStateOf<Result<Boolean>?>(null)
        private set
    var pendingCaptureId by mutableStateOf(savedState.get<String>("captureId"))
        private set
    var externalPhotoRequest by mutableStateOf(savedState.get<String>("photoRequest"))
        private set
    var isImportingPhotos by mutableStateOf(false)
        private set
    var photoErrorMessage by mutableStateOf(savedState.get<String>("photoError"))
        private set
    var photoSessionId by mutableStateOf(savedState.get<String>("photoSessionId") ?: UUID.randomUUID().toString())
        private set
    var hasPendingPhotoSelection by mutableStateOf(savedState.get<Boolean>("pendingPhotoSelection") ?: false)
        private set

    private var initialized = savedState.get<Boolean>("initialized") ?: false
    private var isLoading = false
    private var openGeneration = 0L
    private var pendingSavedId: String? = savedState["pendingSavedId"]
    private var pendingSnapshot: FormContent? = readContent("pendingSnapshot")
    private var pendingWasCreate = savedState.get<Boolean>("pendingWasCreate") ?: false
    private var requiresSaveRecovery = pendingSavedId != null && pendingSnapshot != null

    init {
        if (requiresSaveRecovery) {
            initialized = false
            loadState = FormLoadState.Loading
        }
    }

    private val hasLoadedContent: Boolean
        get() = loadState is FormLoadState.NewDraft || loadState is FormLoadState.Success

    val isPhotoBusy: Boolean
        get() = isImportingPhotos || externalPhotoRequest != null

    val canEdit: Boolean
        get() = hasLoadedContent && !isSaving && !isPhotoBusy && !requiresSaveRecovery

    val hasUnsavedChanges: Boolean
        get() = hasLoadedContent &&
            (content != lastSavedContent || pendingCaptureId != null || hasPendingPhotoSelection)

    val canLeave: Boolean
        get() = !isSaving && !isPhotoBusy && !requiresSaveRecovery

    fun open(draftId: String?): Boolean {
        if (!canLeave) return false
        openGeneration++
        this.draftId = draftId
        createdAt = System.currentTimeMillis()
        content = FormContent(inspectedAt = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        lastSavedContent = content
        initialized = draftId == null
        isLoading = false
        pendingSaveResult = null
        photoErrorMessage = null
        pendingCaptureId = null
        externalPhotoRequest = null
        photoSessionId = UUID.randomUUID().toString()
        hasPendingPhotoSelection = false
        loadState = if (draftId == null) FormLoadState.NewDraft else FormLoadState.Loading
        isOpened = true
        persist()
        return true
    }

    fun ensureOpened(draftId: String?) {
        if (!isOpened) open(draftId)
    }

    suspend fun loadIfNeeded(repository: InspectionDraftRepository) {
        if (!isOpened || initialized || isLoading || isSaving) return
        val targetId = if (requiresSaveRecovery) pendingSavedId else draftId
        if (targetId == null) return
        val generation = openGeneration
        isLoading = true
        loadState = FormLoadState.Loading
        try {
            val result = try {
                repository.getDraftById(targetId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (generation != openGeneration) return
            result.fold(
                onSuccess = { draft ->
                    if (requiresSaveRecovery) recoverInterruptedSave(draft)
                    else if (draft == null) loadState = FormLoadState.NotFound
                    else {
                        content = draft.toFormContent()
                        lastSavedContent = content
                        createdAt = draft.createdAt
                        loadState = FormLoadState.Success(draft)
                    }
                },
                onFailure = { error -> loadState = FormLoadState.Error(error.localizedMessage ?: "Error desconocido") }
            )
            initialized = true
            persist()
        } finally {
            if (generation == openGeneration) isLoading = false
        }
    }

    suspend fun retryLoad(repository: InspectionDraftRepository) {
        if (isLoading || isSaving || hasLoadedContent) return
        initialized = false
        loadIfNeeded(repository)
    }

    fun updateContent(next: FormContent): Boolean {
        if (!canEdit || !validPhotoSet(next.photos)) return false
        content = next.copy(photos = next.photos.toList())
        persist()
        return true
    }

    fun beginPhotoRequest(kind: String, captureId: String? = null): Boolean {
        if (!canEdit) return false
        externalPhotoRequest = kind
        pendingCaptureId = captureId
        photoErrorMessage = null
        persist()
        return true
    }

    fun setPendingCapture(id: String?): Boolean {
        if (isSaving || isImportingPhotos) return false
        pendingCaptureId = id
        persist()
        return true
    }

    fun finishPhotoRequest(clearCapture: Boolean = true) {
        externalPhotoRequest = null
        if (clearCapture) pendingCaptureId = null
        persist()
    }

    fun reportPhotoError(message: String?) {
        photoErrorMessage = message
        persist()
    }

    fun setPendingPhotoSelection(pending: Boolean) {
        hasPendingPhotoSelection = pending
        persist()
    }

    suspend fun addPhotos(importer: suspend () -> List<DraftPhoto>): Result<Int> {
        if (!canEdit) return Result.failure(IllegalStateException("Espera a que termine la operación actual."))
        isImportingPhotos = true
        photoErrorMessage = null
        return try {
            val imported = importer()
            val combined = (content.photos + imported).distinctBy { it.id }
            val conflictingId = imported.any { photo ->
                (content.photos + imported).any { it.id == photo.id && it != photo }
            }
            require(!conflictingId && validPhotoSet(combined)) {
                "Las fotografías superan los límites permitidos o contienen datos no válidos."
            }
            val added = combined.size - content.photos.size
            content = content.copy(photos = combined)
            persist()
            Result.success(added)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportPhotoError(error.localizedMessage ?: "No se pudieron incorporar las fotografías.")
            Result.failure(error)
        } finally {
            isImportingPhotos = false
        }
    }

    fun removePhoto(id: String): Boolean {
        if (!canEdit || content.photos.none { it.id == id }) return false
        content = content.copy(photos = content.photos.filterNot { it.id == id })
        persist()
        return true
    }

    /** Only newly attached files may be cleaned; persisted files removed from the form remain untouched. */
    fun discardChanges(): List<DraftPhoto> {
        if (!canLeave) return emptyList()
        val savedIds = lastSavedContent.photos.mapTo(mutableSetOf()) { it.id }
        val pending = content.photos.filterNot { it.id in savedIds }
        content = lastSavedContent
        hasPendingPhotoSelection = false
        finishPhotoRequest()
        return pending
    }

    fun consumeSaveResult(): Result<Boolean>? {
        val result = pendingSaveResult
        pendingSaveResult = null
        return result
    }

    suspend fun save(repository: InspectionDraftRepository): Result<Boolean>? {
        if (!canEdit) return null
        isSaving = true
        val existingId = draftId
        val savedId = existingId ?: UUID.randomUUID().toString()
        val snapshot = content.copy(
            containerIdentifier = ContainerValidator.normalizeContainerIdentifier(content.containerIdentifier),
            isoCode = ContainerValidator.normalizeIsoCode(content.isoCode),
            photos = content.photos.toList()
        )
        val draft = snapshot.toDraft(savedId)
        // Keep the candidate identity before I/O, so restoration can reconcile a committed first save.
        pendingSavedId = savedId
        pendingSnapshot = snapshot
        pendingWasCreate = existingId == null
        persist()
        val saveResult = try {
            val result = try {
                if (existingId == null) repository.createDraft(draft) else repository.updateDraft(draft)
            } catch (cancelled: CancellationException) {
                requiresSaveRecovery = true
                initialized = false
                loadState = FormLoadState.Loading
                persist()
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            val confirmedResult = if (result.isSuccess && result.getOrNull() != true) {
                Result.failure(IllegalStateException("No se confirmó el guardado del borrador."))
            } else result
            if (confirmedResult.isSuccess) {
                draftId = savedId
                content = snapshot
                lastSavedContent = snapshot
                loadState = FormLoadState.Success(draft)
                initialized = true
                hasPendingPhotoSelection = false
                photoErrorMessage = null
            }
            clearPendingSave()
            persist()
            confirmedResult
        } finally {
            isSaving = false
        }
        pendingSaveResult = saveResult
        completedSaveCount++
        return saveResult
    }

    private fun recoverInterruptedSave(draft: InspectionDraft?) {
        val snapshot = pendingSnapshot ?: return
        when {
            draft != null && draft.toFormContent() == snapshot -> {
                draftId = draft.id
                createdAt = draft.createdAt
                content = snapshot
                lastSavedContent = snapshot
                loadState = FormLoadState.Success(draft)
                pendingSaveResult = Result.success(true)
                hasPendingPhotoSelection = false
                photoErrorMessage = null
            }
            pendingWasCreate && draft == null -> {
                loadState = FormLoadState.NewDraft
                pendingSaveResult = Result.failure(IllegalStateException("El guardado se interrumpió; puedes volver a intentarlo."))
            }
            !pendingWasCreate -> {
                loadState = FormLoadState.Success(lastSavedContent.toDraft(draftId ?: pendingSavedId!!))
                pendingSaveResult = Result.failure(
                    if (draft == null) DraftNotFoundException(draftId ?: pendingSavedId!!)
                    else IllegalStateException("El guardado se interrumpió; se conservaron los cambios pendientes.")
                )
            }
            else -> {
                loadState = FormLoadState.Error("No se pudo confirmar el borrador guardado. Vuelve a comprobarlo.")
                return // An occupied candidate ID must never become an alternative INSERT.
            }
        }
        completedSaveCount++
        clearPendingSave()
    }

    private fun clearPendingSave() {
        pendingSavedId = null
        pendingSnapshot = null
        pendingWasCreate = false
        requiresSaveRecovery = false
    }

    private fun restoreLoadState(): FormLoadState = when (savedState.get<String>("loadState")) {
        "new" -> FormLoadState.NewDraft
        "success" -> draftId?.let { FormLoadState.Success(lastSavedContent.toDraft(it)) } ?: FormLoadState.Loading
        "missing" -> FormLoadState.NotFound
        "error" -> FormLoadState.Error(savedState["loadError"] ?: "Error desconocido")
        else -> FormLoadState.Loading
    }

    private fun persist() {
        writeContent("content", content)
        writeContent("reference", lastSavedContent)
        savedState["draftId"] = draftId
        savedState["createdAt"] = createdAt
        savedState["opened"] = isOpened
        savedState["initialized"] = initialized
        savedState["loadState"] = when (loadState) {
            FormLoadState.NewDraft -> "new"
            FormLoadState.Loading -> "loading"
            is FormLoadState.Success -> "success"
            FormLoadState.NotFound -> "missing"
            is FormLoadState.Error -> "error"
        }
        savedState["loadError"] = (loadState as? FormLoadState.Error)?.message
        savedState["photoRequest"] = externalPhotoRequest
        savedState["captureId"] = pendingCaptureId
        savedState["photoError"] = photoErrorMessage
        savedState["photoSessionId"] = photoSessionId
        savedState["pendingPhotoSelection"] = hasPendingPhotoSelection
        savedState["pendingSavedId"] = pendingSavedId
        writeContent("pendingSnapshot", pendingSnapshot)
        savedState["pendingWasCreate"] = pendingWasCreate
    }

    // Strings and primitive collections only: no image bytes, bitmaps, external URIs or Parcelable models.
    private fun writeContent(key: String, value: FormContent?) {
        savedState[key] = value?.let {
            arrayListOf(it.containerIdentifier, it.isoCode, it.inspectionType, it.inspectedAt, it.observations)
        }
        savedState["$key.photos"] = value?.photos?.flatMapTo(arrayListOf<String>()) {
            listOf(it.id, it.mimeType, it.byteSize.toString(), it.width.toString(), it.height.toString())
        }
    }

    private fun readContent(key: String): FormContent? {
        val fields = savedState.get<ArrayList<String>>(key) ?: return null
        if (fields.size != 5) return null
        val encodedPhotos = savedState.get<ArrayList<String>>("$key.photos") ?: arrayListOf()
        val photos = encodedPhotos.chunked(5).mapNotNull { values ->
            if (values.size != 5) null else runCatching {
                DraftPhoto(values[0], values[1], values[2].toLong(), values[3].toInt(), values[4].toInt())
            }.getOrNull()
        }
        return FormContent(fields[0], fields[1], fields[2], fields[3], fields[4], photos)
    }

    private fun FormContent.toDraft(id: String) = InspectionDraft(
        id = id,
        containerIdentifier = containerIdentifier,
        isoCode = isoCode,
        inspectionType = inspectionType,
        inspectedAt = inspectedAt,
        observations = observations,
        photos = photos.toList(),
        createdAt = createdAt,
        updatedAt = System.currentTimeMillis()
    )

    private fun InspectionDraft.toFormContent() = FormContent(
        containerIdentifier, isoCode, inspectionType, inspectedAt, observations, photos.toList()
    )

    private fun validPhotoSet(photos: List<DraftPhoto>): Boolean =
        photos.size <= PhotoLimits.MAX_PHOTOS && photos.distinctBy { it.id }.size == photos.size &&
            photos.all { it.hasValidMetadata() } && photos.sumOf { it.byteSize } <= PhotoLimits.MAX_BYTES_PER_DRAFT
}
