package com.tatoh.dokushorenshu.ui.biblioteca

import com.tatoh.dokushorenshu.datos.HistoriasRepo
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
import java.io.File
import java.io.IOException

// Fakes mínimos: mismo patrón que HistoriasRepoTest (http fake + assets fake) y un
// ProgresoDao in-memory implementado a mano (map). ProgresoDaoFake vive en
// datos/progreso/Fakes.kt: se comparte con LectorViewModelTest (Task 10).

class BibliotecaViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun antes() { Dispatchers.setMain(dispatcher) }
    @After fun despues() { Dispatchers.resetMain() }

    private val momotaroJson =
        javaClass.classLoader!!.getResourceAsStream("momotaro.json")!!.readBytes().decodeToString()
    private val catalogoJson =
        javaClass.classLoader!!.getResourceAsStream("catalogo.json")!!.readBytes().decodeToString()

    private fun repo(conRed: Boolean): HistoriasRepo = HistoriasRepo(
        leerAsset = { n ->
            when (n) {
                "historias/momotaro.json" -> momotaroJson
                "catalogo.json" -> catalogoJson
                else -> null
            }
        },
        listarAssetsHistorias = { listOf("momotaro.json") },
        dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
        http = { url ->
            if (conRed && url == HistoriasRepo.URL_CATALOGO) catalogoJson
            else throw IOException("sin red")
        },
        // mismo dispatcher que Dispatchers.Main (ver @Before): así todo el trabajo de
        // fondo cae en el scheduler virtual y advanceUntilIdle() es determinístico
        // (con Dispatchers.IO real la resolución ocurre en un hilo fuera del scheduler).
        ioDispatcher = dispatcher,
    )

    private fun vm(conRed: Boolean) =
        BibliotecaViewModel(repo(conRed), ProgresoDaoFake(), ioDispatcher = dispatcher)

    @Test
    fun `carga locales con progreso y catalogo con disponibles`() = runTest {
        val vm = vm(conRed = true)
        vm.cargar()
        advanceUntilIdle()
        assertEquals(1, vm.locales.value.size)
        assertEquals("momotaro", vm.locales.value[0].historia.id)
        val catalogo = vm.catalogo.value as EstadoCatalogo.Ok
        assertEquals(3, catalogo.disponibles.size)  // 4 del catálogo - momotaro local
    }

    @Test
    fun `sin red - catalogo en error pero locales cargadas`() = runTest {
        val vm = vm(conRed = false)
        vm.cargar()
        advanceUntilIdle()
        assertEquals(1, vm.locales.value.size)
        assertTrue(vm.catalogo.value is EstadoCatalogo.Error)
    }

    @Test
    fun `item de biblioteca local expone metadata del catalogo`() = runTest {
        val vm = vm(conRed = false)
        vm.cargar(); advanceUntilIdle()
        val item = vm.locales.value.first { it.historia.id == "momotaro" }
        assertEquals("ももたろう", item.metadata?.tituloLectura)
        // valor del fixture real catalogo.json (test/resources) para "momotaro"
        assertEquals(174, item.metadata?.oraciones)
    }
}
