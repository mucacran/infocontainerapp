package com.mucacran.infocontainer.model

import androidx.annotation.StringRes
import com.mucacran.infocontainer.R

enum class InspectionType(
    val code: String,
    @StringRes val labelRes: Int
) {
    INVENTORY("inventory", R.string.type_inventory),
    SALE("sale", R.string.type_sale),
    RECEIPT("receipt", R.string.type_receipt),
    DELIVERY("delivery", R.string.type_delivery);

    companion object {
        fun fromCode(code: String?): InspectionType? {
            if (code.isNull_or_blank()) return null
            return entries.find { it.code.equals(code, ignoreCase = true) }
        }
    }
}

private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()
