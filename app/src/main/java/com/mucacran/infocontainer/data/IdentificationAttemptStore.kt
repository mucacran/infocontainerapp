package com.mucacran.infocontainer.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONObject

data class IdentificationAttempt(
    val clientRequestId: String,
    val photoId: String,
    val sha256: String,
    val runId: Long?
)

interface IdentificationAttemptJournal {
    fun read(): IdentificationAttempt?
    /** Atomically reserve the first attempt. Returns the prior attempt, if any. */
    fun reserveIfAbsent(attempt: IdentificationAttempt): IdentificationAttempt?
    fun recordRunId(clientRequestId: String, runId: Long)
}

/** Non-secret, fail-closed journal in noBackupFilesDir. It never stores a password or image. */
class IdentificationAttemptStore internal constructor(path: File) : IdentificationAttemptJournal {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "ai_identification_first_attempt.json"))

    private val file = AtomicFile(path)

    override fun read(): IdentificationAttempt? = synchronized(lock) { readLocked() }

    override fun reserveIfAbsent(attempt: IdentificationAttempt): IdentificationAttempt? = synchronized(lock) {
        val existing = readLocked()
        if (existing != null) return@synchronized existing
        validate(attempt)
        writeLocked(attempt)
        null
    }

    override fun recordRunId(clientRequestId: String, runId: Long) = synchronized(lock) {
        require(runId > 0)
        val existing = readLocked() ?: error("La reserva de identificación no existe.")
        check(existing.clientRequestId == clientRequestId) { "El UUID no coincide con el intento reservado." }
        check(existing.runId == null || existing.runId == runId) { "El ID remoto ya corresponde a otra ejecución." }
        if (existing.runId == null) writeLocked(existing.copy(runId = runId))
    }

    private fun readLocked(): IdentificationAttempt? {
        val base = file.baseFile
        if (!base.exists() && !File(base.path + ".bak").exists()) return null
        val text = file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        return try {
            val json = JSONObject(text)
            IdentificationAttempt(
                json.getString("client_request_id"),
                json.getString("photo_id"),
                json.getString("sha256"),
                if (json.isNull("run_id")) null else json.getLong("run_id")
            ).also(::validate)
        } catch (failure: Exception) {
            throw IllegalStateException("El registro del intento de identificación es ilegible; se bloqueó otro envío.", failure)
        }
    }

    private fun writeLocked(attempt: IdentificationAttempt) {
        val parent = file.baseFile.parentFile ?: error("Falta el directorio del registro de identificación.")
        check(parent.isDirectory || parent.mkdirs()) { "No se pudo preparar el registro de identificación." }
        val json = JSONObject().apply {
            put("client_request_id", attempt.clientRequestId)
            put("photo_id", attempt.photoId)
            put("sha256", attempt.sha256)
            put("run_id", attempt.runId ?: JSONObject.NULL)
        }.toString()
        val stream = file.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun validate(attempt: IdentificationAttempt) {
        check(UUID.fromString(attempt.clientRequestId).toString() == attempt.clientRequestId)
        check(UUID.fromString(attempt.photoId).toString() == attempt.photoId)
        check(attempt.sha256.matches(Regex("[0-9a-f]{64}")))
        check(attempt.runId == null || attempt.runId > 0)
    }

    private companion object { val lock = Any() }
}
