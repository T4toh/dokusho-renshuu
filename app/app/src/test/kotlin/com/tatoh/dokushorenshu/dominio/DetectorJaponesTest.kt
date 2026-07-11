package com.tatoh.dokushorenshu.dominio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorJaponesTest {
    @Test
    fun `japones puro pasa`() {
        assertTrue(DetectorJapones.pareceJapones("昔々、あるところにおじいさんとおばあさんが住んでいました。"))
    }

    @Test
    fun `ingles puro no pasa`() {
        assertFalse(DetectorJapones.pareceJapones("Once upon a time there was an old man."))
    }

    @Test
    fun `mixto mayormente japones pasa`() {
        assertTrue(DetectorJapones.pareceJapones("私はAndroidが好きです。"))
    }

    @Test
    fun `vacio o solo espacios no pasa`() {
        assertFalse(DetectorJapones.pareceJapones(""))
        assertFalse(DetectorJapones.pareceJapones(" \n　"))
    }

    @Test
    fun `puntuacion japonesa cuenta como japones`() {
        assertTrue(DetectorJapones.pareceJapones("「はい。」"))
    }
}
