package com.mucacran.infocontainer.data

import kotlinx.coroutines.CancellationException

/** A cleanup failure may leave an unused private file; it must never fail a committed save. */
suspend fun LocalPhotoStore.cleanUnreferencedSafely(id: String, repository: InspectionDraftRepository) {
    try {
        deleteUnreferenced(id) { repository.isPhotoReferenced(it).getOrDefault(true) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Conservatively retain our own file when deletion or the reference check is uncertain.
    }
}
