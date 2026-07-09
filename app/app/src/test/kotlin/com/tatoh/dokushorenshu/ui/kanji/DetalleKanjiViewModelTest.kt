package com.tatoh.dokushorenshu.ui.kanji

import com.tatoh.dokushorenshu.datos.DiccionarioFake
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.OracionEjemplo
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDaoFake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// DiccionarioFake vive en datos/Fakes.kt: compartido con BuscadorPalabrasTest (Task 7).

class DetalleKanjiViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun antes() { Dispatchers.setMain(dispatcher) }
    @After fun despues() { Dispatchers.resetMain() }

    private fun vm(kanji: String, dic: DiccionarioFake, dao: ProgresoDaoFake = ProgresoDaoFake()) =
        DetalleKanjiViewModel(kanji, dic, dao, ioDispatcher = dispatcher)

    @Test
    fun `kanji existente carga info y ejemplos con limite 5`() = runTest {
        val dic = DiccionarioFake()
        dic.kanjis["語"] = KanjiInfo("語", listOf("word", "language"), listOf("ゴ"), listOf("かた.る"), 4, 14)
        dic.ejemplosKanji["語"] = (1..8).map { OracionEjemplo("例文$it", "example $it") }

        val instancia = vm("語", dic)
        instancia.cargar()
        advanceUntilIdle()

        val estado = instancia.estado.value
        assertFalse(estado.cargando)
        assertEquals("語", estado.info?.kanji)
        assertEquals(5, estado.ejemplos.size)
    }

    @Test
    fun `kanji inexistente no crashea y expone estado sin info`() = runTest {
        val dic = DiccionarioFake()

        val instancia = vm("龘", dic)
        instancia.cargar()
        advanceUntilIdle()

        val estado = instancia.estado.value
        assertFalse(estado.cargando)
        assertNull(estado.info)
        assertTrue(estado.ejemplos.isEmpty())
    }

    @Test
    fun `abrir detalle registra apertura automaticamente`() = runTest {
        val dic = DiccionarioFake()
        dic.kanjis["語"] = KanjiInfo("語", listOf("word"), listOf("ゴ"), emptyList(), 4, 14)
        val dao = ProgresoDaoFake()
        vm("語", dic, dao).cargar()
        advanceUntilIdle()
        assertNotNull(dao.kanjiTocado("語"))
        assertNull(dao.kanjiTocado("語")?.dificultad)
    }

    @Test
    fun `alternar dificultad taggea y destaggea`() = runTest {
        val dic = DiccionarioFake()
        dic.kanjis["語"] = KanjiInfo("語", listOf("word"), emptyList(), emptyList(), null, null)
        val dao = ProgresoDaoFake()
        val instancia = vm("語", dic, dao)
        instancia.cargar(); advanceUntilIdle()

        instancia.alternarDificultad("easy"); advanceUntilIdle()
        assertEquals("easy", instancia.estado.value.dificultad)
        assertEquals("easy", dao.kanjiTocado("語")?.dificultad)

        instancia.alternarDificultad("easy"); advanceUntilIdle()  // tap de nuevo = destaggear
        assertNull(instancia.estado.value.dificultad)
        assertNull(dao.kanjiTocado("語")?.dificultad)
    }
}
