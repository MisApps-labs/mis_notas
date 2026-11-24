// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.ui.screen

import android.text.format.DateFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ferlagod.misnotas.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Elegir la fecha y la hora de un recordatorio. Si ya hay uno, permite quitarlo.
 * [onConfirm] recibe el instante elegido, o null para quitarlo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDialog(current: Long?, onConfirm: (Long?) -> Unit, onDismiss: () -> Unit) {
    val initial = remember {
        Calendar.getInstance().apply {
            if (current != null) timeInMillis = current
            else {
                add(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 0)
            }
        }
    }
    // El DatePicker trabaja en UTC: se le pasa la fecha local como medianoche UTC.
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    )
    val context = LocalContext.current
    val timeState = rememberTimePickerState(
        initialHour = initial.get(Calendar.HOUR_OF_DAY),
        initialMinute = initial.get(Calendar.MINUTE),
        is24Hour = DateFormat.is24HourFormat(context)
    )
    var pickingTime by remember { mutableStateOf(false) }

    if (!pickingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { pickingTime = true }, enabled = dateState.selectedDateMillis != null) {
                    Text(stringResource(R.string.next))
                }
            },
            dismissButton = {
                if (current != null) {
                    TextButton(onClick = { onConfirm(null) }) { Text(stringResource(R.string.reminder_remove)) }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        ) {
            DatePicker(state = dateState)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.reminder_title)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateState.selectedDateMillis ?: 0L }
                    val at = Calendar.getInstance().apply {
                        clear()
                        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), timeState.hour, timeState.minute)
                    }.timeInMillis
                    onConfirm(at)
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.cd_back)) } }
        )
    }
}

fun formatReminder(time: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEdMMMjmm"), locale).format(Date(time))
}
