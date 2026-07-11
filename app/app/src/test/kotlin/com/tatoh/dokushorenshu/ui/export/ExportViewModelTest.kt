package com.tatoh.dokushorenshu.ui.export

import com.tatoh.dokushorenshu.datos.DiccionarioFake
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.progreso.KanjiTocado
import com.tatoh.dokushorenshu.datos.progreso.PalabraTocada
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDaoFake
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.dominio.anki.ArmadorMazos
import com.tatoh.dokushorenshu.dominio.anki.MazoNotas
import com.tatoh.dokushorenshu.dominio.anki.ModeloNotas
import com.tatoh.dokushorenshu.dominio.anki.NotaKanji
import com.tatoh.dokushorenshu.dominio.anki.NotaWords
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

class ExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun antes() { Dispatchers.setMain(dispatcher) }
    @After fun despues() { Dispatchers.resetMain() }

    private val momotaroJson =
        javaClass.classLoader!!.getResourceAsStream("momotaro.json")!!.readBytes().decodeToString()

    private fun historiasRepo() = HistoriasRepo(
        leerAsset = { n -> if (n == "historias/momotaro.json") momotaroJson else null },
        listarAssetsHistorias = { listOf("momotaro.json") },
        dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
        dirImportadas = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it },
    )

    private fun dirTemp(): File = File.createTempFile("export", "").let { it.delete(); it.mkdirs(); it }

    /** Repo con DOS historias locales — el fixture de un solo momotaro no
     *  alcanza para probar que la selección filtra (con una sola historia
     *  filtrar o no da el mismo resultado). */
    private fun historiasRepoDos() = HistoriasRepo(
        leerAsset = { n ->
            when (n) {
                "historias/momotaro.json" -> momotaroJson
                "historias/otra.json" -> momotaroJson
                    .replaceFirst("\"id\": \"momotaro\"", "\"id\": \"otra\"")
                    .replaceFirst("\"titulo\": \"桃太郎\"", "\"titulo\": \"Otra historia\"")
                else -> null
            }
        },
        listarAssetsHistorias = { listOf("momotaro.json", "otra.json") },
        dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
        dirImportadas = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it },
    )

    private fun vmDos(
        dao: ProgresoDaoFake = ProgresoDaoFake(),
        diccionario: DiccionarioFake = DiccionarioFake(),
        escribirMazos: (File, List<MazoNotas>) -> Unit = { _, _ -> },
        dirExport: File = dirTemp(),
    ): ExportViewModel {
        val armador = ArmadorMazos(dao, diccionario, historiasRepoDos())
        return ExportViewModel(dao, armador, dirExport, { _, _, _ -> }, escribirMazos, dispatcher, log = { _, _ -> })
    }

    private fun vm(
        dao: ProgresoDaoFake = ProgresoDaoFake(),
        diccionario: DiccionarioFake = DiccionarioFake(),
        escribir: (File, List<NotaWords>, List<NotaKanji>) -> Unit = { _, _, _ -> },
        escribirMazos: (File, List<MazoNotas>) -> Unit = { _, _ -> },
        dirExport: File = dirTemp(),
    ): ExportViewModel {
        val armador = ArmadorMazos(dao, diccionario, historiasRepo())
        return ExportViewModel(dao, armador, dirExport, escribir, escribirMazos, dispatcher, log = { _, _ -> })
    }

    @Test
    fun `contadores reflejan terminos unicos y kanjis taggeados`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        dao.registrarPalabra(PalabraTocada("urashima_taro", "犬", timestamp = 2L))  // mismo termino, no duplica
        dao.registrarPalabra(PalabraTocada("momotaro", "猿", timestamp = 3L))
        dao.insertarKanjiSiNoExiste(KanjiTocado("犬", "easy", 1L))
        dao.insertarKanjiSiNoExiste(KanjiTocado("猿", null, 2L))  // sin tag, no cuenta

        val viewModel = vm(dao = dao)
        viewModel.cargar()
        advanceUntilIdle()

        assertEquals(ContadoresExport(words = 2, kanjisTaggeados = 1, historias = 1), viewModel.contadores.value)
    }

    @Test
    fun `exportar termina en Listo con el archivo del mazo Words`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        val llamadas = mutableListOf<Triple<File, Int, Int>>()
        val dirExport = dirTemp()
        val viewModel = vm(
            dao = dao,
            escribir = { archivo, words, kanji -> llamadas.add(Triple(archivo, words.size, kanji.size)) },
            dirExport = dirExport,
        )
        assertEquals(EstadoExport.Idle, viewModel.estado.value)

        viewModel.exportar(TipoExport.WORDS)
        advanceUntilIdle()

        val listo = viewModel.estado.value as EstadoExport.Listo
        assertEquals(File(dirExport, "dokusho-words.apkg"), listo.archivo)
        assertEquals(1, llamadas.size)
        assertEquals(1, llamadas[0].second)  // 1 nota words
        assertEquals(0, llamadas[0].third)   // mazo Words no lleva notas de kanji
    }

    @Test
    fun `fallo del escritor pasa a Error y borra el archivo parcial`() = runTest {
        val dirExport = dirTemp()
        val viewModel = vm(
            escribir = { archivo, _, _ -> archivo.writeText("parcial"); throw IOException("disco lleno") },
            dirExport = dirExport,
        )

        viewModel.exportar(TipoExport.KANJI)
        advanceUntilIdle()

        assertTrue(viewModel.estado.value is EstadoExport.Error)
        assertFalse(File(dirExport, "dokusho-kanji.apkg").exists())
    }

    @Test
    fun `kanji omitido por no estar en el db queda reflejado en el resumen`() = runTest {
        val dao = ProgresoDaoFake()
        dao.insertarKanjiSiNoExiste(KanjiTocado("犬", "easy", 1L))  // taggeado...
        val viewModel = vm(dao = dao, diccionario = DiccionarioFake())  // ...pero sin entrada en el dict fake

        viewModel.exportar(TipoExport.KANJI)
        advanceUntilIdle()

        val listo = viewModel.estado.value as EstadoExport.Listo
        assertTrue(listo.resumen.contains("1 skipped"))
    }

    @Test
    fun `Generando lleva el tipo que se esta exportando`() = runTest {
        var estadoDuranteEscritura: EstadoExport? = null
        lateinit var viewModel: ExportViewModel
        viewModel = vm(escribir = { _, _, _ -> estadoDuranteEscritura = viewModel.estado.value })

        viewModel.exportar(TipoExport.KANJI)
        advanceUntilIdle()

        assertEquals(EstadoExport.Generando(TipoExport.KANJI), estadoDuranteEscritura)
    }

    @Test
    fun `exportar durante Generando se ignora`() = runTest {
        var contadorEscrituras = 0
        val viewModel = vm(
            escribir = { _, _, _ -> contadorEscrituras++ },
        )

        // Llamar exportar dos veces sin dejar que termine la primera
        viewModel.exportar(TipoExport.WORDS)
        viewModel.exportar(TipoExport.WORDS)  // segundo tap mientras está en Generando
        advanceUntilIdle()

        // El escritor debe haber corrido solo una vez
        assertEquals(1, contadorEscrituras)
    }

    @Test
    fun `contadores incluyen historias locales`() = runTest {
        val viewModel = vm()  // el helper ya monta HistoriasRepo con momotaro
        viewModel.cargar()
        advanceUntilIdle()
        assertEquals(1, viewModel.contadores.value.historias)
    }

    @Test
    fun `exportar STORIES escribe un mazo por historia y resume counts`() = runTest {
        var mazosEscritos: List<MazoNotas>? = null
        val diccionario = DiccionarioFake().apply { todosLosKanjisConocidos = true }
        val viewModel = vm(
            diccionario = diccionario,
            escribirMazos = { _, mazos -> mazosEscritos = mazos },
        )
        // seleccionadas se puebla en cargar() (default: todas) — sin esto la
        // selección queda vacía y STORIES da Error (spec Task 9).
        viewModel.cargar()
        advanceUntilIdle()
        viewModel.exportar(TipoExport.STORIES)
        advanceUntilIdle()
        val listo = viewModel.estado.value as EstadoExport.Listo
        assertEquals("dokusho-stories.apkg", listo.archivo.name)
        assertEquals("Exported 1 stories (217 kanji)", listo.resumen)
        val mazo = mazosEscritos!!.single()
        assertEquals(ModeloNotas.deckIdDeHistoria("momotaro"), mazo.deckId)
        assertEquals("Dokusho — Stories::桃太郎", mazo.nombre)
        assertEquals(217, mazo.notasKanji.size)
        assertTrue(mazo.notasWords.isEmpty())
    }

    @Test
    fun `exportar STORIES reporta kanjis omitidos en el resumen`() = runTest {
        val diccionario = DiccionarioFake()  // solo conoce lo cargado a mano
        diccionario.kanjis["山"] = KanjiInfo("山", listOf("mountain"), listOf("サン"), listOf("やま"), null, null)
        val viewModel = vm(diccionario = diccionario, escribirMazos = { _, _ -> })
        viewModel.cargar()
        advanceUntilIdle()
        viewModel.exportar(TipoExport.STORIES)
        advanceUntilIdle()
        val listo = viewModel.estado.value as EstadoExport.Listo
        assertEquals("Exported 1 stories (1 kanji, 216 skipped)", listo.resumen)
    }

    // --- selección de historias para STORIES (Plan 4b Task 9) ---

    @Test
    fun `cargar puebla historiasStories y selecciona todas por defecto`() = runTest {
        val viewModel = vmDos()
        viewModel.cargar()
        advanceUntilIdle()

        val ids = viewModel.historiasStories.value.map { it.id }
        assertEquals(setOf("momotaro", "otra"), ids.toSet())
        assertEquals(ids.toSet(), viewModel.seleccionadas.value)
    }

    @Test
    fun `toggleHistoria saca y vuelve a poner un id de la seleccion`() = runTest {
        val viewModel = vmDos()
        viewModel.cargar()
        advanceUntilIdle()
        val id = viewModel.historiasStories.value.first().id

        viewModel.toggleHistoria(id)
        assertFalse(id in viewModel.seleccionadas.value)

        viewModel.toggleHistoria(id)
        assertTrue(id in viewModel.seleccionadas.value)
    }

    @Test
    fun `stories exporta solo las historias seleccionadas`() = runTest {
        var mazosEscritos: List<MazoNotas>? = null
        val diccionario = DiccionarioFake().apply { todosLosKanjisConocidos = true }
        val viewModel = vmDos(
            diccionario = diccionario,
            escribirMazos = { _, mazos -> mazosEscritos = mazos },
        )
        viewModel.cargar()
        advanceUntilIdle()

        val ids = viewModel.historiasStories.value.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(ids.toSet(), viewModel.seleccionadas.value)  // default: todas

        viewModel.toggleHistoria(ids.first())  // des-seleccionar una

        viewModel.exportar(TipoExport.STORIES)
        advanceUntilIdle()

        // invariante: mazos escritos == seleccionadas (comparo por deckId, que
        // codifica el idHistoria — MazoNotas no expone el id crudo)
        val mazos = mazosEscritos!!
        assertEquals(1, mazos.size)
        assertEquals(
            ids.drop(1).map { ModeloNotas.deckIdDeHistoria(it) }.toSet(),
            mazos.map { it.deckId }.toSet(),
        )
    }

    @Test
    fun `exportar STORIES con seleccion vacia da Error y no llega a Generando`() = runTest {
        var seEscribio = false
        val viewModel = vmDos(escribirMazos = { _, _ -> seEscribio = true })
        viewModel.cargar()
        advanceUntilIdle()
        viewModel.historiasStories.value.forEach { viewModel.toggleHistoria(it.id) }  // vacía la selección
        assertTrue(viewModel.seleccionadas.value.isEmpty())

        viewModel.exportar(TipoExport.STORIES)
        advanceUntilIdle()

        assertFalse(seEscribio)
        assertTrue(viewModel.estado.value is EstadoExport.Error)
    }
}
