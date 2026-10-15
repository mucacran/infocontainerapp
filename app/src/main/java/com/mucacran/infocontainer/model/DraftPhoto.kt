package com.mucacran.infocontainer.model

import java.util.UUID

/** Metadata only: the ID resolves to an app-private file; no external URI is persisted. */
data class DraftPhoto(
    val id: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int
) {
    fun hasValidMetadata(): Boolean = runCatching {
        UUID.fromString(id).toString() == id &&
            mimeType in PhotoLimits.SUPPORTED_MIME_TYPES &&
            byteSize in 1..PhotoLimits.MAX_BYTES_PER_PHOTO &&
            width > 0 && height > 0 &&
            width.toLong() * height <= PhotoLimits.MAX_PIXELS
    }.getOrDefault(false)
}

object PhotoLimits {
    const val MAX_PHOTOS = 12
    const val MAX_BYTES_PER_PHOTO = 30L * 1024 * 1024
    const val MAX_BYTES_PER_DRAFT = 150L * 1024 * 1024
    const val MAX_PIXELS = 40_000_000L
    val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
}
