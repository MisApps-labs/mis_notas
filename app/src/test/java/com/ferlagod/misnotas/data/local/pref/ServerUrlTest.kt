// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.pref

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlTest {

    @Test
    fun plainServerIsKept() {
        assertEquals("https://nube.ejemplo.es", ServerUrl.normalize(" https://nube.ejemplo.es/ "))
    }

    @Test
    fun personalRootGetsAppFolder() {
        assertEquals(
            "https://nube.ejemplo.es/remote.php/dav/files/ana/MisNotas",
            ServerUrl.normalize("https://nube.ejemplo.es/remote.php/dav/files/ana/")
        )
    }

    @Test
    fun chosenSubfolderIsKept() {
        assertEquals(
            "https://nube.ejemplo.es/remote.php/dav/files/ana/Notas",
            ServerUrl.normalize("https://nube.ejemplo.es/remote.php/dav/files/ana/Notas/")
        )
    }

    @Test
    fun genericWebDavAddressesBecomeTheServer() {
        assertEquals("https://nube.ejemplo.es", ServerUrl.normalize("https://nube.ejemplo.es/remote.php/webdav/"))
        assertEquals("https://nube.ejemplo.es/nextcloud", ServerUrl.normalize("https://nube.ejemplo.es/nextcloud/remote.php/dav"))
    }
}
