// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.data.local.pref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun missingSchemeBecomesHttps() {
        assertEquals("https://nube.ejemplo.es", ServerUrl.normalize("nube.ejemplo.es"))
        assertEquals("http://192.168.1.10:8080/dav", ServerUrl.normalizeWebDav("http://192.168.1.10:8080/dav/"))
    }

    @Test
    fun localHostsAreRecognised() {
        listOf("localhost", "192.168.1.10", "10.0.0.2", "172.20.1.1", "100.101.102.103", "nas.local", "nas.lan", "nas", "fd12::1")
            .forEach { assertTrue(it, ServerUrl.isLocalHost(it)) }
        listOf("nube.ejemplo.es", "8.8.8.8", "172.32.0.1", "192.169.0.1", "100.128.0.1", "2001:db8::1")
            .forEach { assertFalse(it, ServerUrl.isLocalHost(it)) }
    }

    @Test
    fun httpOnlyAllowedOnLocalNetwork() {
        assertTrue(ServerUrl.isAllowed("https://nube.ejemplo.es"))
        assertTrue(ServerUrl.isAllowed("http://192.168.1.10/dav"))
        assertTrue(ServerUrl.isAllowed("http://[fd12::1]:8080/dav"))
        assertFalse(ServerUrl.isAllowed("http://nube.ejemplo.es"))
        assertFalse(ServerUrl.isAllowed("ftp://192.168.1.10"))
    }
}
