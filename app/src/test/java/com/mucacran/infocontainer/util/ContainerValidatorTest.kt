package com.mucacran.infocontainer.util

import com.mucacran.infocontainer.R
import com.mucacran.infocontainer.model.InspectionDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerValidatorTest {

    @Test
    fun normalizeContainerIdentifier_removesSpacesDashesAndConvertsToUpper() {
        assertEquals("CSQU3054383", ContainerValidator.normalizeContainerIdentifier("  csq u 305438-3 "))
        assertEquals("ABCU0012345", ContainerValidator.normalizeContainerIdentifier("abc-u-001234-5"))
        assertEquals("CSQU3054383", ContainerValidator.normalizeContainerIdentifier("CSQU-305438-3"))
    }

    @Test
    fun normalizeContainerIdentifier_preservesLeadingZerosAndDoesNotReplaceChars() {
        val raw = "000u0000000"
        val normalized = ContainerValidator.normalizeContainerIdentifier(raw)
        assertEquals("000U0000000", normalized)
        assertEquals('0', normalized[0])
        assertEquals('0', normalized[1])
    }

    @Test
    fun calculateIso6346CheckDigit_correctForValidCodes() {
        // CSQU305438 -> Expected digit 3
        val digit1 = ContainerValidator.calculateIso6346CheckDigit("CSQU305438")
        assertEquals(3, digit1)

        // MSCU123456 -> Expected digit 6
        val digit2 = ContainerValidator.calculateIso6346CheckDigit("MSCU123456")
        assertEquals(6, digit2)
    }

    @Test
    fun validateContainerIdentifier_validCodes() {
        val result1 = ContainerValidator.validateContainerIdentifier("CSQU3054383")
        assertTrue(result1.isValid)
        assertEquals("CSQU3054383", result1.normalizedValue)
        assertNull(result1.errorMessageRes)

        val result2 = ContainerValidator.validateContainerIdentifier(" msc u 123456-6 ")
        assertTrue(result2.isValid)
        assertEquals("MSCU1234566", result2.normalizedValue)
    }

    @Test
    fun validateContainerIdentifier_invalidCheckDigit() {
        val result = ContainerValidator.validateContainerIdentifier("CSQU3054389")
        assertFalse(result.isValid)
        assertEquals("CSQU3054389", result.normalizedValue)
        assertEquals(R.string.error_container_check_digit, result.errorMessageRes)
        assertEquals(3, result.errorArgs[0]) // Expected check digit
        assertEquals(9, result.errorArgs[1]) // Actual check digit
    }

    @Test
    fun validateContainerIdentifier_invalidCategoryOrFormat() {
        // Missing 'U' as 4th char
        val resultCategory = ContainerValidator.validateContainerIdentifier("CSQA3054383")
        assertFalse(resultCategory.isValid)
        assertEquals(R.string.error_container_category_u, resultCategory.errorMessageRes)

        // Too short
        val resultShort = ContainerValidator.validateContainerIdentifier("CSQU305438")
        assertFalse(resultShort.isValid)
        assertEquals(R.string.error_container_length, resultShort.errorMessageRes)

        // Non-digit serial
        val resultAlphaSerial = ContainerValidator.validateContainerIdentifier("CSQU30543AB")
        assertFalse(resultAlphaSerial.isValid)
        assertEquals(R.string.error_container_format, resultAlphaSerial.errorMessageRes)
    }

    @Test
    fun validateIsoCode_validAndInvalidCases() {
        // Empty / optional
        val emptyResult = ContainerValidator.validateIsoCode("   ")
        assertTrue(emptyResult.isValid)
        assertEquals("", emptyResult.normalizedValue)

        // Valid 4-character ISO
        val validResult = ContainerValidator.validateIsoCode(" 45-g1 ")
        assertTrue(validResult.isValid)
        assertEquals("45G1", validResult.normalizedValue)

        // Invalid ISO (5 chars)
        val invalidResult = ContainerValidator.validateIsoCode("45G12")
        assertFalse(invalidResult.isValid)
        assertEquals(R.string.error_iso_code_format, invalidResult.errorMessageRes)
    }

    @Test
    fun countUnicodeCodePoints_handlesEmojiAndSurrogatePairs() {
        val asciiText = "Container 123"
        assertEquals(13, ContainerValidator.countUnicodeCodePoints(asciiText))

        // Emoji 🚢 is 1 code point, but 2 Char units in UTF-16
        val emojiText = "🚢"
        assertEquals(2, emojiText.length)
        assertEquals(1, ContainerValidator.countUnicodeCodePoints(emojiText))

        val mixedText = "Contenedor 🚢 📦"
        assertEquals(14, ContainerValidator.countUnicodeCodePoints(mixedText))
    }

    @Test
    fun validateObservations_unicodeLimitEnforcement() {
        // Exactly 10,000 code points
        val text10k = "a".repeat(10000)
        val validResult = ContainerValidator.validateObservations(text10k)
        assertTrue(validResult.isValid)

        // 10,001 code points
        val text10k1 = "a".repeat(10001)
        val invalidResult = ContainerValidator.validateObservations(text10k1)
        assertFalse(invalidResult.isValid)
        assertEquals(R.string.error_observations_too_long, invalidResult.errorMessageRes)
    }

    @Test
    fun validateDraft_fullValidation() {
        val validDraft = InspectionDraft(
            containerIdentifier = "CSQU3054383",
            isoCode = "45G1",
            inspectionType = "inventory",
            observations = "OK"
        )
        val validResult = ContainerValidator.validateDraft(validDraft)
        assertTrue(validResult.isValid)

        val invalidDraft = InspectionDraft(
            containerIdentifier = "INVALID_ID",
            isoCode = "",
            inspectionType = "",
            observations = ""
        )
        val invalidResult = ContainerValidator.validateDraft(invalidDraft)
        assertFalse(invalidResult.isValid)
        assertFalse(invalidResult.containerResult.isValid)
        assertFalse(invalidResult.inspectionTypeResult.isValid)
    }
}
