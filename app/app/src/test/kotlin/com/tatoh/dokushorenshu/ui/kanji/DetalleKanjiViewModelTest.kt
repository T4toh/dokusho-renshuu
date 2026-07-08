package com.tatoh.dokushorenshu.ui.kanji

import com.tatoh.dokushorenshu.datos.DiccionarioFake
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.OracionEjemplo
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

    @Test
    fun `kanji existente carga info y ejemplos con limite 5`() = runTest {
        val dic = DiccionarioFake()
        dic.kanjis["語"] = KanjiInfo("語", listOf("word", "language"), listOf("ゴ"), listOf("かた.る"), 4, 14)
        dic.ejemplosKanji["語"] = (1..8).map { OracionEjemplo("例文$it", "example $it") }

        val vm = DetalleKanjiViewModel("語", dic, ioDispatcher = dispatcher)
        vm.cargar()
        advanceUntilIdle()

        val estado = vm.estado.value
        assertFalse(estado.cargando)
        assertEquals("語", estado.info?.kanji)
        assertEquals(5, estado.ejemplos.size)
    }

    @Test
    fun `kanji inexistente no crashea y expone estado sin info`() = runTest {
        val dic = DiccionarioFake()

        val vm = DetalleKanjiViewModel("龘", dic, ioDispatcher = dispatcher)
        vm.cargar()
        advanceUntilIdle()

        val estado = vm.estado.value
        assertFalse(estado.cargando)
        assertNull(estado.info)
        assertTrue(estado.ejemplos.isEmpty())
    }
}
