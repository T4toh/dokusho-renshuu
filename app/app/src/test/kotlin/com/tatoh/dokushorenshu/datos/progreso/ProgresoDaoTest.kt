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
}
