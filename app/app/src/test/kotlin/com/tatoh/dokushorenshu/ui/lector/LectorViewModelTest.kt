package com.tatoh.dokushorenshu.ui.lector

import com.tatoh.dokushorenshu.datos.Diccionario
import com.tatoh.dokushorenshu.datos.Furigana
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.datos.OracionEjemplo
import com.tatoh.dokushorenshu.datos.Palabra
import com.tatoh.dokushorenshu.datos.progreso.PrefsRepo
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDaoFake
import com.tatoh.dokushorenshu.datos.progreso.ProgresoHistoria
import com.tatoh.dokushorenshu.dominio.BuscadorPalabras
import com.tatoh.dokushorenshu.dominio.PalabraToken
import com.tatoh.dokushorenshu.dominio.Tokenizador
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

/** Diccionario fake vacío: mismo patrón que BuscadorPalabrasTest (Task 7). No importa
 *  el contenido de las definiciones para estos tests, solo que `consultar` no crashee. */
private class DiccionarioFake : Diccionario {
    override fun buscarPalabra(termino: String): List<Palabra> = emptyList()
    override fun buscarKanji(kanji: String): KanjiInfo? = null
    override fun oracionesDePalabra(termino: String, limite: Int): List<OracionEjemplo> = emptyList()
    override fun oracionesDeKanji(kanji: String, limite: Int): List<OracionEjemplo> = emptyList()
}

class LectorViewModelTest {
    // mismo setup de dispatcher que BibliotecaViewModelTest
    private val dispatcher = StandardTestDispatcher()

    @Before fun antes() { Dispatchers.setMain(dispatcher) }
    @After fun despues() { Dispatchers.resetMain() }

    private val momotaroJson =
        javaClass.classLoader!!.getResourceAsStream("momotaro.json")!!.readBytes().decodeToString()

    private fun vmMomotaro(dao: ProgresoDaoFake): LectorViewModel {
        val repo = HistoriasRepo(
            leerAsset = { n -> if (n == "historias/momotaro.json") momotaroJson else null },
            listarAssetsHistorias = { listOf("momotaro.json") },
            dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
        )
        return LectorViewModel(
            idHistoria = "momotaro",
            historiasRepo = repo,
            progresoDao = dao,
            prefs = PrefsRepo(dao),
            tokenizador = Tokenizador(),
            buscador = BuscadorPalabras(DiccionarioFake()),
            // mismo dispatcher que Dispatchers.Main (ver @Before): cargar() hace todo su
            // trabajo de I/O en este scheduler virtual, así advanceUntilIdle() es determinístico.
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `carga oraciones aplanadas y restaura posicion guardada`() = runTest {
        val dao = ProgresoDaoFake()
        dao.guardarProgreso(ProgresoHistoria("momotaro", parrafo = 2, oracion = 0))
        val vm = vmMomotaro(dao)
        vm.cargar()
        advanceUntilIdle()
        val estado = vm.estado.value
        assertTrue(estado.oraciones.isNotEmpty())
        assertEquals(2, estado.oraciones[estado.indiceActual].parrafo)
    }

    @Test
    fun `avanzar guarda progreso`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        val antes = vm.estado.value.indiceActual
        vm.avanzar(); advanceUntilIdle()
        assertEquals(antes + 1, vm.estado.value.indiceActual)
        assertNotNull(dao.progreso("momotaro"))
    }

    @Test
    fun `tocar palabra arma consulta y registra`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        val token = vm.estado.value.oraciones[vm.estado.value.indiceActual]
            .tokens.first { it.esContenido }
        vm.tocarPalabra(token); advanceUntilIdle()
        assertNotNull(vm.estado.value.consulta)
        assertEquals(1, dao.palabrasDe("momotaro").size)
    }

    @Test
    fun `alternar furigana persiste`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        assertTrue(vm.estado.value.furiganaActiva)
        vm.alternarFurigana(); advanceUntilIdle()
        assertFalse(vm.estado.value.furiganaActiva)
        assertEquals("off", dao.pref("furigana"))
    }

    // --- lecturaDelToken: lógica pura de intersección de spans (Step 4) ---

    @Test
    fun `token con furigana solapada devuelve lectura`() {
        val oracion = Oracion("桃太郎は十五になりました。", listOf(Furigana(0, 3, "ももたろう")))
        val token = PalabraToken("桃太郎", null, null, inicio = 0, fin = 3, esContenido = true)
        assertEquals("ももたろう", lecturaDelToken(oracion, token))
    }

    @Test
    fun `token kana-only sin furigana solapada devuelve null`() {
        val oracion = Oracion("むかし、むかし", emptyList())
        val token = PalabraToken("むかし", null, null, inicio = 0, fin = 3, esContenido = true)
        assertNull(lecturaDelToken(oracion, token))
    }
}
