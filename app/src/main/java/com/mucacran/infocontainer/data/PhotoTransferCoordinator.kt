package com.mucacran.infocontainer.data

import com.mucacran.infocontainer.data.transfer.PhotoTransferMetadata
import com.mucacran.infocontainer.data.transfer.PhotoTransferRecord
import com.mucacran.infocontainer.data.transfer.TransferJournal
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.network.PhotoApi
import com.mucacran.infocontainer.network.UploadPhotoPayload

/** A supervised transfer of an already saved original. Never creates or updates local drafts. */
class PhotoTransferCoordinator(
    private val drafts: InspectionDraftRepository,
    private val journal: TransferJournal,
    private val prepareSource: suspend (DraftPhoto) -> PreparedPhotoUpload,
    private val api: PhotoApi
) {
    suspend fun upload(
        draftId: String,
        inspectionId: Long,
        localPhotoId: String,
        category: String,
        description: String,
        username: String,
        applicationPassword: String
    ): PhotoTransferRecord {
        require(inspectionId > 0)
        val draft = drafts.getDraftById(draftId).getOrThrow()
            ?: error("El borrador local ya no está disponible. No se envió ninguna fotografía.")
        val photo = draft.photos.singleOrNull { it.id == localPhotoId }
            ?: error("La fotografía ya no está guardada en este borrador. No se envió.")
        val source = prepareSource(photo)
        check(source.clientPhotoId == localPhotoId)
        // This durable write must succeed before a request can reach WordPress.
        val planned = journal.prepare(
            draftId, inspectionId,
            PhotoTransferMetadata(
                localPhotoId = localPhotoId,
                sha256 = source.sha256,
                byteSize = source.byteSize,
                mimeType = source.mimeType,
                category = category,
                description = description
            )
        )
        val remote = api.upload(
            username, applicationPassword, inspectionId,
            UploadPhotoPayload(
                file = source.file,
                clientPhotoId = planned.clientPhotoId,
                category = planned.metadata.category,
                mimeType = planned.metadata.mimeType,
                byteSize = planned.metadata.byteSize,
                sha256 = planned.metadata.sha256,
                description = planned.metadata.description.takeIf { it.isNotEmpty() },
                capturedAt = planned.metadata.capturedAt
            )
        )
        // The API checks the echoed inspection/UUID/category/size/hash before this association.
        return journal.complete(draftId, localPhotoId, remote.id)
    }
}
