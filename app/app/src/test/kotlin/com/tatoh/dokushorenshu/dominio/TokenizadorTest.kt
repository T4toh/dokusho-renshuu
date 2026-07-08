package com.tatoh.dokushorenshu.dominio

import org.junit.Assert.*
import org.junit.Test

class TokenizadorTest {
    private val tokenizador = Tokenizador()

    @Test
    fun `spans contiguos y completos`() {
        val texto = "おじいさんは山へしば刈りに行きました。"
        val tokens = tokenizador.tokenizar(texto)
        assertTrue(tokens.isNotEmpty())
        assertEquals(0, tokens.first().inicio)
        assertEquals(texto.length, tokens.last().fin)
        for (i in 1 until tokens.size) {
            assertEquals(tokens[i - 1].fin, tokens[i].inicio)
        }
        assertEquals(texto, tokens.joinToString("") { it.superficie })
    }

    @Test
    fun `sustantivo es contenido con lectura hiragana`() {
        val yama = tokenizador.tokenizar("山へ行く。").first { it.superficie == "山" }
        assertTrue(yama.esContenido)
        assertEquals("やま", yama.lecturaHiragana)
    }

    @Test
    fun `particulas y simbolos no son contenido`() {
        val tokens = tokenizador.tokenizar("山へ行く。")
        assertFalse(tokens.first { it.superficie == "へ" }.esContenido)
        assertFalse(tokens.first { it.superficie == "。" }.esContenido)
    }

    @Test
    fun `verbo conjugado expone forma base`() {
        val tokens = tokenizador.tokenizar("行きました")
        val verbo = tokens.first { it.esContenido }
        assertEquals("行き", verbo.superficie)
        assertEquals("行く", verbo.formaBase)
    }

    @Test
    fun `texto vacio devuelve lista vacia`() {
        assertTrue(tokenizador.tokenizar("").isEmpty())
    }
}
