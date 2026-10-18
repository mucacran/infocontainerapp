package com.mucacran.infocontainer.network

import java.io.File

/** Public metadata only. This model deliberately contains no URLs or credentials. */
data class RemotePhoto(
    val id: Long,
    val inspectionId: Long,
    val clientPhotoId: String,
    val category: String,
    val mimeType: String,
    val byteSize: Long,
    val sha256: String
)

/** The caller must persist this UUID and immutable metadata before the first attempt. */
data class UploadPhotoPayload(
    val file: File,
    val clientPhotoId: String,
    val category: String,
    val mimeType: String,
    val byteSize: Long,
    val sha256: String,
    val description: String? = null,
    val capturedAt: String? = null
)

/** The authenticated stream was hashed and discarded; no local original is replaced. */
data class VerifiedPhotoDownload(val photoId: Long, val byteSize: Long, val sha256: String)

enum class PhotoTransferFailure {
    CREDENTIALS_REJECTED, PERMISSION_DENIED, NOT_FOUND, NOT_EDITABLE, RETRY_CONFLICT,
    TOO_LARGE, UNSUPPORTED_FORMAT, INVALID_REQUEST, TLS_FAILURE, UNAVAILABLE, TIMEOUT,
    REDIRECT_BLOCKED, INVALID_RESPONSE, INTEGRITY_MISMATCH, LOCAL_FILE_CHANGED
}

/** Messages, causes, arbitrary server bodies and authentication headers are never retained. */
class PhotoTransferException(
    val reason: PhotoTransferFailure,
    val httpCode: Int? = null,
    val wpCode: String? = null
) : Exception(reason.name)

interface PhotoApi {
    suspend fun upload(
        username: String, applicationPassword: String, inspectionId: Long, payload: UploadPhotoPayload
    ): RemotePhoto

    suspend fun list(username: String, applicationPassword: String, inspectionId: Long): List<RemotePhoto>

    suspend fun metadata(username: String, applicationPassword: String, photoId: Long): RemotePhoto

    suspend fun downloadVerified(
        username: String, applicationPassword: String, photo: RemotePhoto
    ): VerifiedPhotoDownload
}
