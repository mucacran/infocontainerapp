package com.mucacran.infocontainer.util

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object DateTimeUtils {

    fun getCurrentIsoDateTime(): String {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    fun parseIsoDateTime(isoString: String): ZonedDateTime {
        return try {
            ZonedDateTime.parse(isoString, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (e: Exception) {
            ZonedDateTime.now()
        }
    }

    fun formatForDisplay(isoString: String): String {
        return try {
            val dateTime = parseIsoDateTime(isoString)
            val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.getDefault())
            dateTime.format(formatter)
        } catch (e: Exception) {
            isoString
        }
    }

    fun showDateTimePicker(
        context: Context,
        currentIsoString: String,
        onDateTimeSelected: (String) -> Unit
    ) {
        val currentDateTime = parseIsoDateTime(currentIsoString)

        val datePickerDialog = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val timePickerDialog = TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        val updatedDateTime = currentDateTime
                            .withYear(year)
                            .withMonth(month + 1)
                            .withDayOfMonth(dayOfMonth)
                            .withHour(hourOfDay)
                            .withMinute(minute)
                            .withSecond(0)
                        
                        onDateTimeSelected(updatedDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    },
                    currentDateTime.hour,
                    currentDateTime.minute,
                    true
                )
                timePickerDialog.show()
            },
            currentDateTime.year,
            currentDateTime.monthValue - 1,
            currentDateTime.dayOfMonth
        )
        datePickerDialog.show()
    }
}
