package com.mucacran.infocontainer.util

import com.mucacran.infocontainer.ui.FormContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsavedChangesDetectionTest {

    @Test
    fun formContent_detectsChangeFromAaToBB_preventingHashCollision() {
        val initialContent = FormContent(observations = "Aa")
        val modifiedContent = FormContent(observations = "BB")

        // "Aa".hashCode() == "BB".hashCode() en JVM/Java (2112)
        // La comparación por objeto de datos (FormContent) no usa solo el hash, sino igualdad de campos.
        val hasUnsavedChanges = initialContent != modifiedContent
        assertTrue("Cambiar 'Aa' por 'BB' debe detectarse como un cambio no guardado.", hasUnsavedChanges)
    }

    @Test
    fun formContent_equalsWhenAllFieldsMatch() {
        val content1 = FormContent(
            containerIdentifier = "CSQU3054383",
            isoCode = "45G1",
            inspectionType = "inventory",
            inspectedAt = "2025-02-23T10:00:00-05:00",
            observations = "Inspección inicial"
        )
        val content2 = FormContent(
            containerIdentifier = "CSQU3054383",
            isoCode = "45G1",
            inspectionType = "inventory",
            inspectedAt = "2025-02-23T10:00:00-05:00",
            observations = "Inspección inicial"
        )

        assertFalse(content1 != content2)
    }

    @Test
    fun formContent_detectsFieldModifications() {
        val base = FormContent(
            containerIdentifier = "CSQU3054383",
            inspectionType = "inventory"
        )

        assertTrue(base != base.copy(containerIdentifier = "CSQU3054384"))
        assertTrue(base != base.copy(isoCode = "22G1"))
        assertTrue(base != base.copy(inspectionType = "sale"))
        assertTrue(base != base.copy(inspectedAt = "2026-01-01T00:00:00Z"))
        assertTrue(base != base.copy(observations = "Nuevas observaciones"))
    }
}
