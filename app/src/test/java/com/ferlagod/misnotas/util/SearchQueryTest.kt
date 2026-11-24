// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    private fun hay(text: String) = TextSearch.normalize(text)

    @Test
    fun parsesOperators() {
        val q = SearchQuery.parse("""receta "tarta de queso" -horno #Cocina""")
        assertEquals(listOf("receta", "tarta de queso"), q.terms)
        assertEquals(listOf("horno"), q.excluded)
        assertEquals(listOf("cocina"), q.tags)
    }

    @Test
    fun matchesSubstringsPhrasesExclusionsAndTags() {
        val q = SearchQuery.parse("""ota "de queso" -horno #cocina""")
        assertTrue(q.matches(hay("Nota: tarta DE QUÉSO"), setOf("cocina/postres")))
        assertFalse(q.matches(hay("Nota: tarta de queso al horno"), setOf("cocina")))
        assertFalse(q.matches(hay("Nota: tarta de queso"), setOf("viajes")))
        assertFalse(q.matches(hay("Nota: tarta queso"), setOf("cocina")))
    }

    @Test
    fun emptyAndLoneDash() {
        assertTrue(SearchQuery.parse("   ").isEmpty)
        assertEquals(listOf("-"), SearchQuery.parse("a - b").terms.filter { it == "-" })
    }
}
