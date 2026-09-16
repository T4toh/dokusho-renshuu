package com.tatoh.dokushorenshu.dominio.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EscalarRecorteTest {
    @Test
    fun `overlay del mismo tamanio que el bitmap no escala`() {
        val r = escalarRecorte(Recorte(10, 20, 100, 50), 1080, 2400, 1080, 2400)
        assertEquals(Recorte(10, 20, 100, 50), r)
    }

    @Test
    fun `overlay mas chico que el bitmap duplica las coordenadas`() {
        // caso real: el overlay mide menos que la pantalla capturada por las barras
        // de sistema; sin escalar, el recorte cae corrido hacia arriba-izquierda.
        val r = escalarRecorte(Recorte(10, 20, 100, 50), 540, 1200, 1080, 2400)
        assertEquals(Recorte(20, 40, 200, 100), r)
    }

    @Test
    fun `seleccion que se sale del bitmap devuelve null`() {
        assertNull(escalarRecorte(Recorte(1000, 0, 200, 50), 1080, 2400, 1080, 2400))
    }

    @Test
    fun `seleccion degenerada devuelve null`() {
        assertNull(escalarRecorte(Recorte(10, 20, 0, 50), 1080, 2400, 1080, 2400))
        assertNull(escalarRecorte(Recorte(10, 20, 100, 0), 1080, 2400, 1080, 2400))
    }

    @Test
    fun `overlay de tamanio cero devuelve null sin dividir por cero`() {
        assertNull(escalarRecorte(Recorte(10, 20, 100, 50), 0, 0, 1080, 2400))
    }
}

class UnirBloquesTest {
    @Test
    fun `lineas del mismo bloque se concatenan sin separador`() {
        // el japonés no usa espacios; en manga vertical cada columna es una Line
        // del mismo globo y concatenarlas da el orden de lectura.
        val texto = unirBloques(listOf(BloqueOcr(listOf("日本語の", "テキスト"))))
        assertEquals("日本語のテキスト", texto)
    }

    @Test
    fun `bloques distintos se separan con salto de linea`() {
        val texto = unirBloques(listOf(BloqueOcr(listOf("おはよう")), BloqueOcr(listOf("こんばんは"))))
        assertEquals("おはよう\nこんばんは", texto)
    }

    @Test
    fun `bloques vacios o en blanco se descartan`() {
        val texto = unirBloques(
            listOf(BloqueOcr(listOf("あ")), BloqueOcr(listOf("  ")), BloqueOcr(emptyList()), BloqueOcr(listOf("い")))
        )
        assertEquals("あ\nい", texto)
    }

    @Test
    fun `sin bloques devuelve cadena vacia`() {
        assertEquals("", unirBloques(emptyList()))
    }
}
