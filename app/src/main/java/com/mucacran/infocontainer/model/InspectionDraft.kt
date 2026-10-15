package com.mucacran.infocontainer.model

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class InspectionDraft(
    val id: String = UUID.randomUUID().toString(),
    val containerIdentifier: String = "",
    val isoCode: String = "",
    val inspectionType: String = "",
    val inspectedAt: String = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    val observations: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val photos: List<DraftPhoto> = emptyList()
)
