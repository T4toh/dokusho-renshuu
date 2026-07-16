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
    fun `parsea catalogo v2 con campos nuevos`() {
        val catalogo = ParserHistoria.parsearCatalogo(recurso("catalogo.json"))
        assertEquals(2, catalogo.version)
        assertEquals(4, catalogo.historias.size)
        val momotaro = catalogo.historias.first { it.id == "momotaro" }
        assertEquals("ももたろう", momotaro.tituloLectura)
        assertEquals("Peach Boy", momotaro.tituloEn)
        assertEquals(217, momotaro.kanjisUnicos)
        assertEquals(174, momotaro.oraciones)
        // urashima_taro cambió de dificultad al regenerarse el catálogo (facil -> media)
        val urashima = catalogo.historias.first { it.id == "urashima_taro" }
        assertEquals("media", urashima.dificultad)
        assertTrue(catalogo.historias.all { it.tamanio > 0 })
    }

    @Test
    fun `titulo_en null se parsea como null`() {
        val json = """{"version":2,"historias":[{"id":"x","titulo":"t","titulo_lectura":"てぃー",
            "titulo_en":null,"autor":"a","dificultad":"facil","tamaño":1,"version":2,
            "kanjis_unicos":1,"oraciones":1}]}"""
        val catalogo = ParserHistoria.parsearCatalogo(json)
        assertNull(catalogo.historias.first().tituloEn)
    }

    @Test
    fun `catalogo con version distinta de 2 lanza`() {
        val json = """{"version":1,"historias":[]}"""
        val e = assertThrows(IllegalArgumentException::class.java) {
            ParserHistoria.parsearCatalogo(json)
        }
        assertTrue(e.message!!.contains("versión"))
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
        val json = """{"version":2,"historias":[{"id":"x","titulo":"t","titulo_lectura":"てぃー",
            "titulo_en":null,"autor":"a","dificultad":"xx","tamaño":1,"version":2,
            "kanjis_unicos":1,"oraciones":1}]}"""
        val e = assertThrows(IllegalArgumentException::class.java) { ParserHistoria.parsearCatalogo(json) }
        assertTrue(e.message!!.contains("dificultad"))
    }

    @Test
    fun `parsea traduccion cuando viene como string`() {
        val json = """{"id":"t","titulo":"T","autor":"A","fuente":"F","licencia":"L",
            "dificultad":"facil","version":2,"parrafos":[{"oraciones":[
            {"texto":"桃太郎は強い。","furigana":[[0,3,"ももたろう"]],"traduccion":"Momotaro is strong."}]}]}"""
        val historia = ParserHistoria.parsear(json)
        assertEquals("Momotaro is strong.", historia.parrafos[0].oraciones[0].traduccion)
    }

    @Test
    fun `traduccion null o ausente parsea como null`() {
        val conNull = """{"id":"t","titulo":"T","autor":"A","fuente":"F","licencia":"L",
            "dificultad":"facil","version":2,"parrafos":[{"oraciones":[
            {"texto":"a","furigana":[],"traduccion":null},{"texto":"b","furigana":[]}]}]}"""
        val oraciones = ParserHistoria.parsear(conNull).parrafos[0].oraciones
        assertNull(oraciones[0].traduccion)
        assertNull(oraciones[1].traduccion)
    }

    @Test
    fun `round-trip serializar-parsear conserva traduccion`() {
        val historia = Historia(
            id = "t", titulo = "T", autor = "A", fuente = "F", licencia = "L",
            dificultad = "facil", version = 2,
            parrafos = listOf(Parrafo(listOf(
                Oracion("桃太郎は強い。", listOf(Furigana(0, 3, "ももたろう")), "Momotaro is strong."),
                Oracion("川へ行った。", emptyList()),  // sin traducción → JsonNull
            ))),
        )
        assertEquals(historia, ParserHistoria.parsear(SerializadorHistoria.serializar(historia)))
    }
}
