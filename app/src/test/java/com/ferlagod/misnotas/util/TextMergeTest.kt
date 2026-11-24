// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextMergeTest {

    private val base = "Compra\n- pan\n- leche\n- huevos\n\nNotas finales"

    @Test
    fun changesInDifferentLinesAreCombined() {
        val local = "Compra\n- pan integral\n- leche\n- huevos\n\nNotas finales"
        val remote = "Compra\n- pan\n- leche\n- huevos\n- café\n\nNotas finales"
        assertEquals("Compra\n- pan integral\n- leche\n- huevos\n- café\n\nNotas finales", TextMerge.merge3(base, local, remote))
    }

    @Test
    fun additionsAtBothEndsAreCombined() {
        val local = "Arriba\n$base"
        val remote = "$base\nAbajo"
        assertEquals("Arriba\n$base\nAbajo", TextMerge.merge3(base, local, remote))
    }

    @Test
    fun deletionAndEditElsewhere() {
        val local = "Compra\n- pan\n- huevos\n\nNotas finales"
        val remote = "Compra\n- pan\n- leche\n- huevos\n\nNotas finales actualizadas"
        assertEquals("Compra\n- pan\n- huevos\n\nNotas finales actualizadas", TextMerge.merge3(base, local, remote))
    }

    @Test
    fun sameLineChangedDifferentlyIsAConflict() {
        assertNull(TextMerge.merge3(base, base.replace("leche", "leche entera"), base.replace("leche", "leche sin lactosa")))
    }

    @Test
    fun identicalChangesAreNotAConflict() {
        val both = base.replace("pan", "pan de molde")
        assertEquals(both, TextMerge.merge3(base, both, both))
    }

    @Test
    fun taskToggledOnBothSidesInDifferentItems() {
        val b = "- [ ] uno\n- [ ] dos\n- [ ] tres"
        assertEquals("- [x] uno\n- [ ] dos\n- [x] tres", TextMerge.merge3(b, "- [x] uno\n- [ ] dos\n- [ ] tres", "- [ ] uno\n- [ ] dos\n- [x] tres"))
    }

    @Test
    fun titles() {
        assertEquals("Nuevo", TextMerge.mergeLine("Viejo", "Nuevo", "Viejo"))
        assertNull(TextMerge.mergeLine("Viejo", "A", "B"))
    }
}
