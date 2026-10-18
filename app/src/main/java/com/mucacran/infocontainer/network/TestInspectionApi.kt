package com.mucacran.infocontainer.network

data class CreatedTestInspection(val containerId: Long, val containerIdentifier: String, val inspectionId: Long)

interface TestInspectionApi {
    suspend fun createTestInspection(username: String, applicationPassword: String): CreatedTestInspection
}
