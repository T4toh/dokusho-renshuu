package com.tatoh.dokushorenshu.datos

import org.junit.Assert.*
import org.junit.Test

class ParserHistoriaTest {
    private fun recurso(nombre: String): String =
        javaClass.classLoader!!.getResourceAsStream(nombre)!!.readBytes().decodeToString()

    @Test
    fun `parsea momotaro real`() {
        val historia = ParserHistoria.parsear(recurso("momotaro.json"))
        assertEquals("momotaro", historia.id)
        assertEquals("桃太郎", historia.titulo)
        assertEquals("楠山正雄", historia.autor)
        assertTrue(historia.parrafos.size > 100)
        // toda furigana en rango y con fin exclusivo
        for (p in historia.parrafos) for (o in p.oraciones) for (f in o.furigana) {
            assertTrue(f.inicio in 0 until f.fin)
            assertTrue(f.fin <= o.texto.length)
            assertTrue(f.lectura.isNotEmpty())
        }
    }

    @Test
    fun `parsea catalogo real`() {
        val catalogo = ParserHistoria.parsearCatalogo(recurso("catalogo.json"))
        assertEquals(1, catalogo.version)
        assertEquals(4, catalogo.historias.size)
        assertTrue(catalogo.historias.any { it.id == "momotaro" && it.dificultad == "facil" })
        assertTrue(catalogo.historias.all { it.tamanio > 0 })
    }

    @Test
    fun `estructura invalida lanza con mensaje claro`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ParserHistoria.parsear("""{"id":"x"}""")
        }
        assertTrue(e.message!!.contains("titulo"))
    }

    @Test
    fun `furigana fuera de rango lanza`() {
        val json = """{"id":"x","titulo":"t","autor":"a","fuente":"f","licencia":"l",
            "dificultad":"facil","version":1,
            "parrafos":[{"oraciones":[{"texto":"ab","furigana":[[0,99,"x"]],"traduccion":null}]}]}"""
        assertThrows(IllegalArgumentException::class.java) { ParserHistoria.parsear(json) }
    }

    @Test
    fun `json roto lanza IllegalArgumentException tambien`() {
        assertThrows(IllegalArgumentException::class.java) { ParserHistoria.parsear("no es json") }
    }

    @Test
    fun `dificultad invalida lanza`() {
        val json = """{"id":"x","titulo":"t","autor":"a","fuente":"f","licencia":"l",
            "dificultad":"imposible","version":1,
            "parrafos":[{"oraciones":[{"texto":"ab","furigana":[[0,1,"あ"]]}]}]}"""
        val e = assertThrows(IllegalArgumentException::class.java) { ParserHistoria.parsear(json) }
        assertTrue(e.message!!.contains("dificultad"))
    }

    @Test
    fun `catalogo dificultad invalida lanza`() {
        val json = """{"version":1,"historias":[{"id":"x","titulo":"t","autor":"a","dificultad":"xx","tamaño":1,"version":1}]}"""
        val e = assertThrows(IllegalArgumentException::class.java) { ParserHistoria.parsearCatalogo(json) }
        assertTrue(e.message!!.contains("dificultad"))
    }
}
