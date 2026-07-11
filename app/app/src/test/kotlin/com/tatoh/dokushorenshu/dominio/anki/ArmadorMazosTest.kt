package com.tatoh.dokushorenshu.dominio.anki

import com.tatoh.dokushorenshu.datos.DiccionarioFake
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.OracionEjemplo
import com.tatoh.dokushorenshu.datos.Palabra
import com.tatoh.dokushorenshu.datos.progreso.KanjiTocado
import com.tatoh.dokushorenshu.datos.progreso.PalabraTocada
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDaoFake
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ArmadorMazosTest {
    private val momotaroJson =
        javaClass.classLoader!!.getResourceAsStream("momotaro.json")!!.readBytes().decodeToString()

    private fun historiasRepo(): HistoriasRepo = HistoriasRepo(
        leerAsset = { nombre -> if (nombre == "historias/momotaro.json") momotaroJson else null },
        listarAssetsHistorias = { listOf("momotaro.json") },
        dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
        dirImportadas = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it },
    )

    private fun armador(
        dao: ProgresoDaoFake = ProgresoDaoFake(),
        diccionario: DiccionarioFake = DiccionarioFake(),
    ) = ArmadorMazos(dao, diccionario, historiasRepo())

    /** Repo con DOS historias locales (momotaro + una sintética derivada por
     *  reemplazo de texto) — necesario para probar el filtro de `seleccion`,
     *  que con una sola historia no distingue nada. */
    private fun historiasRepoDos(): HistoriasRepo {
        val otraJson = momotaroJson
            .replaceFirst("\"id\": \"momotaro\"", "\"id\": \"otra\"")
            .replaceFirst("\"titulo\": \"桃太郎\"", "\"titulo\": \"Otra historia\"")
        return HistoriasRepo(
            leerAsset = { nombre ->
                when (nombre) {
                    "historias/momotaro.json" -> momotaroJson
                    "historias/otra.json" -> otraJson
                    else -> null
                }
            },
            listarAssetsHistorias = { listOf("momotaro.json", "otra.json") },
            dirDescargas = File.createTempFile("desc", "").let { it.delete(); it.mkdirs(); it },
            dirImportadas = File.createTempFile("imp", "").let { it.delete(); it.mkdirs(); it },
        )
    }

    private fun armadorDos(
        dao: ProgresoDaoFake = ProgresoDaoFake(),
        diccionario: DiccionarioFake = DiccionarioFake(),
    ) = ArmadorMazos(dao, diccionario, historiasRepoDos())

    // --- armarWords: enriquecido con Diccionario ---

    @Test
    fun `palabra con definicion en el diccionario usa lectura y significados reales`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        val diccionario = DiccionarioFake().apply {
            palabras["犬"] = listOf(Palabra("犬", "いぬ", listOf("dog"), emptyList(), popularidad = 10))
        }
        val notas = armador(dao, diccionario).armarWords()
        assertEquals(1, notas.size)
        assertEquals("犬", notas[0].palabra)
        assertEquals("いぬ", notas[0].lectura)
        assertEquals("dog", notas[0].significados)
        assertEquals("", notas[0].tag)  // campo reservado, spec: Nota Words sin tag propio
    }

    @Test
    fun `termino en kana puro sin entrada propia cae al fallback por lectura`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "おじいさん", timestamp = 1L))
        val diccionario = DiccionarioFake().apply {
            // entrada indexada por 祖父 pero con lectura おじいさん — buscarPalabra("おじいさん")
            // da vacío, buscarPorLectura("おじいさん") la encuentra (mismo fallback que BuscadorPalabras).
            palabras["祖父"] = listOf(Palabra("祖父", "おじいさん", listOf("grandfather"), emptyList(), popularidad = 5))
        }
        val notas = armador(dao, diccionario).armarWords()
        assertEquals(1, notas.size)
        assertEquals("おじいさん", notas[0].lectura)
        assertEquals("grandfather", notas[0].significados)
    }

    @Test
    fun `palabra sin ninguna entrada da nota con lectura sola, nunca falla`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "未知語", timestamp = 1L))
        val notas = armador(dao, DiccionarioFake()).armarWords()
        assertEquals(1, notas.size)
        assertEquals("未知語", notas[0].palabra)
        assertEquals("未知語", notas[0].lectura)
        assertEquals("", notas[0].significados)
    }

    @Test
    fun `mismo termino tocado en dos historias da una sola nota`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        dao.registrarPalabra(PalabraTocada("urashima_taro", "犬", timestamp = 2L))
        val notas = armador(dao).armarWords()
        assertEquals(1, notas.size)
    }

    // --- armarKanji: solo taggeados, skip defensivo de los que salieron del db ---

    @Test
    fun `kanji visto pero sin tag no entra al mazo de kanji`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarAperturaKanji("見", 1L)  // visto, dificultad null
        val (notas, omitidos) = armador(dao).armarKanji()
        assertTrue(notas.isEmpty())
        assertEquals(0, omitidos)
    }

    @Test
    fun `kanji taggeado que ya no esta en el db se omite y cuenta`() = runTest {
        val dao = ProgresoDaoFake()
        dao.insertarKanjiSiNoExiste(KanjiTocado("洗", "hard", 1L))
        val (notas, omitidos) = armador(dao, DiccionarioFake()).armarKanji()  // dict fake vacío
        assertTrue(notas.isEmpty())
        assertEquals(1, omitidos)
    }

    @Test
    fun `kanji taggeado presente en el db arma la nota con sus lecturas`() = runTest {
        val dao = ProgresoDaoFake()
        dao.insertarKanjiSiNoExiste(KanjiTocado("洗", "hard", 1L))
        val diccionario = DiccionarioFake().apply {
            kanjis["洗"] = KanjiInfo("洗", listOf("wash"), listOf("セン"), listOf("あら.う"), jlpt = 3, strokes = 9)
        }
        val (notas, omitidos) = armador(dao, diccionario).armarKanji()
        assertEquals(1, notas.size)
        assertEquals(0, omitidos)
        assertEquals("洗", notas[0].kanji)
        assertEquals("セン", notas[0].onYomi)
        assertEquals("あら.う", notas[0].kunYomi)
        assertEquals("hard", notas[0].dificultad)
    }

    // --- oraciones: prioridad historias > Tatoeba, cap 5 ---

    @Test
    fun `historias no alcanzan el cap - Tatoeba rellena el resto`() = runTest {
        // "洗濯" aparece 3 veces en momotaro.json (fixture real) — se completa con
        // 2 oraciones de Tatoeba (fake) hasta llegar a 5.
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "洗濯", timestamp = 1L))
        val diccionario = DiccionarioFake().apply {
            ejemplosPalabra["洗濯"] = listOf(
                OracionEjemplo("洗濯物を干す。", "Hang out the laundry."),
                OracionEjemplo("洗濯機が壊れた。", "The washing machine broke."),
                OracionEjemplo("これは使われない。", "This one is not used (excede el cap)."),
            )
        }
        val notas = armador(dao, diccionario).armarWords()
        val oraciones = notas.single { it.palabra == "洗濯" }.oraciones
        assertEquals(5, oraciones.size)
        assertEquals(3, oraciones.count { !it.contains("<br>") })  // de las historias, ruby sin <br>
        assertEquals(2, oraciones.count { it.contains("<br>") })   // relleno Tatoeba, "jp<br>en"
        // prioridad: las 3 primeras son de historias, las 2 últimas de Tatoeba
        assertTrue(oraciones.take(3).none { it.contains("<br>") })
        assertTrue(oraciones.takeLast(2).all { it.contains("<br>") })
    }

    @Test
    fun `relleno Tatoeba escapa HTML igual que las oraciones de historias`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬犬犬", timestamp = 1L))  // sin match en historias
        val diccionario = DiccionarioFake().apply {
            ejemplosPalabra["犬犬犬"] = listOf(
                OracionEjemplo("a<b>c", "x & y <script>"),
            )
        }
        val notas = armador(dao, diccionario).armarWords()
        val oraciones = notas.single { it.palabra == "犬犬犬" }.oraciones
        assertEquals(listOf("a&lt;b&gt;c<br>x &amp; y &lt;script&gt;"), oraciones)
    }

    @Test
    fun `cap de 5 oraciones aunque las historias tengan muchas mas coincidencias`() = runTest {
        // "桃太郎" aparece 34 veces en momotaro.json — nunca se llama a Tatoeba.
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "桃太郎", timestamp = 1L))
        val notas = armador(dao).armarWords()
        val oraciones = notas.single { it.palabra == "桃太郎" }.oraciones
        assertEquals(5, oraciones.size)
        assertTrue(oraciones.none { it.contains("<br>") })
    }

    // --- armar(): combina ambos mazos ---

    @Test
    fun `armar combina notasWords y notasKanji con el contador de omitidos`() = runTest {
        val dao = ProgresoDaoFake()
        dao.registrarPalabra(PalabraTocada("momotaro", "犬", timestamp = 1L))
        dao.insertarKanjiSiNoExiste(KanjiTocado("見", "easy", 1L))  // fuera del dict fake -> omitido
        val resultado = armador(dao, DiccionarioFake()).armar()
        assertEquals(1, resultado.notasWords.size)
        assertTrue(resultado.notasKanji.isEmpty())
        assertEquals(1, resultado.kanjisOmitidos)
    }

    // --- armarHistorias (Plan 4a.1) ---

    @Test
    fun `armarHistorias arma un mazo por historia con kanjis en orden de primera aparicion`() = runTest {
        val dao = ProgresoDaoFake()
        val diccionario = DiccionarioFake().apply { todosLosKanjisConocidos = true }
        val resultado = armador(dao, diccionario).armarHistorias()
        val mazo = resultado.mazos.single()
        assertEquals("momotaro", mazo.idHistoria)
        assertEquals("桃太郎", mazo.titulo)
        assertEquals(217, mazo.notas.size)  // kanjis únicos reales del fixture (= kanjis_unicos del catálogo)
        assertEquals(listOf("山", "刈", "川", "洗", "濯"), mazo.notas.take(5).map { it.kanji })
        assertEquals(0, resultado.kanjisOmitidos)
    }

    @Test
    fun `armarHistorias usa guid por historia y oraciones solo de esa historia sin Tatoeba`() = runTest {
        val dao = ProgresoDaoFake()
        val diccionario = DiccionarioFake().apply {
            todosLosKanjisConocidos = true
            // Tatoeba NUNCA debe consultarse para mazos de historias:
            ejemplosKanji["刈"] = listOf(OracionEjemplo("この芝を刈る。", "Mow this lawn."))
        }
        val mazo = armador(dao, diccionario).armarHistorias().mazos.single()
        val nota = mazo.notas.single { it.kanji == "刈" }
        assertEquals("story:momotaro:刈", nota.claveGuid)
        assertEquals(1, nota.oraciones.size)              // 刈 está en 1 sola oración del fixture
        assertTrue(nota.oraciones.single().contains("<ruby>"))
        assertFalse(nota.oraciones.single().contains("<br>"))  // sin relleno Tatoeba (formato jp<br>en)
    }

    @Test
    fun `armarHistorias hereda el tag del usuario y deja vacia la dificultad sin tag`() = runTest {
        val dao = ProgresoDaoFake()
        dao.insertarKanjiSiNoExiste(KanjiTocado("洗", "hard", 1L))
        val diccionario = DiccionarioFake().apply { todosLosKanjisConocidos = true }
        val notas = armador(dao, diccionario).armarHistorias().mazos.single().notas
        assertEquals("hard", notas.single { it.kanji == "洗" }.dificultad)
        assertEquals("", notas.single { it.kanji == "山" }.dificultad)
    }

    @Test
    fun `armarHistorias omite y cuenta kanjis fuera del diccionario`() = runTest {
        val dao = ProgresoDaoFake()
        val diccionario = DiccionarioFake()  // sin todosLosKanjisConocidos: solo conoce lo cargado a mano
        diccionario.kanjis["山"] = KanjiInfo("山", listOf("mountain"), listOf("サン"), listOf("やま"), null, null)
        val resultado = armador(dao, diccionario).armarHistorias()
        val mazo = resultado.mazos.single()
        assertEquals(listOf("山"), mazo.notas.map { it.kanji })
        assertEquals(216, resultado.kanjisOmitidos)  // 217 únicos - 1 conocido
    }

    // --- armarHistorias con seleccion + resumenHistorias (Plan 4b Task 9) ---

    @Test
    fun `armarHistorias con seleccion filtra los mazos`() = runTest {
        val diccionario = DiccionarioFake().apply { todosLosKanjisConocidos = true }
        val armadorConDos = armadorDos(diccionario = diccionario)
        val todas = armadorConDos.armarHistorias()
        assertEquals(2, todas.mazos.size)
        val unId = todas.mazos.first().idHistoria
        val filtrado = armadorConDos.armarHistorias(seleccion = setOf(unId))
        assertEquals(listOf(unId), filtrado.mazos.map { it.idHistoria })
    }

    @Test
    fun `resumenHistorias devuelve id y titulo de las locales`() {
        val resumen = armadorDos().resumenHistorias()
        assertEquals(2, resumen.size)
        assertTrue(resumen.all { it.id.isNotBlank() && it.titulo.isNotBlank() })
        assertEquals(setOf("momotaro", "otra"), resumen.map { it.id }.toSet())
    }
}
