package com.tatoh.dokushorenshu.datos.progreso

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProgresoDaoTest {
    private fun db(): ProgresoDb = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), ProgresoDb::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun `progreso se guarda y actualiza`() = runTest {
        val dao = db().dao()
        assertNull(dao.progreso("momotaro"))
        dao.guardarProgreso(ProgresoHistoria("momotaro", parrafo = 3, oracion = 1))
        dao.guardarProgreso(ProgresoHistoria("momotaro", parrafo = 5, oracion = 0))
        assertEquals(5, dao.progreso("momotaro")!!.parrafo)
        assertEquals(1, dao.todos().size)
    }

    @Test
    fun `palabra tocada dos veces no duplica`() = runTest {
        val dao = db().dao()
        dao.registrarPalabra(PalabraTocada("momotaro", "洗濯", timestamp = 1L))
        dao.registrarPalabra(PalabraTocada("momotaro", "洗濯", timestamp = 2L))
        val palabras = dao.palabrasDe("momotaro")
        assertEquals(1, palabras.size)
        assertEquals(2L, palabras[0].timestamp)  // upsert actualiza
    }

    @Test
    fun `prefs roundtrip y default de furigana`() = runTest {
        val repo = PrefsRepo(db().dao())
        assertTrue(repo.furiganaActiva())  // default: activada
        repo.setFuriganaActiva(false)
        assertFalse(repo.furiganaActiva())
    }

    @Test
    fun `prefs roundtrip y default de katakana`() = runTest {
        val repo = PrefsRepo(db().dao())
        assertTrue(repo.katakanaActiva())  // default: activada
        repo.setKatakanaActiva(false)
        assertFalse(repo.katakanaActiva())
    }

    @Test
    fun `reabrir un kanji taggeado preserva la dificultad y actualiza el timestamp`() = runTest {
        val dao = db().dao()
        dao.registrarAperturaKanji("洗", 1L)
        dao.setDificultadKanji("洗", "hard")
        dao.registrarAperturaKanji("洗", 2L)

        val tocado = dao.kanjiTocado("洗")
        assertEquals("hard", tocado?.dificultad)
        assertEquals(2L, tocado?.timestamp)
        assertEquals(1, dao.kanjisPorDificultad("hard").size)  // una sola fila, no duplicada
    }

    @Test
    fun `todasPalabras devuelve las filas de todas las historias sin filtrar`() = runTest {
        val dao = db().dao()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        dao.registrarPalabra(PalabraTocada("urashima_taro", "亀", timestamp = 2L))
        assertEquals(2, dao.todasPalabras().size)
    }

    @Test
    fun `kanjisTaggeados excluye los vistos sin dificultad`() = runTest {
        val dao = db().dao()
        dao.registrarAperturaKanji("見", 1L)  // visto, nunca taggeado
        dao.registrarAperturaKanji("洗", 2L)
        dao.setDificultadKanji("洗", "hard")
        val taggeados = dao.kanjisTaggeados()
        assertEquals(1, taggeados.size)
        assertEquals("洗", taggeados[0].kanji)
    }
}
