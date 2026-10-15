package com.mucacran.infocontainer.util

import com.mucacran.infocontainer.R
import com.mucacran.infocontainer.model.InspectionDraft
import com.mucacran.infocontainer.model.InspectionType

data class FieldValidationResult(
    val isValid: Boolean,
    val normalizedValue: String,
    val errorMessageRes: Int? = null,
    val errorArgs: Array<out Any> = emptyArray()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FieldValidationResult

        if (isValid != other.isValid) return false
        if (normalizedValue != other.normalizedValue) return false
        if (errorMessageRes != other.errorMessageRes) return false
        if (!errorArgs.contentEquals(other.errorArgs)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isValid.hashCode()
        result = 31 * result + normalizedValue.hashCode()
        result = 31 * result + (errorMessageRes ?: 0)
        result = 31 * result + errorArgs.contentHashCode()
        return result
    }
}

data class DraftValidationResult(
    val containerResult: FieldValidationResult,
    val isoCodeResult: FieldValidationResult,
    val inspectionTypeResult: FieldValidationResult,
    val observationsResult: FieldValidationResult
) {
    val isValid: Boolean
        get() = containerResult.isValid &&
                isoCodeResult.isValid &&
                inspectionTypeResult.isValid &&
                observationsResult.isValid
}

object ContainerValidator {

    private const val MAX_OBSERVATIONS_CODE_POINTS = 10_000

    fun normalizeContainerIdentifier(raw: String): String {
        return raw.replace(" ", "")
            .replace("-", "")
            .uppercase()
    }

    fun getCharIsoValue(ch: Char): Int? {
        return when (ch) {
            '0' -> 0; '1' -> 1; '2' -> 2; '3' -> 3; '4' -> 4
            '5' -> 5; '6' -> 6; '7' -> 7; '8' -> 8; '9' -> 9
            'A' -> 10; 'B' -> 12; 'C' -> 13; 'D' -> 14; 'E' -> 15
            'F' -> 16; 'G' -> 17; 'H' -> 18; 'I' -> 19; 'J' -> 20
            'K' -> 21; 'L' -> 23; 'M' -> 24; 'N' -> 25; 'O' -> 26
            'P' -> 27; 'Q' -> 28; 'R' -> 29; 'S' -> 30; 'T' -> 31
            'U' -> 32; 'V' -> 34; 'W' -> 35; 'X' -> 36; 'Y' -> 37
            'Z' -> 38
            else -> null
        }
    }

    fun calculateIso6346CheckDigit(identifier10Chars: String): Int? {
        if (identifier10Chars.length != 10) return null
        var sum = 0
        var multiplier = 1 // 2^0 = 1
        for (i in 0 until 10) {
            val valChar = getCharIsoValue(identifier10Chars[i]) ?: return null
            sum += valChar * multiplier
            multiplier *= 2 // 2^i
        }
        return (sum % 11) % 10
    }

    fun validateContainerIdentifier(raw: String): FieldValidationResult {
        val normalized = normalizeContainerIdentifier(raw)
        if (normalized.isEmpty()) {
            return FieldValidationResult(
                isValid = false,
                normalizedValue = normalized,
                errorMessageRes = R.string.error_container_empty
            )
        }

        if (normalized.length != 11) {
            return FieldValidationResult(
                isValid = false,
                normalizedValue = normalized,
                errorMessageRes = R.string.error_container_length
            )
        }

        if (normalized[3] != 'U') {
            return FieldValidationResult(
                isValid = false,
                normalizedValue = normalized,
                errorMessageRes = R.string.error_container_category_u
            )
        }

        if (!normalized.matches(Regex("^[A-Z]{3}U[0-9]{7}$"))) {
            return FieldValidationResult(
                isValid = false,
                normalizedValue = normalized,
                errorMessageRes = R.string.error_container_format
            )
        }

        val expectedCheckDigit = calculateIso6346CheckDigit(normalized.substring(0, 10))
        val actualCheckDigit = normalized[10].digitToIntOrNull()

        if (expectedCheckDigit == null || actualCheckDigit == null || actualCheckDigit != expectedCheckDigit) {
            return FieldValidationResult(
                isValid = false,
                normalizedValue = normalized,
                errorMessageRes = R.string.error_container_check_digit,
                errorArgs = arrayOf(expectedCheckDigit ?: -1, actualCheckDigit ?: -1)
            )
        }

        return FieldValidationResult(
            isValid = true,
            normalizedValue = normalized
        )
    }

    fun normalizeIsoCode(raw: String): String {
        return raw.replace(" ", "")
            .replace("-", "")
            .uppercase()
    }

    fun validateIsoCode(raw: String): FieldValidationResult {
        val normalized = normalizeIsoCode(raw)
        if (normalized.isEmpty()) {
            return FieldValidationResult(
                isValid = true,
                normalizedValue = normalized
            )
        }

        if (!normalized.matches(Regex("^[A-Z0-9]{4}$"))) {
            return FieldValidationResult(
                isValid = false,
                normalizedValue = normalized,
                errorMessageRes = R.string.error_iso_code_format
            )
        }

        return FieldValidationResult(
            isValid = true,
            normalizedValue = normalized
        )
    }

    fun countUnicodeCodePoints(text: String): Int {
        return if (text.isEmpty()) 0 else text.codePointCount(0, text.length)
    }

    fun validateObservations(text: String): FieldValidationResult {
        val count = countUnicodeCodePoints(text)
        if (count > MAX_OBSERVATIONS_CODE_POINTS) {
            return FieldValidationResult(
                isValid = false,
                normalizedValue = text,
                errorMessageRes = R.string.error_observations_too_long
            )
        }
        return FieldValidationResult(
            isValid = true,
            normalizedValue = text
        )
    }

    fun validateInspectionType(typeCode: String): FieldValidationResult {
        if (typeCode.isBlank()) {
            return FieldValidationResult(
                isValid = false,
                normalizedValue = typeCode,
                errorMessageRes = R.string.error_inspection_type_required
            )
        }
        if (InspectionType.fromCode(typeCode) == null) {
            return FieldValidationResult(
                isValid = false,
                normalizedValue = typeCode,
                errorMessageRes = R.string.error_inspection_type_invalid
            )
        }
        return FieldValidationResult(
            isValid = true,
            normalizedValue = typeCode
        )
    }

    fun validateDraft(draft: InspectionDraft): DraftValidationResult {
        return DraftValidationResult(
            containerResult = validateContainerIdentifier(draft.containerIdentifier),
            isoCodeResult = validateIsoCode(draft.isoCode),
            inspectionTypeResult = validateInspectionType(draft.inspectionType),
            observationsResult = validateObservations(draft.observations)
        )
    }
}
