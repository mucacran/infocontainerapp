package com.mucacran.infocontainer.data

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.UUID

data class TestInspectionAssociation(val containerId: Long, val containerIdentifier: String, val inspectionId: Long)

class TestInspectionStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "test_inspection_association.json")
    private val uncertainFile = File(context.noBackupFilesDir, "test_inspection_creation_uncertain")
    @Synchronized fun hasUncertainAttempt(): Boolean = uncertainFile.isFile
    @Synchronized fun markUncertain() { uncertainFile.writeText("review-required", Charsets.US_ASCII) }
    @Synchronized fun load(): TestInspectionAssociation? = runCatching {
        if (!file.isFile) return null
        Gson().fromJson(file.readText(Charsets.UTF_8), TestInspectionAssociation::class.java).also {
            require(it.containerId > 0 && it.inspectionId > 0 && it.containerIdentifier.isNotBlank())
        }
    }.getOrNull()

    @Synchronized fun save(value: TestInspectionAssociation) {
        require(value.containerId > 0 && value.inspectionId > 0 && value.containerIdentifier.isNotBlank())
        val tmp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        tmp.writeText(Gson().toJson(value), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { tmp.delete(); error("association write failed") }
    }
}
