package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.HistoriasRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportadorHistoriaTest {
    companion object {
        private val generador = GeneradorFurigana(Tokenizador())
    }

    private fun tempDir() = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it }

    private fun repo() = HistoriasRepo(
        leerAsset = { null },
        listarAssetsHistorias = { emptyList() },
        dirDescargas = tempDir(),
        dirImportadas = tempDir(),
    )

    private fun importador(repo: HistoriasRepo = repo()) = ImportadorHistoria(generador, repo)

    @Test
    fun `importa texto en parrafos y oraciones con furigana`() {
        val repo = repo()
        val historia = importador(repo).importar(
            titulo = "犬の話", autor = "", dificultad = "media",
            texto = "犬が走った。猫が寝た。\n\n「おはよう。」と言った。",
        )
        assertEquals(2, historia.parrafos.size)                       // línea en blanco separa párrafos
        assertEquals(2, historia.parrafos[0].oraciones.size)          // dos oraciones
        assertEquals(1, historia.parrafos[1].oraciones.size)          // diálogo = 1 oración
        assertTrue(historia.parrafos[0].oraciones[0].furigana.isNotEmpty())  // 犬 lleva ruby
        assertEquals("import", historia.fuente)
        assertEquals(2, historia.version)
        assertEquals(historia.id, repo.cargarHistoria(historia.id)!!.id)  // quedó persistida
    }

    @Test
    fun `id se deriva del titulo y desambigua colisiones`() {
        val repo = repo()
        val imp = importador(repo)
        val h1 = imp.importar("私の話", "", "facil", "犬。")
        val h2 = imp.importar("私の話", "", "facil", "猫。")
        assertEquals("私の話", h1.id)
        assertEquals("私の話-2", h2.id)
    }

    @Test
    fun `titulo con caracteres ilegales de filesystem se sanitiza`() {
        val h = importador().importar("a/b:c*d?e", "", "media", "犬。")
        assertEquals("a_b_c_d_e", h.id)
    }

    @Test
    fun `entradas invalidas lanzan IllegalArgumentException`() {
        val imp = importador()
        assertThrows(IllegalArgumentException::class.java) { imp.importar("", "", "media", "犬。") }
        assertThrows(IllegalArgumentException::class.java) { imp.importar("t", "", "easy", "犬。") }  // dificultad UI, no schema
        assertThrows(IllegalArgumentException::class.java) { imp.importar("t", "", "media", "   \n  ") }
    }
}
