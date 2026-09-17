package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.RecortesRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CreadorRecortesTest {

    @get:Rule val carpeta = TemporaryFolder()

    companion object {
        // Kuromoji carga IPADIC y pica en ~1 GB (ver maxHeapSize en build.gradle.kts):
        // una sola instancia para toda la clase, nunca una por test.
        private val tokenizador = Tokenizador()
    }

    private fun creador() = CreadorRecortes(
        GeneradorFurigana(tokenizador),
        RecortesRepo(carpeta.root),
    )

    @Test
    fun `una linea con dos oraciones produce un parrafo con dos oraciones`() {
        val r = creador().crear("今日はいい天気です。明日も晴れます。")
        assertEquals(1, r.parrafos.size)
        assertEquals(2, r.parrafos[0].oraciones.size)
    }

    @Test
    fun `dos lineas producen dos parrafos`() {
        val r = creador().crear("一行目です。\n二行目です。")
        assertEquals(2, r.parrafos.size)
    }

    @Test
    fun `las lineas vacias se descartan`() {
        val r = creador().crear("一行目です。\n\n  \n二行目です。")
        assertEquals(2, r.parrafos.size)
    }

    @Test
    fun `genera furigana sobre los kanji`() {
        val r = creador().crear("天気がいい。")
        assertTrue("debería anotar la lectura de 天気", r.parrafos[0].oraciones[0].furigana.isNotEmpty())
    }

    @Test
    fun `conserva el texto crudo tal cual`() {
        val texto = "一行目です。\n二行目です。"
        assertEquals(texto, creador().crear(texto).texto)
    }

    @Test
    fun `texto sin contenido lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { creador().crear("   \n  ") }
    }

    @Test
    fun `dos recortes seguidos no comparten id`() {
        val c = creador()
        assertTrue(c.crear("あ。").id != c.crear("い。").id)
    }

    @Test
    fun `el recorte queda persistido`() {
        val repo = RecortesRepo(carpeta.root)
        val creado = CreadorRecortes(GeneradorFurigana(tokenizador), repo).crear("天気。")
        assertEquals(creado, repo.cargar(creado.id))
    }
}
