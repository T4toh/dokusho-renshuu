package com.tatoh.dokushorenshu.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    private fun v(s: String) = Version.parse(s) ?: error("no parsea: $s")

    @Test
    fun `parsea con y sin v, con y sin prerelease`() {
        assertEquals(Version(0, 1, 0), Version.parse("0.1.0"))
        assertEquals(Version(0, 1, 0), Version.parse("v0.1.0"))
        assertEquals(Version(0, 1, 0, listOf("beta", "4")), Version.parse("v0.1.0-beta.4"))
        assertEquals(Version(1, 2, 3, listOf("rc", "1")), Version.parse(" 1.2.3-rc.1 "))
    }

    @Test
    fun `rechaza lo que no es una version de la app`() {
        assertNull(Version.parse("db-v2"))
        assertNull(Version.parse(""))
        assertNull(Version.parse("1.2"))
        assertNull(Version.parse("1.2.3+4"))   // metadata de build: no se usa en este repo
        assertNull(Version.parse("1.2.3-"))
    }

    @Test
    fun `orden por major minor patch`() {
        assertTrue(v("1.0.0") > v("0.9.9"))
        assertTrue(v("0.2.0") > v("0.1.9"))
        assertTrue(v("0.1.1") > v("0.1.0"))
        assertEquals(0, v("0.1.0").compareTo(v("v0.1.0")))
    }

    @Test
    fun `sin prerelease es mayor que con prerelease`() {
        assertTrue(v("0.1.0") > v("0.1.0-beta.9"))
        assertTrue(v("0.1.0-beta.1") < v("0.1.0"))
    }

    @Test
    fun `prereleases se comparan por identificador`() {
        assertTrue(v("0.1.0-beta.5") > v("0.1.0-beta.4"))
        assertTrue(v("0.1.0-beta.10") > v("0.1.0-beta.9"))   // numérico, no lexicográfico
        assertTrue(v("1.2.0-rc.1") > v("1.2.0-beta.3"))     // alfanumérico
        assertTrue(v("1.0.0-alpha.1") > v("1.0.0-alpha"))   // prefijo más corto es menor
        assertTrue(v("1.0.0-alpha.beta") > v("1.0.0-alpha.1")) // numérico < alfanumérico
    }

    @Test
    fun `toString vuelve al texto sin v`() {
        assertEquals("0.1.0-beta.5", v("v0.1.0-beta.5").toString())
        assertEquals("1.0.0", v("1.0.0").toString())
    }
}
