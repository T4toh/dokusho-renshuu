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
    override fun buscarPorLectura(lectura: String): List<Palabra> = emptyList()
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
    private val catalogoJson =
        javaClass.classLoader!!.getResourceAsStream("catalogo.json")!!.readBytes().decodeToString()

    private fun vmMomotaro(dao: ProgresoDaoFake, idHistoria: String = "momotaro"): LectorViewModel {
        val repo = HistoriasRepo(
            leerAsset = { n ->
                when (n) {
                    "historias/momotaro.json" -> momotaroJson
                    "catalogo.json" -> catalogoJson
                    else -> null
                }
            },
            listarAssetsHistorias = { listOf("momotaro.json") },
            dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
        )
        return LectorViewModel(
            idHistoria = idHistoria,
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
    fun `sin progreso guardado arranca en portada (indice -1)`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        assertEquals(-1, vm.estado.value.indiceActual)
    }

    @Test
    fun `previous desde la primera oracion vuelve a la portada`() = runTest {
        val dao = ProgresoDaoFake()
        dao.guardarProgreso(ProgresoHistoria("momotaro", parrafo = 0, oracion = 0))
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        assertEquals(0, vm.estado.value.indiceActual)
        vm.retroceder(); advanceUntilIdle()
        assertEquals(-1, vm.estado.value.indiceActual)
    }

    @Test
    fun `portada expone metadata del catalogo local cuando existe`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        // valores del fixture real catalogo.json (test/resources) para "momotaro"
        assertEquals("Peach Boy", vm.estado.value.metadata?.tituloEn)
        assertEquals(217, vm.estado.value.metadata?.kanjisUnicos)
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
        vm.avanzar(); advanceUntilIdle()  // sale de la portada (-1) antes de indexar oraciones
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

    @Test
    fun `cargar historia inexistente no crashea y degrada el estado`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao, idHistoria = "no-existe")
        vm.cargar()
        advanceUntilIdle()
        val estado = vm.estado.value
        assertEquals("Story not available", estado.titulo)
        assertTrue(estado.oraciones.isEmpty())
    }

    // --- enfocar: foco directo por índice (Plan 3.6 Task 2, scroll libre) ---

    @Test
    fun `enfocar actualiza indiceActual y persiste progreso igual que mover`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        vm.avanzar(); advanceUntilIdle() // sale de la portada, ya hay oraciones cargadas
        val destino = (vm.estado.value.indiceActual + 1).coerceAtMost(vm.estado.value.oraciones.lastIndex)
        vm.enfocar(destino); advanceUntilIdle()
        assertEquals(destino, vm.estado.value.indiceActual)
        val plana = vm.estado.value.oraciones[destino]
        val progreso = dao.progreso("momotaro")
        assertEquals(plana.parrafo, progreso?.parrafo)
        assertEquals(plana.oracionEnParrafo, progreso?.oracion)
    }

    @Test
    fun `enfocar con indice fuera de rango se ignora`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        vm.avanzar(); advanceUntilIdle()
        val actual = vm.estado.value.indiceActual
        vm.enfocar(vm.estado.value.oraciones.size); advanceUntilIdle() // fuera de rango
        assertEquals(actual, vm.estado.value.indiceActual)
        vm.enfocar(-1); advanceUntilIdle() // enfocar nunca apunta a la portada
        assertEquals(actual, vm.estado.value.indiceActual)
    }

    @Test
    fun `enfocar no persiste con estado degradado (sin oraciones)`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao, idHistoria = "no-existe")
        vm.cargar(); advanceUntilIdle()
        assertTrue(vm.estado.value.oraciones.isEmpty())
        vm.enfocar(0); advanceUntilIdle()
        assertEquals(-1, vm.estado.value.indiceActual)
        assertNull(dao.progreso("no-existe"))
    }

    // --- centradoPedido: contador de centrados EXPLÍCITOS (fix rubberbanding, Task 2) ---
    // Solo mover() (Previous/Next/salto desde portada) y cargar() lo incrementan; enfocar()
    // (foco por asentado de scroll libre o tap) nunca debe tocarlo — así LectorScreen no
    // vuelve a re-centrar tras un scroll que el usuario ya asentó donde quería.

    @Test
    fun `cargar establece centradoPedido inicial en 1`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        assertEquals(1, vm.estado.value.centradoPedido)
    }

    @Test
    fun `avanzar y retroceder incrementan centradoPedido`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        val inicial = vm.estado.value.centradoPedido

        vm.avanzar(); advanceUntilIdle() // salto Start reading: portada -> oracion 0
        assertEquals(inicial + 1, vm.estado.value.centradoPedido)

        vm.avanzar(); advanceUntilIdle() // Next
        assertEquals(inicial + 2, vm.estado.value.centradoPedido)

        vm.retroceder(); advanceUntilIdle() // Previous
        assertEquals(inicial + 3, vm.estado.value.centradoPedido)
    }

    @Test
    fun `mover en un limite que no cambia indiceActual no incrementa centradoPedido`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        val antes = vm.estado.value.centradoPedido
        vm.retroceder(); advanceUntilIdle() // ya está en la portada (-1), retroceder es no-op
        assertEquals(antes, vm.estado.value.centradoPedido)
    }

    @Test
    fun `enfocar (settle de scroll o tap) NO incrementa centradoPedido`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        vm.avanzar(); advanceUntilIdle() // sale de la portada
        val antes = vm.estado.value.centradoPedido
        val destino = (vm.estado.value.indiceActual + 1).coerceAtMost(vm.estado.value.oraciones.lastIndex)
        vm.enfocar(destino); advanceUntilIdle()
        assertEquals(destino, vm.estado.value.indiceActual) // el foco sí se movió...
        assertEquals(antes, vm.estado.value.centradoPedido) // ...pero sin pedir centrado
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

    // --- segmentosDeGrupo: split de un GRUPO de tokens en sub-segmentos alineados a
    // furigana exacta (Plan 3.6 fix "furigana corrida" sobre okurigana, p.ej. 刈り con
    // lectura か solo sobre 刈; luego Plan 3.6.pulido fix "furigana duplicada" sobre
    // clusters — ver tests de `agruparTokens` más abajo) ---

    @Test
    fun `token aislado totalmente cubierto por una furigana da un solo segmento con esa lectura`() {
        val token = PalabraToken("桃太郎", null, null, inicio = 0, fin = 3, esContenido = true)
        val furigana = listOf(Furigana(0, 3, "ももたろう"))
        assertEquals(
            listOf(Segmento("桃太郎", "ももたろう", token)),
            segmentosDeGrupo(GrupoRenderizado(listOf(token)), furigana),
        )
    }

    @Test
    fun `token aislado con okurigana parcialmente cubierto separa kanji con lectura del kana sin lectura`() {
        // 刈り: el kanji 刈 (index 5) tiene furigana か; り (index 6) es kana, sin furigana.
        val token = PalabraToken("刈り", null, null, inicio = 5, fin = 7, esContenido = true)
        val furigana = listOf(Furigana(5, 6, "か"))
        assertEquals(
            listOf(Segmento("刈", "か", token), Segmento("り", null, token)),
            segmentosDeGrupo(GrupoRenderizado(listOf(token)), furigana),
        )
    }

    @Test
    fun `token aislado sin furigana solapada da un solo segmento sin lectura`() {
        val token = PalabraToken("むかし", null, null, inicio = 0, fin = 3, esContenido = true)
        assertEquals(
            listOf(Segmento("むかし", null, token)),
            segmentosDeGrupo(GrupoRenderizado(listOf(token)), emptyList()),
        )
    }

    @Test
    fun `token aislado con dos furigana independientes da dos segmentos, cada uno con su lectura`() {
        val token = PalabraToken("大人", null, null, inicio = 0, fin = 2, esContenido = true)
        val furigana = listOf(Furigana(0, 1, "おと"), Furigana(1, 2, "な"))
        assertEquals(
            listOf(Segmento("大", "おと", token), Segmento("人", "な", token)),
            segmentosDeGrupo(GrupoRenderizado(listOf(token)), furigana),
        )
    }

    @Test
    fun `furigana que se sale del rango del grupo se recorta defensivamente (dato inconsistente)`() {
        // furigana [1,4) empieza ANTES del grupo (inicio=2): red de seguridad ante datos
        // inconsistentes (no debería pasar, tokens y furigana vienen del mismo texto).
        val token = PalabraToken("五に", null, null, inicio = 2, fin = 4, esContenido = true)
        val furigana = listOf(Furigana(1, 4, "じゅうごに"))
        assertEquals(
            listOf(Segmento("五に", "じゅうごに", token)),
            segmentosDeGrupo(GrupoRenderizado(listOf(token)), furigana),
        )
    }

    // --- agruparTokens: agrupa tokens consecutivos cruzados por una misma furigana
    // (Plan 3.6.pulido fix "furigana duplicada": antes 二人, tokenizado 二+人 con una sola
    // furigana ふたり que cruza el límite entre ambos, renderizaba "ふたりふたり" porque
    // CADA token overlapeado repetía la lectura completa) ---

    @Test
    fun `tokens sin furigana que cruce quedan cada uno en su propio grupo (sin cambios)`() {
        val a = PalabraToken("桃", null, null, inicio = 0, fin = 1, esContenido = true)
        val b = PalabraToken("太郎", null, null, inicio = 1, fin = 3, esContenido = true)
        val furigana = listOf(Furigana(0, 1, "もも"), Furigana(1, 3, "たろう"))
        assertEquals(
            listOf(GrupoRenderizado(listOf(a)), GrupoRenderizado(listOf(b))),
            agruparTokens(listOf(a, b), furigana),
        )
    }

    @Test
    fun `dos tokens cruzados por una furigana quedan en un solo grupo`() {
        val ni = PalabraToken("二", null, null, inicio = 0, fin = 1, esContenido = true)
        val hito = PalabraToken("人", null, null, inicio = 1, fin = 2, esContenido = true)
        val furigana = listOf(Furigana(0, 2, "ふたり"))
        assertEquals(
            listOf(GrupoRenderizado(listOf(ni, hito))),
            agruparTokens(listOf(ni, hito), furigana),
        )
    }

    @Test
    fun `cluster de 2 tokens con una furigana cruzada da una sola lectura sin duplicar`() {
        val ni = PalabraToken("二", null, null, inicio = 0, fin = 1, esContenido = true)
        val hito = PalabraToken("人", null, null, inicio = 1, fin = 2, esContenido = true)
        val furigana = listOf(Furigana(0, 2, "ふたり"))
        val grupo = agruparTokens(listOf(ni, hito), furigana).single()
        assertEquals(
            listOf(Segmento("二人", "ふたり", ni)),
            segmentosDeGrupo(grupo, furigana),
        )
    }

    @Test
    fun `cluster mixto con furigana cruzada mas furigana interna no duplica ninguna lectura`() {
        // Grupo de 2 tokens: A="一" [0,1) y B="二三" [1,3). Una furigana cruza el límite
        // A-B (cubre [0,2), "一二") y OTRA cae enteramente dentro de B, en la parte que la
        // primera no cubre ([2,3), "三"). El bug haría que B repitiera "AB" + "C"
        // concatenado; el fix debe dar exactamente un segmento por tramo, sin repetir.
        val a = PalabraToken("一", null, null, inicio = 0, fin = 1, esContenido = true)
        val b = PalabraToken("二三", null, null, inicio = 1, fin = 3, esContenido = true)
        val cruza = Furigana(0, 2, "AB")
        val interna = Furigana(2, 3, "C")
        val grupo = agruparTokens(listOf(a, b), listOf(cruza, interna)).single()
        assertEquals(
            listOf(Segmento("一二", "AB", a), Segmento("三", "C", b)),
            segmentosDeGrupo(grupo, listOf(cruza, interna)),
        )
    }

    @Test
    fun `furigana solapada entre si (dato real inconsistente) no duplica el caracter compartido`() {
        // Caso REAL de momotaro.json (dispositivo, oración 「おばあさん、今帰ったよ。」):
        // dos furigana pisadas entre sí, [7,8,"いま"] para 今 y [7,9,"かえ"] para 帰 —
        // esta última debería empezar en 8, no en 7 (dato de catálogo mal alineado), pero
        // el render tiene que ser una red de seguridad: NUNCA repetir un carácter del
        // texto original aunque la furigana de entrada se solape consigo misma. Antes de
        // el fix, el segundo span (que arranca ANTES de donde terminó el cursor) volvía a
        // emitir "今" adentro de su propio segmento ("今帰"), duplicando el carácter.
        val hoy = PalabraToken("今", null, null, inicio = 7, fin = 8, esContenido = true)
        val volver = PalabraToken("帰った", null, null, inicio = 8, fin = 11, esContenido = true)
        val furigana = listOf(Furigana(7, 8, "いま"), Furigana(7, 9, "かえ"))
        val grupo = agruparTokens(listOf(hoy, volver), furigana).single()
        val segmentos = segmentosDeGrupo(grupo, furigana)
        assertEquals("今帰った", segmentos.joinToString("") { it.texto })
        assertEquals(
            listOf(Segmento("今", "いま", hoy), Segmento("帰", "かえ", volver), Segmento("った", null, volver)),
            segmentos,
        )
    }

    // --- calcularGruposFurigana: precómputo de grupos+segmentos (Plan 3.6 perf,
    // feedback de dispositivo: agruparTokens/segmentosDeGrupo corrían en cada
    // recomposición de item durante el scroll). La función combina las dos funciones
    // puras existentes UNA vez por oración; TextoConFurigana ya no las llama. ---

    @Test
    fun `calcularGruposFurigana combina agruparTokens y segmentosDeGrupo por grupo`() {
        val ni = PalabraToken("二", null, null, inicio = 0, fin = 1, esContenido = true)
        val hito = PalabraToken("人", null, null, inicio = 1, fin = 2, esContenido = true)
        val furigana = listOf(Furigana(0, 2, "ふたり"))
        val esperado = agruparTokens(listOf(ni, hito), furigana)
            .map { grupo -> GrupoFurigana(grupo, segmentosDeGrupo(grupo, furigana)) }
        assertEquals(esperado, calcularGruposFurigana(listOf(ni, hito), furigana))
    }

    @Test
    fun `calcularGruposFurigana con tokens sin furigana da un grupo por token con segmento sin lectura`() {
        val a = PalabraToken("桃", null, null, inicio = 0, fin = 1, esContenido = true)
        val b = PalabraToken("太郎", null, null, inicio = 1, fin = 3, esContenido = true)
        assertEquals(
            listOf(
                GrupoFurigana(GrupoRenderizado(listOf(a)), listOf(Segmento("桃", null, a))),
                GrupoFurigana(GrupoRenderizado(listOf(b)), listOf(Segmento("太郎", null, b))),
            ),
            calcularGruposFurigana(listOf(a, b), emptyList()),
        )
    }

    @Test
    fun `cargar precomputa gruposFurigana por oracion, igual que llamar calcularGruposFurigana a mano`() = runTest {
        val dao = ProgresoDaoFake()
        val vm = vmMomotaro(dao)
        vm.cargar(); advanceUntilIdle()
        val plana = vm.estado.value.oraciones.first()
        assertEquals(
            calcularGruposFurigana(plana.tokens, plana.oracion.furigana),
            plana.gruposFurigana,
        )
        // no es una lista vacía "por accidente": la oración de prueba tiene grupos reales.
        assertTrue(plana.gruposFurigana.isNotEmpty())
    }

    @Test
    fun `invariante- ninguna oracion de momotaro duplica ni omite texto al segmentar furigana`() {
        // Invariante de [TextoConFurigana]: para CUALQUIER oración real (tokenizada con
        // el Tokenizador real, JVM-puro), concatenar el texto de todos los segmentos de
        // todos los grupos, en orden, debe reproducir EXACTAMENTE `oracion.texto` — ni un
        // carácter de más (duplicado, el bug de "今今帰ったよ") ni de menos (omitido).
        val repo = HistoriasRepo(
            leerAsset = { n -> if (n == "historias/momotaro.json") momotaroJson else null },
            listarAssetsHistorias = { listOf("momotaro.json") },
            dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
        )
        val historia = repo.cargarHistoria("momotaro")!!
        val tokenizador = Tokenizador()
        var oracionesRevisadas = 0
        for (parrafo in historia.parrafos) {
            for (oracion in parrafo.oraciones) {
                val tokens = tokenizador.tokenizar(oracion.texto)
                val grupos = agruparTokens(tokens, oracion.furigana)
                val reconstruido = grupos.joinToString("") { grupo ->
                    segmentosDeGrupo(grupo, oracion.furigana).joinToString("") { it.texto }
                }
                assertEquals(
                    "oración \"${oracion.texto}\" quedó mal segmentada: \"$reconstruido\"",
                    oracion.texto,
                    reconstruido,
                )
                oracionesRevisadas++
            }
        }
        assertTrue("no se revisó ninguna oración", oracionesRevisadas > 0)
    }
}
