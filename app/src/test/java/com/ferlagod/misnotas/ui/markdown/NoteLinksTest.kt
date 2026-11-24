// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLinksTest {

    @Test
    fun parsesWikiLinksWithAndWithoutAlias() {
        val text = "Ver [[Receta de Tarta]] y [[Viaje|el viaje]]."
        val tokens = InlineMarkdown.parse(text).filter { it.kind == InlineKind.WIKILINK }
        assertEquals(2, tokens.size)
        assertEquals(NoteLinks.SCHEME + "Receta de Tarta", tokens[0].url)
        assertEquals("Receta de Tarta", text.substring(tokens[0].contentStart, tokens[0].contentEnd))
        assertEquals("el viaje", text.substring(tokens[1].contentStart, tokens[1].contentEnd))
        assertEquals(setOf("receta de tarta", "viaje"), NoteLinks.targets(text))
    }

    @Test
    fun normalLinksStillWork() {
        val tokens = InlineMarkdown.parse("[web](https://ej.com) [[]] [x]")
        assertTrue(tokens.any { it.kind == InlineKind.LINK })
        assertTrue(tokens.none { it.kind == InlineKind.WIKILINK })
    }

    @Test
    fun linkToEscapesSyntax() {
        assertEquals("[[Plan (v2)- final]]", NoteLinks.linkTo("Plan [v2]| final"))
    }
}
