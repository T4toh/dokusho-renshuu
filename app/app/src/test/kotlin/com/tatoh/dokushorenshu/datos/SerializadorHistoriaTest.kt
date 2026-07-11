package com.tatoh.dokushorenshu.datos

import org.junit.Assert.assertEquals
import org.junit.Test

class SerializadorHistoriaTest {
    @Test
    fun `round trip con historia real de assets`() {
        val crudo = javaClass.classLoader!!
            .getResourceAsStream("momotaro.json")!!.readBytes().decodeToString()
        val historia = ParserHistoria.parsear(crudo)
        assertEquals(historia, ParserHistoria.parsear(SerializadorHistoria.serializar(historia)))
    }

    @Test
    fun `round trip con furigana vacia y campos minimos`() {
        val historia = Historia(
            id = "prueba", titulo = "テスト", autor = "", fuente = "import",
            licencia = "texto del usuario", dificultad = "media", version = 2,
            parrafos = listOf(Parrafo(listOf(Oracion("ここにいる。", emptyList())))),
        )
        assertEquals(historia, ParserHistoria.parsear(SerializadorHistoria.serializar(historia)))
    }

    @Test
    fun `emite traduccion null y no escapa el japones`() {
        val historia = Historia(
            id = "x", titulo = "犬", autor = "", fuente = "import",
            licencia = "texto del usuario", dificultad = "facil", version = 2,
            parrafos = listOf(Parrafo(listOf(Oracion("犬。", listOf(Furigana(0, 1, "いぬ")))))),
        )
        val json = SerializadorHistoria.serializar(historia)
        assertEquals(true, json.contains(""""traduccion":null"""))
        assertEquals(true, json.contains("犬"))  // ensure_ascii=False equivalente
        assertEquals(true, json.contains("""[0,1,"いぬ"]"""))
    }
}
