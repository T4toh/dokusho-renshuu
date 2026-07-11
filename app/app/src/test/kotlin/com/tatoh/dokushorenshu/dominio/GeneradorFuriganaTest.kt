package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.Furigana
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneradorFuriganaTest {
    companion object {
        private val generador = GeneradorFurigana(Tokenizador())
    }

    @Test
    fun `kanji puro lleva la lectura completa`() {
        // 桃 es un token de un solo kanji → terna [0,1) con lectura もも
        assertEquals(listOf(Furigana(0, 1, "もも")), generador.generar("桃"))
    }

    @Test
    fun `okurigana se recorta del ruby`() {
        // 走った: lectura はしった → ruby はし solo sobre 走 ([0,1))
        val ternas = generador.generar("走った")
        assertEquals(listOf(Furigana(0, 1, "はし")), ternas)
    }

    @Test
    fun `kana puro no lleva terna`() {
        assertEquals(emptyList<Furigana>(), generador.generar("ここにいる"))
    }

    @Test
    fun `katakana no lleva terna`() {
        // el ruby de katakana lo maneja el toggle カナ del lector (Plan 3.7)
        assertEquals(emptyList<Furigana>(), generador.generar("テレビ"))
    }

    @Test
    fun `latin y numeros no llevan terna`() {
        assertEquals(emptyList<Furigana>(), generador.generar("ABC123"))
    }

    @Test
    fun `oracion mixta emite ternas disjuntas dentro de rango`() {
        val texto = "犬が走った。"
        val ternas = generador.generar(texto)
        assertTrue(ternas.isNotEmpty())
        var cursor = 0
        for (f in ternas.sortedBy { it.inicio }) {
            assertTrue("solapado: $f", f.inicio >= cursor)
            assertTrue("fuera de rango: $f", f.fin <= texto.length)
            assertTrue("lectura vacía: $f", f.lectura.isNotEmpty())
            cursor = f.fin
        }
    }

    @Test
    fun `texto vacio no emite nada`() {
        assertEquals(emptyList<Furigana>(), generador.generar(""))
    }
}
