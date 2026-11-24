// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Date
import java.util.Locale

class TemplatesTest {

    @Test
    fun placeholdersAreReplaced() {
        val out = Templates.fill("Diario {{fecha}} a las {{ HORA }} — {{otra}}", Date(0), Locale.ROOT)
        assertFalse(out.contains("{{fecha}}"))
        assertFalse(out.contains("HORA"))
        assertEquals(true, out.endsWith("{{otra}}"))
    }
}
