package com.tatoh.dokushorenshu.datos

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DiccionarioSqliteTest {
    private fun abrirFixture(): DiccionarioSqlite {
        // el recurso se copia a un archivo temporal porque SQLite necesita ruta real
        val destino = File.createTempFile("dic_test", ".db")
        destino.deleteOnExit()
        javaClass.classLoader!!.getResourceAsStream("diccionario_test.db")!!.use { entrada ->
            destino.outputStream().use { salida -> entrada.copyTo(salida) }
        }
        return DiccionarioSqlite.desdeArchivo(destino.path)
    }

    @Test
    fun `buscar palabra parsea listas json`() {
        val resultados = abrirFixture().buscarPalabra("物語")
        assertEquals(1, resultados.size)
        assertEquals("ものがたり", resultados[0].lectura)
        assertEquals(listOf("tale", "story (long)"), resultados[0].significados)
    }

    @Test
    fun `palabra inexistente devuelve lista vacia`() {
        assertTrue(abrirFixture().buscarPalabra("存在しない").isEmpty())
    }

    @Test
    fun `buscar kanji con jlpt y sin jlpt`() {
        val dic = abrirFixture()
        val go = dic.buscarKanji("語")!!
        assertEquals(4, go.jlpt)
        assertEquals(listOf("ゴ"), go.onYomi)
        assertNull(dic.buscarKanji("物")!!.jlpt)
        assertNull(dic.buscarKanji("犬"))
    }

    @Test
    fun `oraciones de palabra y de kanji`() {
        val dic = abrirFixture()
        assertEquals("これは物語です。", dic.oracionesDePalabra("物語")[0].japones)
        assertEquals("これは物語です。", dic.oracionesDeKanji("語")[0].japones)
        assertTrue(dic.oracionesDeKanji("犬").isEmpty())
    }
}
