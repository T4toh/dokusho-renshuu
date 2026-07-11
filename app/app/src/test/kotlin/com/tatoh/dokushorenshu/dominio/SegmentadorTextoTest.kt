package com.tatoh.dokushorenshu.dominio

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentadorTextoTest {
    private fun oraciones(texto: String): List<String> =
        SegmentadorTexto.segmentar(texto).map { (ini, fin) -> texto.substring(ini, fin) }

    @Test
    fun `corta en punto japones`() {
        assertEquals(listOf("犬が走る。", "猫が寝る。"), oraciones("犬が走る。猫が寝る。"))
    }

    @Test
    fun `corta en exclamacion e interrogacion`() {
        assertEquals(listOf("走れ！", "なぜ？"), oraciones("走れ！なぜ？"))
    }

    @Test
    fun `dialogo con puntos internos queda como una oracion`() {
        // contrato del Plan 2: 「…。…。」 = 1 oración
        assertEquals(listOf("「おはよう。元気？」と言った。"), oraciones("「おはよう。元気？」と言った。"))
    }

    @Test
    fun `parentesis y comillas dobles tambien anidan`() {
        assertEquals(listOf("彼（先生。偉い人。）が来た。"), oraciones("彼（先生。偉い人。）が来た。"))
        assertEquals(listOf("『本。』を読む。"), oraciones("『本。』を読む。"))
    }

    @Test
    fun `cierre residual se fusiona con la oracion anterior`() {
        // 」 residual tras el corte: span de solo puntuación → fusionar
        // (profundidad ya en 0 cuando aparece el 。 dentro… caso sintético del test Python)
        assertEquals(listOf("行く。」"), oraciones("行く。」"))
    }

    @Test
    fun `interrogacion suelta tras exclamacion se fusiona`() {
        assertEquals(listOf("待て！？"), oraciones("待て！？"))
    }

    @Test
    fun `resto sin puntuacion final es una oracion`() {
        assertEquals(listOf("終わりのない文"), oraciones("終わりのない文"))
    }

    @Test
    fun `texto vacio o solo espacios no produce spans`() {
        assertEquals(emptyList<String>(), oraciones(""))
        assertEquals(emptyList<String>(), oraciones("   　"))
    }

    @Test
    fun `cierre sin apertura no rompe la profundidad`() {
        // max(0, profundidad-1): un 」 huérfano no deja profundidad negativa
        assertEquals(listOf("」変だ。", "次。"), oraciones("」変だ。次。"))
    }
}
