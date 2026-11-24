// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.util

/**
 * Consulta de búsqueda con operadores:
 * - `palabra`: debe aparecer (en cualquier parte, también dentro de otra palabra).
 * - `"frase exacta"`: debe aparecer tal cual.
 * - `-palabra` o `-"frase"`: no debe aparecer.
 * - `#etiqueta`: la nota debe tener esa etiqueta (o una subetiqueta suya).
 *
 * Todo sin distinguir mayúsculas ni acentos, como [TextSearch].
 */
data class SearchQuery(
    val terms: List<String>,
    val excluded: List<String>,
    val tags: List<String>
) {
    val isEmpty: Boolean get() = terms.isEmpty() && excluded.isEmpty() && tags.isEmpty()

    /** Textos que se resaltan en los resultados. */
    val highlights: List<String> get() = terms

    /** [haystack] ya normalizado con [TextSearch.normalize]; [tagKeys] con [Tags.key]. */
    fun matches(haystack: String, tagKeys: Collection<String>): Boolean =
        terms.all { haystack.contains(it) } &&
            excluded.none { haystack.contains(it) } &&
            tags.all { Tags.matches(tagKeys, it) }

    companion object {
        val EMPTY = SearchQuery(emptyList(), emptyList(), emptyList())

        fun parse(raw: String): SearchQuery {
            val terms = mutableListOf<String>()
            val excluded = mutableListOf<String>()
            val tags = mutableListOf<String>()
            var i = 0
            val s = raw.trim()
            while (i < s.length) {
                if (s[i].isWhitespace()) { i++; continue }
                var negate = false
                if (s[i] == '-' && i + 1 < s.length && !s[i + 1].isWhitespace()) {
                    negate = true
                    i++
                }
                val token: String
                val quoted = s[i] == '"'
                if (quoted) {
                    val close = s.indexOf('"', i + 1)
                    token = if (close < 0) s.substring(i + 1) else s.substring(i + 1, close)
                    i = if (close < 0) s.length else close + 1
                } else {
                    var end = i
                    while (end < s.length && !s[end].isWhitespace()) end++
                    token = s.substring(i, end)
                    i = end
                }
                if (token.isBlank()) continue
                val isTag = !quoted && token.length > 1 && token.startsWith("#") && token[1].isLetter()
                when {
                    isTag && !negate -> tags += Tags.key(token)
                    negate -> excluded += TextSearch.normalize(token)
                    else -> terms += TextSearch.normalize(token)
                }
            }
            return SearchQuery(terms, excluded, tags)
        }
    }
}
