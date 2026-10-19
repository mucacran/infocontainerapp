package com.mucacran.infocontainer.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.network.AiApi
import com.mucacran.infocontainer.network.AiExecutionGate
import com.mucacran.infocontainer.network.AiReviewResponse
import com.mucacran.infocontainer.network.IdentificationRun
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The first authorized identification consumes one durable slot before any POST is dispatched. */
class AiIdentificationCoordinator internal constructor(
    private val api: AiApi,
    private val resolveOriginal: (DraftPhoto) -> File,
    private val gate: AiExecutionGate,
    private val attempts: IdentificationAttemptJournal
) {
    constructor(api: AiApi, store: LocalPhotoStore, gate: AiExecutionGate, attempts: IdentificationAttemptJournal) :
        this(api, store::originalForUpload, gate, attempts)

    private data class ReviewKey(val runId: Long, val action: String, val content: JsonElement, val resolution: String?)
    private val reviewIds = mutableMapOf<ReviewKey, String>()

    val identificationAuthorized: Boolean
        get() = gate.identificationAuthorized

    fun authorizeIdentificationForSession() = gate.authorizeIdentificationForSession()

    /** Returns only non-secret metadata. A corrupt journal fails closed rather than allowing a new POST. */
    fun attempt(): IdentificationAttempt? = attempts.read()

    suspend fun identify(username: String, password: String, photo: DraftPhoto): IdentificationRun {
        check(gate.identificationAuthorized) { "Real identification is disabled" }
        require(username.isNotBlank() && password.isNotBlank() && !username.contains(':') &&
            !username.contains('\n') && !username.contains('\r') &&
            !password.contains('\n') && !password.contains('\r')) { "Credenciales de aplicación incompletas." }
        attempts.read()?.let { throw IdentificationAlreadyAttemptedException(it.runId) }
        require(photo.hasValidMetadata() && photo.mimeType in setOf("image/jpeg", "image/png") &&
            photo.byteSize <= 10L * 1024 * 1024) {
            "La fotografía debe ser PNG o JPEG válido de hasta 10 MiB."
        }
        // Hash and validate the existing private original before reserving the only request.
        val file = resolveOriginal(photo)
        val fingerprint = withContext(Dispatchers.IO) { sha256(file, photo) }
        val reserved = IdentificationAttempt(UUID.randomUUID().toString(), photo.id, fingerprint, null)
        val existing = attempts.reserveIfAbsent(reserved)
        if (existing != null) throw IdentificationAlreadyAttemptedException(existing.runId)

        // The durable reservation is intentionally retained on every exception or cancellation.
        val response = api.identify(username, password, file, reserved.clientRequestId)
        requireMatchingRun(response, reserved.clientRequestId)
        attempts.recordRunId(reserved.clientRequestId, response.run.id)
        return response
    }

    /** A GET is possible only after WordPress returned a run ID in the original POST response. */
    suspend fun checkExisting(username: String, password: String): IdentificationRun? {
        val saved = attempts.read() ?: return null
        val runId = saved.runId ?: return null
        val response = api.getIdentificationRun(username, password, runId)
        requireMatchingRun(response, saved.clientRequestId)
        check(response.run.id == runId) { "La ejecución consultada no coincide con el intento reservado." }
        return response
    }

    /** Real reviews are still gated separately; the first identification does not authorize them. */
    suspend fun review(
        username: String,
        password: String,
        runId: Long,
        action: String,
        content: Any? = null,
        resolution: String? = null
    ): AiReviewResponse {
        check(gate.authorized) { "Real AI review is disabled" }
        check(attempts.read()?.runId == runId) { "La revisión no corresponde a la identificación reservada." }
        val key = ReviewKey(runId, action, Gson().toJsonTree(content), resolution)
        val id = synchronized(reviewIds) { reviewIds.getOrPut(key) { UUID.randomUUID().toString() } }
        return api.review(username, password, runId, id, action, content, resolution)
    }

    private fun requireMatchingRun(response: IdentificationRun, requestId: String) {
        check(response.run.id > 0 && response.run.operation == "identification" &&
            response.run.clientRequestId == requestId) {
            "La ejecución recibida no coincide con el intento reservado."
        }
    }

    private fun sha256(file: File, photo: DraftPhoto): String {
        require(file.isFile && file.length() == photo.byteSize) { "La fotografía privada no está disponible o cambió." }
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(8)
        var headerCount = 0
        var total = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (headerCount < header.size) {
                    val copied = minOf(count, header.size - headerCount)
                    buffer.copyInto(header, headerCount, 0, copied)
                    headerCount += copied
                }
                total += count
                digest.update(buffer, 0, count)
            }
        }
        require(total == photo.byteSize) { "La fotografía privada cambió durante la lectura." }
        val jpeg = headerCount >= 3 && header[0] == 0xff.toByte() &&
            header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
        val png = headerCount == 8 && header.contentEquals(byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        ))
        require((photo.mimeType == "image/jpeg" && jpeg) || (photo.mimeType == "image/png" && png)) {
            "La firma de la fotografía no coincide con PNG/JPEG."
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

class IdentificationAlreadyAttemptedException(val knownRunId: Long?) : IllegalStateException(
    if (knownRunId == null) "La identificación ya se inició; su resultado es incierto y no se enviará otro POST."
    else "La identificación ya se inició; consulta la ejecución existente."
)
