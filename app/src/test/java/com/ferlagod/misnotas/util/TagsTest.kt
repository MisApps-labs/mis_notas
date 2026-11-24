// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagsTest {

    @Test
    fun extractsTagsInOrderWithoutDuplicates() {
        val md = "Ideas #trabajo y #Viaje/2026.\nOtra vez #trabajo, (#casa)"
        assertEquals(listOf("trabajo", "Viaje/2026", "casa"), Tags.extract(md))
    }

    @Test
    fun ignoresHeadingsUrlsCodeAndNumbers() {
        val md = "# Título\n## Otro\nhttps://ej.com/p#ancla a#b #123\n`#codigo`\n```\n#bloque\n```\n#sí"
        assertEquals(listOf("sí"), Tags.extract(md))
    }

    @Test
    fun trailingPunctuationIsNotPartOfTag() {
        assertEquals(listOf("lista"), Tags.extract("Mira #lista- y nada más"))
        assertEquals(listOf("a/b"), Tags.extract("#a/b/"))
    }

    @Test
    fun matchingIgnoresCaseAccentsAndIncludesSubtags() {
        val keys = Tags.extract("#Canción #viaje/2026").map { Tags.key(it) }
        assertTrue(Tags.matches(keys, "cancion"))
        assertTrue(Tags.matches(keys, "#VIAJE"))
        assertFalse(Tags.matches(keys, "via"))
    }
}
