package com.tatoh.dokushorenshu.ui.recortes

import com.tatoh.dokushorenshu.datos.DiccionarioFake
import com.tatoh.dokushorenshu.datos.Recorte
import com.tatoh.dokushorenshu.datos.RecortesRepo
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDaoFake
import com.tatoh.dokushorenshu.dominio.BuscadorPalabras
import com.tatoh.dokushorenshu.dominio.CreadorRecortes
import com.tatoh.dokushorenshu.dominio.GeneradorFurigana
import com.tatoh.dokushorenshu.dominio.Tokenizador
import com.tatoh.dokushorenshu.dominio.ocr.RecortadorOcr
import com.tatoh.dokushorenshu.dominio.ocr.Recorte as RectanguloOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecorteViewModelTest {

    @get:Rule val carpeta = TemporaryFolder()

    companion object {
        // Kuromoji carga IPADIC y pica en ~1 GB (ver maxHeapSize en build.gradle.kts):
        // una sola instancia para toda la clase, nunca una por test. Mismo criterio que
        // CreadorRecortesTest.
        private val tokenizador = Tokenizador()
    }

    // mismo setup de dispatcher que LectorViewModelTest
    private val dispatcher = StandardTestDispatcher()

    @Before fun antes() { Dispatchers.setMain(dispatcher) }
    @After fun despues() { Dispatchers.resetMain() }

    private fun repo() = RecortesRepo(carpeta.root)

    private fun creador() = CreadorRecortes(GeneradorFurigana(tokenizador), repo())

    private fun crear(texto: String, imagenPendiente: File? = null): Recorte =
        creador().crear(texto, imagenPendiente)

    private fun vm(
        recorte: Recorte,
        dao: ProgresoDaoFake = ProgresoDaoFake(),
        recortador: RecortadorOcr = RecortadorOcr { _, _, _, _ -> "" },
    ) = RecorteViewModel(
        id = recorte.id,
        recortesRepo = repo(),
        creadorRecortes = creador(),
        tokenizador = tokenizador,
        buscador = BuscadorPalabras(DiccionarioFake()),
        progresoDao = dao,
        recortador = recortador,
        // mismo dispatcher que Dispatchers.Main (ver @Before): así advanceUntilIdle()
        // cubre también el trabajo de I/O y es determinístico.
        ioDispatcher = dispatcher,
    )

    // ---- El prefijo del que depende la exportación (Task 9) ----

    @Test
    fun `tocar una palabra registra el idHistoria con el prefijo recorte y dos puntos`() = runTest {
        val recorte = crear("今日はいい天気です。")
        val dao = ProgresoDaoFake()
        val vm = vm(recorte, dao)
        vm.cargar(); advanceUntilIdle()

        vm.tapPalabra(0, vm.estado.value.planas[0].tokens[0])
        advanceUntilIdle()

        // String EXACTO a propósito: este literal es lo único que separa el mazo de los
        // recortes del de las historias (la exportación filtra con LIKE 'recorte:%').
        // Si alguien lo cambia, acá tiene que explotar — no hay compilador ni error de
        // runtime que avise.
        assertEquals(1, dao.palabrasDe("recorte:" + recorte.id).size)
    }

    @Test
    fun `lo registrado NO queda bajo el id pelado, que es lo que veria el mazo de historias`() = runTest {
        val recorte = crear("今日はいい天気です。")
        val dao = ProgresoDaoFake()
        val vm = vm(recorte, dao)
        vm.cargar(); advanceUntilIdle()

        vm.tapPalabra(0, vm.estado.value.planas[0].tokens[0])
        advanceUntilIdle()

        assertTrue(dao.palabrasDe(recorte.id).isEmpty())
    }

    @Test
    fun `registra la forma de diccionario y no la superficie conjugada`() = runTest {
        val recorte = crear("犬が走った。")
        val dao = ProgresoDaoFake()
        val vm = vm(recorte, dao)
        vm.cargar(); advanceUntilIdle()

        // el token conjugado del texto (走っ → 走る): es el caso que motivó guardar la
        // forma base (feedback de uso 2026-08-18, la carta mostraba 食べ en vez de 食べる)
        val conjugado = vm.estado.value.planas[0].tokens
            .first { it.formaBase != null && it.formaBase != it.superficie }
        vm.tapPalabra(0, conjugado)
        advanceUntilIdle()

        assertEquals(conjugado.formaBase, dao.palabrasDe("recorte:" + recorte.id).single().termino)
    }

    // ---- Selección libre ----

    @Test
    fun `long-press ancla la seleccion en el token`() = runTest {
        val recorte = crear("今日はいい天気です。")
        val vm = vm(recorte)
        vm.cargar(); advanceUntilIdle()

        val token = vm.estado.value.planas[0].tokens[0]
        vm.iniciarSeleccion(0, token)

        val seleccion = vm.estado.value.seleccion!!
        assertEquals(token.inicio, seleccion.inicio)
        assertEquals(token.fin, seleccion.fin)
        assertEquals(token.superficie, vm.estado.value.textoSeleccionado)
    }

    @Test
    fun `tap con seleccion activa extiende el rango a la union en la misma oracion`() = runTest {
        val recorte = crear("今日はいい天気です。")
        val vm = vm(recorte)
        vm.cargar(); advanceUntilIdle()

        val tokens = vm.estado.value.planas[0].tokens
        vm.iniciarSeleccion(0, tokens[2])   // ancla en el medio
        vm.tapPalabra(0, tokens.last())     // extiende hacia adelante
        vm.tapPalabra(0, tokens.first())    // y hacia atrás
        advanceUntilIdle()

        val seleccion = vm.estado.value.seleccion!!
        assertEquals(tokens.first().inicio, seleccion.inicio)
        assertEquals(tokens.last().fin, seleccion.fin)
        // el texto seleccionado es el substring crudo: incluye las partículas del medio
        assertEquals(vm.estado.value.planas[0].oracion.texto, vm.estado.value.textoSeleccionado)
        assertNull("extender no abre el diccionario", vm.estado.value.consulta)
    }

    @Test
    fun `tap en OTRA oracion limpia la seleccion y abre la consulta`() = runTest {
        val recorte = crear("今日はいい天気です。\n犬が走った。")
        val vm = vm(recorte)
        vm.cargar(); advanceUntilIdle()
        assertEquals(2, vm.estado.value.planas.size)

        vm.iniciarSeleccion(0, vm.estado.value.planas[0].tokens[0])
        vm.tapPalabra(1, vm.estado.value.planas[1].tokens[0])
        advanceUntilIdle()

        assertNull(vm.estado.value.seleccion)
        assertNotNull(vm.estado.value.consulta)
    }

    // ---- Edición ----

    @Test
    fun `una edicion en curso sobrevive a un segundo cargar (rotacion)`() = runTest {
        val recorte = crear("今日はいい天気です。")
        val vm = vm(recorte)
        vm.cargar(); advanceUntilIdle()

        vm.empezarEdicion()
        vm.setTextoEditado("犬が走った。")
        // la Screen dispara cargar() con LaunchedEffect(Unit), que vuelve a correr al
        // rotar: el borrador no se puede perder por eso.
        vm.cargar(); advanceUntilIdle()

        assertTrue(vm.estado.value.editando)
        assertEquals("犬が走った。", vm.estado.value.textoEditado)
    }

    @Test
    fun `guardar la edicion regenera los parrafos y persiste`() = runTest {
        val recorte = crear("今日はいい天気です。")
        val vm = vm(recorte)
        vm.cargar(); advanceUntilIdle()

        vm.empezarEdicion()
        vm.setTextoEditado("犬が走った。\n猫が寝る。")
        vm.guardarEdicion(); advanceUntilIdle()

        assertTrue(!vm.estado.value.editando)
        assertEquals(2, vm.estado.value.planas.size)
        // en disco, no solo en memoria, y con el MISMO id (si cambiara, el vocabulario
        // ya registrado bajo "recorte:<id>" quedaría huérfano)
        val enDisco = repo().cargar(recorte.id)!!
        assertEquals("犬が走った。\n猫が寝る。", enDisco.texto)
        assertEquals(2, enDisco.parrafos.size)
    }

    @Test
    fun `guardar con el texto en blanco no toca nada`() = runTest {
        val recorte = crear("今日はいい天気です。")
        val vm = vm(recorte)
        vm.cargar(); advanceUntilIdle()

        vm.empezarEdicion()
        vm.setTextoEditado("   \n　\n")
        vm.guardarEdicion(); advanceUntilIdle()

        assertTrue("sigue en edición, no se guardó nada", vm.estado.value.editando)
        assertEquals("今日はいい天気です。", repo().cargar(recorte.id)!!.texto)
    }

    /** VM cuyo guardado en disco SIEMPRE falla: el creador escribe en un "directorio"
     *  que en realidad es un archivo, así que mkdirs() no hace nada y el writeText del
     *  .tmp tira FileNotFoundException — el mismo camino que un disco lleno o un rename
     *  rechazado. La lectura sigue yendo al repo bueno, así que cargar() funciona. */
    private fun vmConGuardadoRoto(recorte: Recorte): RecorteViewModel {
        val roto = RecortesRepo(carpeta.newFile("no-soy-un-directorio"), log = { _, _ -> })
        return RecorteViewModel(
            id = recorte.id,
            recortesRepo = repo(),
            creadorRecortes = CreadorRecortes(GeneradorFurigana(tokenizador), roto),
            tokenizador = tokenizador,
            buscador = BuscadorPalabras(DiccionarioFake()),
            progresoDao = ProgresoDaoFake(),
            recortador = RecortadorOcr { _, _, _, _ -> "" },
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `si el guardado falla, el borrador queda en pantalla en vez de perderse`() = runTest {
        val recorte = crear("今日はいい天気です。")
        val vm = vmConGuardadoRoto(recorte)
        vm.cargar(); advanceUntilIdle()

        vm.empezarEdicion()
        vm.setTextoEditado("犬が走った。")
        // Sin el runCatching de guardarEdicion() esta línea propaga la excepción fuera
        // del viewModelScope: en el dispositivo mata el proceso, acá tumba el test.
        vm.guardarEdicion(); advanceUntilIdle()

        assertTrue("no puede salir del modo edición", vm.estado.value.editando)
        assertEquals("el texto tipeado a mano sobrevive", "犬が走った。", vm.estado.value.textoEditado)
        assertNotNull("y el fallo se avisa, nunca en silencio", vm.estado.value.error)
        assertEquals("en disco sigue el texto viejo", "今日はいい天気です。", repo().cargar(recorte.id)!!.texto)

        vm.errorMostrado()
        assertNull("el aviso no se repite tras mostrarse", vm.estado.value.error)
    }

    // ---- Imagen ----

    @Test
    fun `quitarImagen deja la nota con su texto y sin imagen`() = runTest {
        val pendiente = carpeta.newFile("captura-pendiente.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val recorte = crear("今日はいい天気です。", pendiente)
        assertTrue("el fixture arranca CON imagen", recorte.tieneImagen)
        val vm = vm(recorte)
        vm.cargar(); advanceUntilIdle()
        assertNotNull(vm.estado.value.imagen)

        vm.quitarImagen(); advanceUntilIdle()

        assertNull(vm.estado.value.imagen)
        assertTrue(!vm.estado.value.recorte!!.tieneImagen)
        assertEquals("el texto sobrevive", "今日はいい天気です。", repo().cargar(recorte.id)!!.texto)
    }

    // ---- Recortar sobre la imagen guardada y re-escanear ----

    /** Nota con imagen + un VM cuyo OCR de recorte responde lo que diga [ocr].
     *  El archivo tiene bytes de mentira a propósito: el recortador real es el que
     *  decodifica y recorta, y acá está reemplazado por el fake. */
    private fun vmConImagen(texto: String, ocr: RecortadorOcr): Pair<Recorte, RecorteViewModel> {
        val pendiente = carpeta.newFile("captura-pendiente.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val recorte = crear(texto, pendiente)
        return recorte to vm(recorte, recortador = ocr)
    }

    @Test
    fun `escanear la seleccion precarga la edicion y no toca el texto guardado`() = runTest {
        val (recorte, vm) = vmConImagen("今日はいい天気です。") { _, _, _, _ -> "犬が走った。" }
        vm.cargar(); advanceUntilIdle()

        vm.empezarRecorte()
        vm.setSeleccionImagen(RectanguloOcr(left = 10, top = 20, ancho = 100, alto = 50))
        vm.escanearSeleccion(anchoDibujado = 200, altoDibujado = 400); advanceUntilIdle()

        val estado = vm.estado.value
        assertTrue("el texto reconocido tiene que quedar editable, no guardado", estado.editando)
        assertEquals("犬が走った。", estado.textoEditado)
        assertTrue("y el modo recorte se cierra", !estado.modoRecorte)
        assertEquals(
            "en disco sigue el texto viejo hasta que el usuario toque Save",
            "今日はいい天気です。",
            repo().cargar(recorte.id)!!.texto,
        )
    }

    @Test
    fun `escanear un area sin texto avisa y deja el modo recorte para reintentar`() = runTest {
        val (recorte, vm) = vmConImagen("今日はいい天気です。") { _, _, _, _ -> "" }
        vm.cargar(); advanceUntilIdle()

        vm.empezarRecorte()
        vm.setSeleccionImagen(RectanguloOcr(left = 0, top = 0, ancho = 10, alto = 10))
        vm.escanearSeleccion(anchoDibujado = 200, altoDibujado = 400); advanceUntilIdle()

        val estado = vm.estado.value
        assertTrue("no puede entrar en edición con texto vacío", !estado.editando)
        assertTrue("se queda en modo recorte para reintentar el recuadro", estado.modoRecorte)
        assertNotNull("y avisa, nunca en silencio", estado.error)
        assertEquals("今日はいい天気です。", repo().cargar(recorte.id)!!.texto)
    }

    @Test
    fun `si el OCR falla el estado queda intacto y se avisa`() = runTest {
        val (recorte, vm) = vmConImagen("今日はいい天気です。") { _, _, _, _ -> error("ML Kit se cayó") }
        vm.cargar(); advanceUntilIdle()

        vm.empezarRecorte()
        vm.setSeleccionImagen(RectanguloOcr(left = 0, top = 0, ancho = 10, alto = 10))
        // Sin el runCatching de escanearSeleccion() esta línea propaga fuera del
        // viewModelScope: en el dispositivo mata el proceso, acá tumba el test.
        vm.escanearSeleccion(anchoDibujado = 200, altoDibujado = 400); advanceUntilIdle()

        val estado = vm.estado.value
        assertTrue(!estado.editando)
        assertTrue(estado.modoRecorte)
        assertNotNull(estado.error)
        assertEquals("今日はいい天気です。", repo().cargar(recorte.id)!!.texto)
    }

    @Test
    fun `escanear sin haber dibujado un recuadro no hace nada`() = runTest {
        val (_, vm) = vmConImagen("今日はいい天気です。") { _, _, _, _ -> "犬が走った。" }
        vm.cargar(); advanceUntilIdle()

        vm.empezarRecorte()
        vm.escanearSeleccion(anchoDibujado = 200, altoDibujado = 400); advanceUntilIdle()

        val estado = vm.estado.value
        assertTrue("sin recuadro no hay OCR que abrir", !estado.editando)
        assertNull("y no es un error del usuario: no se avisa nada", estado.error)
    }

    @Test
    fun `cancelar el recorte descarta el recuadro`() = runTest {
        val (_, vm) = vmConImagen("今日はいい天気です。") { _, _, _, _ -> "犬が走った。" }
        vm.cargar(); advanceUntilIdle()

        vm.empezarRecorte()
        vm.setSeleccionImagen(RectanguloOcr(left = 10, top = 20, ancho = 100, alto = 50))
        vm.cancelarRecorte()

        val estado = vm.estado.value
        assertTrue(!estado.modoRecorte)
        assertNull("el recuadro no puede sobrevivir a la salida del modo", estado.seleccionImagen)
    }
}
