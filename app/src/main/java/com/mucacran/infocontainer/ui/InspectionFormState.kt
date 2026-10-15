package com.mucacran.infocontainer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.mucacran.infocontainer.data.InspectionDraftRepository
import com.mucacran.infocontainer.model.InspectionDraft
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
    val observations: String = ""
)

sealed class FormLoadState {
    object NewDraft : FormLoadState()
    object Loading : FormLoadState()
    data class Success(val draft: InspectionDraft) : FormLoadState()
    object NotFound : FormLoadState()
    data class Error(val message: String) : FormLoadState()
}

class InspectionFormState : ViewModel() {
    var content by mutableStateOf(FormContent())
        private set
    var lastSavedContent by mutableStateOf(FormContent())
        private set
    var draftId by mutableStateOf<String?>(null)
        private set
    var createdAt by mutableLongStateOf(0L)
        private set
    var loadState by mutableStateOf<FormLoadState>(FormLoadState.Loading)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var isOpened by mutableStateOf(false)
        private set
    var completedSaveCount by mutableIntStateOf(0)
        private set
    var pendingSaveResult by mutableStateOf<Result<Boolean>?>(null)
        private set

    private var initialized = false
    private var isLoading = false
    private var openGeneration = 0L

    private val hasLoadedContent: Boolean
        get() = loadState is FormLoadState.NewDraft || loadState is FormLoadState.Success

    val canEdit: Boolean
        get() = hasLoadedContent && !isSaving

    val hasUnsavedChanges: Boolean
        get() = hasLoadedContent && content != lastSavedContent

    val canLeave: Boolean
        get() = !isSaving

    fun open(draftId: String?): Boolean {
        if (isSaving) return false
        openGeneration++
        this.draftId = draftId
        createdAt = System.currentTimeMillis()
        content = FormContent(
            inspectedAt = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        )
        lastSavedContent = content
        initialized = draftId == null
        isLoading = false
        pendingSaveResult = null
        loadState = if (draftId == null) FormLoadState.NewDraft else FormLoadState.Loading
        isOpened = true
        return true
    }

    fun ensureOpened(draftId: String?) {
        if (!isOpened) open(draftId)
    }

    suspend fun loadIfNeeded(repository: InspectionDraftRepository) {
        if (!isOpened || initialized || isLoading || isSaving) return
        val targetId = draftId ?: return
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
                    if (draft == null) {
                        loadState = FormLoadState.NotFound
                    } else {
                        content = draft.toFormContent()
                        lastSavedContent = content
                        createdAt = draft.createdAt
                        loadState = FormLoadState.Success(draft)
                    }
                },
                onFailure = { error ->
                    loadState = FormLoadState.Error(error.localizedMessage ?: "Error desconocido")
                }
            )
            initialized = true
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
        if (!canEdit) return false
        content = next
        return true
    }

    fun consumeSaveResult(): Result<Boolean>? {
        val result = pendingSaveResult
        pendingSaveResult = null
        return result
    }

    suspend fun save(repository: InspectionDraftRepository): Result<Boolean>? {
        if (!canEdit) return null
        isSaving = true
        val saveResult = try {
            val existingId = draftId
            val savedId = existingId ?: UUID.randomUUID().toString()
            val snapshot = content.copy(
                containerIdentifier = ContainerValidator.normalizeContainerIdentifier(content.containerIdentifier),
                isoCode = ContainerValidator.normalizeIsoCode(content.isoCode)
            )
            val draft = InspectionDraft(
                id = savedId,
                containerIdentifier = snapshot.containerIdentifier,
                isoCode = snapshot.isoCode,
                inspectionType = snapshot.inspectionType,
                inspectedAt = snapshot.inspectedAt,
                observations = snapshot.observations,
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis()
            )
            val result = try {
                if (existingId == null) repository.createDraft(draft) else repository.updateDraft(draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            val confirmedResult = if (result.isSuccess && result.getOrNull() != true) {
                Result.failure(IllegalStateException("No se confirmó el guardado del borrador."))
            } else {
                result
            }
            if (confirmedResult.isSuccess) {
                draftId = savedId
                content = snapshot
                lastSavedContent = snapshot
                loadState = FormLoadState.Success(draft)
                initialized = true
            }
            confirmedResult
        } finally {
            isSaving = false
        }
        pendingSaveResult = saveResult
        completedSaveCount++
        return saveResult
    }

    private fun InspectionDraft.toFormContent() = FormContent(
        containerIdentifier = containerIdentifier,
        isoCode = isoCode,
        inspectionType = inspectionType,
        inspectedAt = inspectedAt,
        observations = observations
    )
}
