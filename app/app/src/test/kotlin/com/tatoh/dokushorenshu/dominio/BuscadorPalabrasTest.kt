package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.DiccionarioFake
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.OracionEjemplo
import com.tatoh.dokushorenshu.datos.Palabra
import org.junit.Assert.*
import org.junit.Test

private fun token(superficie: String, base: String? = null, lectura: String? = null) =
    PalabraToken(superficie, lectura, base, 0, superficie.length, esContenido = true)

private fun palabra(termino: String) = Palabra(termino, "よみ", listOf("meaning"), emptyList(), 1)

class BuscadorPalabrasTest {
    @Test
    fun `palabra directa con ejemplos de oracion_palabra`() {
        val dic = DiccionarioFake()
        dic.palabras["物語"] = listOf(palabra("物語"))
        dic.ejemplosPalabra["物語"] = listOf(OracionEjemplo("これは物語です。", "This is a tale."))
        dic.kanjis["物"] = KanjiInfo("物", listOf("thing"), emptyList(), emptyList(), null, 8)
        dic.kanjis["語"] = KanjiInfo("語", listOf("word"), emptyList(), emptyList(), 4, 14)

        val consulta = BuscadorPalabras(dic).consultar(token("物語"))
        assertFalse(consulta.sinDefinicion)
        assertEquals(1, consulta.ejemplos.size)
        assertEquals(listOf("物", "語"), consulta.kanjis.map { it.kanji })
    }

    @Test
    fun `verbo conjugado cae a forma base`() {
        val dic = DiccionarioFake()
        dic.palabras["行く"] = listOf(palabra("行く"))
        val consulta = BuscadorPalabras(dic).consultar(token("行き", base = "行く"))
        assertFalse(consulta.sinDefinicion)
        assertEquals("行く", consulta.definiciones[0].termino)
    }

    @Test
    fun `palabra de un kanji usa oracion_kanji`() {
        val dic = DiccionarioFake()
        dic.palabras["山"] = listOf(palabra("山"))
        dic.ejemplosKanji["山"] = listOf(OracionEjemplo("山へ行く。", "Go to the mountain."))
        val consulta = BuscadorPalabras(dic).consultar(token("山"))
        assertEquals(1, consulta.ejemplos.size)  // fallback a oracion_kanji (contrato 2-6 chars)
    }

    @Test
    fun `sin definicion nunca vacio - lleva lectura de kuromoji`() {
        val consulta = BuscadorPalabras(DiccionarioFake())
            .consultar(token("ばあ", lectura = "ばあ"))
        assertTrue(consulta.sinDefinicion)
        assertEquals("ばあ", consulta.lecturaFallback)
        assertTrue(consulta.ejemplos.isEmpty())
    }

    @Test
    fun `termino sin ejemplos propios cae a kanji`() {
        val dic = DiccionarioFake()
        dic.palabras["洗濯"] = listOf(palabra("洗濯"))
        dic.ejemplosKanji["洗"] = listOf(OracionEjemplo("洗う。", "Wash."))
        val consulta = BuscadorPalabras(dic).consultar(token("洗濯"))
        assertEquals("洗う。", consulta.ejemplos[0].japones)
    }

    @Test
    fun `palabra en kana cae a busqueda por lectura`() {
        val dic = DiccionarioFake()
        dic.palabras["お祖父さん"] = listOf(palabra("お祖父さん").copy(lectura = "おじいさん"))
        val consulta = BuscadorPalabras(dic).consultar(token("おじいさん", lectura = "おじいさん"))
        assertFalse(consulta.sinDefinicion)
        assertEquals("お祖父さん", consulta.definiciones[0].termino)
    }
}
