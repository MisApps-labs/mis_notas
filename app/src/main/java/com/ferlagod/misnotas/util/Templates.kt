// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Plantillas: notas de la carpeta elegida en Ajustes que sirven de punto de partida.
 * Al usarlas se sustituyen `{{date}}`/`{{fecha}}`, `{{time}}`/`{{hora}}` y `{{datetime}}`.
 */
object Templates {

    private val PLACEHOLDER = Regex("""\{\{\s*(date|fecha|time|hora|datetime|fechahora)\s*}}""", RegexOption.IGNORE_CASE)

    fun fill(text: String, now: Date = Date(), locale: Locale = Locale.getDefault()): String =
        PLACEHOLDER.replace(text) { m ->
            when (m.groupValues[1].lowercase()) {
                "date", "fecha" -> DateFormat.getDateInstance(DateFormat.LONG, locale).format(now)
                "time", "hora" -> DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(now)
                else -> DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT, locale).format(now)
            }
        }
}
