// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.util

/**
 * Etiquetas: palabras con `#` dentro del propio texto de la nota (`#trabajo`, `#viaje/2026`),
 * como en Obsidian o Bear. Al vivir en el Markdown no cambian el formato de sincronización, las
 * ve cualquier otro cliente y se conservan al exportar.
 *
 * Reglas:
 * - Empieza por `#` seguido de una letra; sigue con letras, números, `_`, `-` y `/` (subetiquetas).
 * - Delante solo puede haber el inicio de línea, un espacio o `(`: así no cuentan `a#b`, las
 *   anclas de una URL ni los títulos (`# Título`, con espacio).
 * - No cuentan dentro de bloques de código ni de `código en línea`.
 * - Se comparan sin mayúsculas ni acentos: `#Canción` y `#cancion` son la misma.
 */
object Tags {

    private fun isTagChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == '/'

    /**
     * Si en text[i] empieza una etiqueta (dentro de [from, to)), devuelve dónde acaba; si no, -1.
     */
    fun tagEndAt(text: String, i: Int, from: Int = 0, to: Int = text.length): Int {
        if (i >= to || text[i] != '#') return -1
        if (i > from) {
            val prev = text[i - 1]
            if (!prev.isWhitespace() && prev != '(') return -1
        }
        if (i + 1 >= to || !text[i + 1].isLetter()) return -1
        var end = i + 1
        while (end < to && isTagChar(text[end])) end++
        while (end > i + 1 && (text[end - 1] == '/' || text[end - 1] == '-')) end--
        return end
    }

    /** Etiquetas de [markdown] tal como están escritas (sin `#`), en orden de aparición y sin repetir. */
    fun extract(markdown: String): List<String> {
        if ('#' !in markdown) return emptyList()
        val seen = LinkedHashMap<String, String>()
        var inFence = false
        for (line in markdown.lineSequence()) {
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence || '#' !in line) continue
            var i = 0
            var inCode = false
            while (i < line.length) {
                val c = line[i]
                if (c == '`') inCode = !inCode
                if (!inCode && c == '#') {
                    val end = tagEndAt(line, i)
                    if (end > 0) {
                        val tag = line.substring(i + 1, end)
                        seen.putIfAbsent(key(tag), tag)
                        i = end
                        continue
                    }
                }
                i++
            }
        }
        return seen.values.toList()
    }

    /** Clave de comparación: sin acentos, en minúsculas. */
    fun key(tag: String): String = TextSearch.normalize(tag.removePrefix("#"))

    /** ¿La nota con estas etiquetas tiene [wanted]? Incluye subetiquetas: `#viaje` encuentra `#viaje/2026`. */
    fun matches(noteTagKeys: Collection<String>, wanted: String): Boolean {
        val w = key(wanted)
        return noteTagKeys.any { it == w || it.startsWith("$w/") }
    }
}
