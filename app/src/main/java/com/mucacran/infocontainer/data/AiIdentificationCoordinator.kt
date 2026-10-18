package com.mucacran.infocontainer.data

import com.mucacran.infocontainer.model.DraftPhoto
import com.mucacran.infocontainer.network.AiApi
import com.mucacran.infocontainer.network.AiExecutionGate
import com.mucacran.infocontainer.network.IdentificationRun
import java.util.UUID

class AiIdentificationCoordinator(private val api: AiApi, private val store: LocalPhotoStore, private val gate: AiExecutionGate) {
    private val requestIds = mutableMapOf<String, String>()
    suspend fun identify(username: String, password: String, photo: DraftPhoto): IdentificationRun {
        check(gate.authorized) { "Real AI execution is disabled" }
        val id = requestIds.getOrPut(photo.id) { UUID.randomUUID().toString() }
        return api.identify(username, password, store.originalForUpload(photo), id)
    }
    suspend fun review(username: String, password: String, runId: Long, action: String, content: Any? = null, resolution: String? = null) =
        api.review(username, password, runId, requestIds.getOrPut("review:$runId:$action") { UUID.randomUUID().toString() }, action, content, resolution)
}
