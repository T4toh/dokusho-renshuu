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
    )

    private fun armador(
        dao: ProgresoDaoFake = ProgresoDaoFake(),
        diccionario: DiccionarioFake = DiccionarioFake(),
    ) = ArmadorMazos(dao, diccionario, historiasRepo())

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
}
