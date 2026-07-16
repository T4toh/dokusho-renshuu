package com.tatoh.dokushorenshu.ui.biblioteca

import com.tatoh.dokushorenshu.datos.DiccionarioFake
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.ParserHistoria
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

    private fun repo(
        conRed: Boolean,
        catalogoAsset: String = catalogoJson,
        catalogoRemoto: String = catalogoJson,
    ): HistoriasRepo = HistoriasRepo(
        leerAsset = { n ->
            when (n) {
                "historias/momotaro.json" -> momotaroJson
                "catalogo.json" -> catalogoAsset
                else -> null
            }
        },
        listarAssetsHistorias = { listOf("momotaro.json") },
        dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
        dirImportadas = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it },
        http = { url ->
            when {
                !conRed -> throw IOException("sin red")
                url == HistoriasRepo.URL_CATALOGO -> catalogoRemoto
                url == HistoriasRepo.urlHistoria("momotaro") -> momotaroJson
                else -> throw IOException("sin red")
            }
        },
        // mismo dispatcher que Dispatchers.Main (ver @Before): así todo el trabajo de
        // fondo cae en el scheduler virtual y advanceUntilIdle() es determinístico
        // (con Dispatchers.IO real la resolución ocurre en un hilo fuera del scheduler).
        ioDispatcher = dispatcher,
    )

    private fun vm(conRed: Boolean, dao: ProgresoDaoFake = ProgresoDaoFake(), dic: DiccionarioFake = DiccionarioFake()) =
        BibliotecaViewModel(repo(conRed), dao, dic, ioDispatcher = dispatcher)

    // catálogo con el tamaño de momotaro reemplazado — para simular que el remoto (o el
    // asset local viejo) difiere. El fixture real trae "tamaño": 67083 para momotaro.
    private fun catalogoConTamanioMomotaro(tamanio: Long): String =
        catalogoJson.replace(Regex("\"tamaño\"\\s*:\\s*67083"), "\"tamaño\": $tamanio")

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

    @Test
    fun `sin kanjis taggeados la seccion review esta vacia`() = runTest {
        val vm = vm(conRed = false)
        vm.cargar(); advanceUntilIdle()
        assertTrue(vm.review.value.isEmpty())
    }

    @Test
    fun `kanji taggeado easy aparece en review, medium y hard con hint`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarAperturaKanji("語", 100L)
        dao.setDificultadKanji("語", "easy")
        val dic = DiccionarioFake()
        dic.kanjis["語"] = KanjiInfo("語", listOf("word", "language"), listOf("ゴ"), listOf("かた.る"), 4, 14)
        val vm = vm(conRed = false, dao = dao, dic = dic)
        vm.cargar(); advanceUntilIdle()

        val tarjetas = vm.review.value
        assertEquals(3, tarjetas.size)
        val easy = tarjetas.first { it.dificultad == "easy" } as TarjetaReview.ConKanji
        assertEquals("語", easy.kanji.kanji)
        assertEquals("word", easy.kanji.significados.first())
        assertTrue(tarjetas.first { it.dificultad == "medium" } is TarjetaReview.Vacia)
        assertTrue(tarjetas.first { it.dificultad == "hard" } is TarjetaReview.Vacia)
    }

    @Test
    fun `historia importada se marca y se puede borrar`() = runTest {
        // usamos el mismo repo para el vm y para guardar la importada (vm(conRed) crea uno nuevo
        // cada vez, así que acá construimos el vm a mano sobre un único repo).
        val repo = repo(conRed = false)
        val vm = BibliotecaViewModel(repo, ProgresoDaoFake(), DiccionarioFake(), ioDispatcher = dispatcher)
        repo.guardarImportada(ParserHistoria.parsear(momotaroJson).copy(id = "propia", titulo = "自作"))
        vm.cargar()
        advanceUntilIdle()
        val item = vm.locales.value.first { it.historia.id == "propia" }
        assertTrue(item.importada)
        assertFalse(vm.locales.value.first { it.historia.id == "momotaro" }.importada)

        vm.borrarImportada("propia")
        advanceUntilIdle()
        assertTrue(vm.locales.value.none { it.historia.id == "propia" })
    }

    @Test
    fun `kanji taggeado que ya no esta en el db se salta al siguiente mas antiguo`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarAperturaKanji("龘", 50L)   // más antiguo, ya no existe en el db nuevo
        dao.setDificultadKanji("龘", "hard")
        dao.registrarAperturaKanji("山", 200L)  // más nuevo, sí existe
        dao.setDificultadKanji("山", "hard")
        val dic = DiccionarioFake()
        dic.kanjis["山"] = KanjiInfo("山", listOf("mountain"), emptyList(), listOf("やま"), 8, 3)
        val vm = vm(conRed = false, dao = dao, dic = dic)
        vm.cargar(); advanceUntilIdle()

        val hard = vm.review.value.first { it.dificultad == "hard" } as TarjetaReview.ConKanji
        assertEquals("山", hard.kanji.kanji)  // 龘 se saltea (query defensiva)
    }

    // --- actualizables: fix "la app nunca actualiza historias bundleadas" (backlog
    // feedback de uso 2026-07-13). refrescarCatalogo compara tamanio remoto vs local
    // (HistoriasRepo.tamanioLocal) para cada historia local y publica los ids con
    // update disponible; la UI muestra el botón Update que llama a descargar(id). ---

    @Test
    fun `catalogo remoto identico al local no marca updates`() = runTest {
        val vm = vm(conRed = true)
        vm.cargar(); advanceUntilIdle()
        assertTrue(vm.actualizables.value.isEmpty())
    }

    @Test
    fun `tamanio remoto distinto marca update para la historia local`() = runTest {
        val repo = repo(conRed = true, catalogoRemoto = catalogoConTamanioMomotaro(1))
        val vm = BibliotecaViewModel(repo, ProgresoDaoFake(), DiccionarioFake(), ioDispatcher = dispatcher)
        vm.cargar(); advanceUntilIdle()
        assertEquals(setOf("momotaro"), vm.actualizables.value)
        // la historia local con update NO aparece además como descargable
        val catalogo = vm.catalogo.value as EstadoCatalogo.Ok
        assertTrue(catalogo.disponibles.none { it.id == "momotaro" })
    }

    @Test
    fun `descargar re-descarga la historia y limpia el flag de update`() = runTest {
        // asset local viejo (tamaño 1) vs remoto real: el remoto declara EXACTAMENTE los
        // bytes del JSON que sirve el http fake, como en producción (tamaño = getsize).
        val tamanioReal = momotaroJson.toByteArray(Charsets.UTF_8).size.toLong()
        val repo = repo(
            conRed = true,
            catalogoAsset = catalogoConTamanioMomotaro(1),
            catalogoRemoto = catalogoConTamanioMomotaro(tamanioReal),
        )
        val vm = BibliotecaViewModel(repo, ProgresoDaoFake(), DiccionarioFake(), ioDispatcher = dispatcher)
        vm.cargar(); advanceUntilIdle()
        assertEquals(setOf("momotaro"), vm.actualizables.value)

        vm.descargar("momotaro"); advanceUntilIdle()
        assertTrue(vm.actualizables.value.isEmpty())  // descargada.length() == tamanio remoto
        assertEquals(1, vm.locales.value.count { it.historia.id == "momotaro" })  // sin duplicar
    }

    @Test
    fun `sin red no hay flags de update y el error de catalogo se mantiene`() = runTest {
        val vm = vm(conRed = false)
        vm.cargar(); advanceUntilIdle()
        assertTrue(vm.actualizables.value.isEmpty())
        assertTrue(vm.catalogo.value is EstadoCatalogo.Error)
    }
}
