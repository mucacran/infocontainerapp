package com.mucacran.infocontainer.ui

import com.mucacran.infocontainer.util.ContainerValidator

/** Local prerequisites only. The normal inspection analysis contract is not available yet. */
enum class PhotoAnalysisReadiness {
    BUSY,
    RECOVERY_REQUIRED,
    PHOTOS_REQUIRED,
    REASON_REQUIRED,
    SERVER_UNAVAILABLE
}

/** Reading this value never saves, transfers photographs, or starts an analysis. */
val InspectionFormState.photoAnalysisReadiness: PhotoAnalysisReadiness
    get() = when {
        !canEdit -> PhotoAnalysisReadiness.BUSY
        // A successful import keeps its recovery journal until the draft is saved.
        recoveryPending || pendingCaptureId != null || (hasPendingPhotoSelection && photoErrorMessage != null) ->
            PhotoAnalysisReadiness.RECOVERY_REQUIRED
        content.photos.isEmpty() -> PhotoAnalysisReadiness.PHOTOS_REQUIRED
        !ContainerValidator.validateInspectionType(content.inspectionType).isValid ->
            PhotoAnalysisReadiness.REASON_REQUIRED
        else -> PhotoAnalysisReadiness.SERVER_UNAVAILABLE
    }
