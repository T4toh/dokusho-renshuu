package com.tatoh.dokushorenshu.ui.biblioteca

import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.progreso.PalabraTocada
import com.tatoh.dokushorenshu.datos.progreso.Pref
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDao
import com.tatoh.dokushorenshu.datos.progreso.ProgresoHistoria
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
// ProgresoDao in-memory implementado a mano (map). Ver Step 3 para las firmas.

/** ProgresoDao in-memory: suficiente para el ViewModel, no ejercita Room. */
private class ProgresoDaoFake : ProgresoDao {
    private val progresos = mutableMapOf<String, ProgresoHistoria>()
    private val palabras = mutableListOf<PalabraTocada>()
    private val prefs = mutableMapOf<String, String>()

    override suspend fun progreso(id: String): ProgresoHistoria? = progresos[id]

    override suspend fun todos(): List<ProgresoHistoria> = progresos.values.toList()

    override suspend fun guardarProgreso(progreso: ProgresoHistoria) {
        progresos[progreso.idHistoria] = progreso
    }

    override suspend fun registrarPalabra(palabra: PalabraTocada) {
        palabras.add(palabra)
    }

    override suspend fun palabrasDe(id: String): List<PalabraTocada> =
        palabras.filter { it.idHistoria == id }

    override suspend fun pref(clave: String): String? = prefs[clave]

    override suspend fun guardarPref(pref: Pref) {
        prefs[pref.clave] = pref.valor
    }
}

class BibliotecaViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun antes() { Dispatchers.setMain(dispatcher) }
    @After fun despues() { Dispatchers.resetMain() }

    private val momotaroJson =
        javaClass.classLoader!!.getResourceAsStream("momotaro.json")!!.readBytes().decodeToString()
    private val catalogoJson =
        javaClass.classLoader!!.getResourceAsStream("catalogo.json")!!.readBytes().decodeToString()

    private fun repo(conRed: Boolean): HistoriasRepo = HistoriasRepo(
        leerAsset = { n -> if (n == "historias/momotaro.json") momotaroJson else null },
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
}
