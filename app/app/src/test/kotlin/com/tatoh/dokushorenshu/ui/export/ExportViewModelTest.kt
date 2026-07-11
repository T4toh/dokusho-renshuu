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
        viewModel.exportar(TipoExport.STORIES)
        advanceUntilIdle()
        val listo = viewModel.estado.value as EstadoExport.Listo
        assertEquals("Exported 1 stories (1 kanji, 216 skipped)", listo.resumen)
    }
}
