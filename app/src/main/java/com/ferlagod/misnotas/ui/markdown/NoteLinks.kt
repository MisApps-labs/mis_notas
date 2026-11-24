// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.ui.markdown

import com.ferlagod.misnotas.util.TextSearch

/**
 * Enlaces entre notas con la sintaxis de Obsidian: `[[Título]]` o `[[Título|texto]]`.
 * Se resuelven por título, sin distinguir mayúsculas ni acentos. Viven en el Markdown, así que
 * no cambian el formato de sincronización y se conservan al exportar.
 */
object NoteLinks {

    /** Prefijo interno de la dirección de un enlace a nota (nunca se guarda en la nota). */
    const val SCHEME = "misnotas-note:"

    private val LINK = Regex("""\[\[([^\[\]\n|]+)(?:\|[^\[\]\n]*)?]]""")

    fun key(title: String): String = TextSearch.normalize(title.trim())

    /** Títulos enlazados desde [markdown] (claves normalizadas). */
    fun targets(markdown: String): Set<String> =
        if ("[[" !in markdown) emptySet() else LINK.findAll(markdown).mapTo(HashSet()) { key(it.groupValues[1]) }

    /** Enlace a una nota, con los caracteres que romperían la sintaxis sustituidos. */
    fun linkTo(title: String): String =
        "[[" + title.replace('[', '(').replace(']', ')').replace('|', '-').replace('\n', ' ').trim() + "]]"
}
