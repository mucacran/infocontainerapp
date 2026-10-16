package com.mucacran.infocontainer.ui

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Durable recovery record for an interrupted form. Writes are serialized off the UI thread. */
class FormRecoveryStore(context: Context) {
    data class Record(
        val draftId: String?, val createdAt: Long, val sessionId: String,
        val content: FormContent, val reference: FormContent,
        val pendingCaptureId: String?, val pendingSelection: Boolean
    )

    private val file = AtomicFile(File(context.noBackupFilesDir, "inspection_form_recovery.json"))
    private val scope = CoroutineScope(Dispatchers.IO)
    private val writeMutex = Mutex()

    fun read(): Record? = runCatching {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        decode(file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) })
    }.getOrNull()

    fun write(record: Record?) {
        scope.launch {
            writeMutex.withLock {
                if (record == null) { file.baseFile.delete(); File(file.baseFile.path + ".bak").delete(); return@withLock }
                val stream = file.startWrite()
                try { stream.write(encode(record).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                catch (error: Exception) { file.failWrite(stream) }
            }
        }
    }

    private fun encode(record: Record) = JSONObject().apply {
        put("version", 1); put("draftId", record.draftId); put("createdAt", record.createdAt)
        put("sessionId", record.sessionId); put("pendingCaptureId", record.pendingCaptureId)
        put("pendingSelection", record.pendingSelection)
        put("content", encodeContent(record.content)); put("reference", encodeContent(record.reference))
    }

    private fun encodeContent(value: FormContent) = JSONObject().apply {
        put("containerIdentifier", value.containerIdentifier); put("isoCode", value.isoCode)
        put("inspectionType", value.inspectionType); put("inspectedAt", value.inspectedAt)
        put("observations", value.observations)
        put("photos", JSONArray().apply { value.photos.forEach { p -> put(JSONObject().put("id", p.id).put("mimeType", p.mimeType).put("byteSize", p.byteSize).put("width", p.width).put("height", p.height)) } })
    }

    private fun decode(json: JSONObject): Record {
        require(json.getInt("version") == 1)
        return Record(json.optString("draftId").takeUnless { it.isNullOrBlank() }, json.getLong("createdAt"), json.getString("sessionId"),
            decodeContent(json.getJSONObject("content")), decodeContent(json.getJSONObject("reference")), json.optString("pendingCaptureId").takeUnless { it.isNullOrBlank() }, json.optBoolean("pendingSelection"))
    }

    private fun decodeContent(json: JSONObject): FormContent {
        val photos = json.getJSONArray("photos").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { com.mucacran.infocontainer.model.DraftPhoto(it.getString("id"), it.getString("mimeType"), it.getLong("byteSize"), it.getInt("width"), it.getInt("height")) } } }
        return FormContent(json.getString("containerIdentifier"), json.getString("isoCode"), json.getString("inspectionType"), json.getString("inspectedAt"), json.getString("observations"), photos)
    }
}
