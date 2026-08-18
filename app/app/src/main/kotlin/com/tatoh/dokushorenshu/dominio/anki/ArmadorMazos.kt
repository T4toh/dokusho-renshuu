package com.tatoh.dokushorenshu.dominio.anki

import com.tatoh.dokushorenshu.datos.Diccionario
import com.tatoh.dokushorenshu.datos.Historia
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.datos.OracionEjemplo
import com.tatoh.dokushorenshu.datos.Palabra
import com.tatoh.dokushorenshu.datos.progreso.KanjiTocado
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDao

/** Cap de oraciones por nota. En armarWords/armarKanji: historias primero,
 *  Tatoeba rellena el resto (spec 4a). En los mazos por historia
 *  (armarHistorias): cap duro sin relleno — solo oraciones de esa historia
 *  (spec 4a.1). */
private const val CAP_ORACIONES = 5

/** Cuántas candidatas de Tatoeba se piden por cada oración que entra en la nota,
 *  para tener de dónde elegir las más simples (ver `masSimples`). */
private const val FACTOR_CANDIDATAS = 4

/** Cuántas glosas de la palabra entran en la tarjeta de kanji: la entrada de
 *  Jitendex de un verbo común trae 10+ y tapan la tarjeta. */
private const val GLOSAS_EN_TARJETA = 3

/** Resultado combinado de armar los dos mazos. `kanjisOmitidos` cuenta kanjis
 *  taggeados que ya no están en el diccionario (release nuevo, entrada
 *  movida/borrada) — el export nunca aborta por esto, solo informa (spec
 *  "Manejo de errores": "exported N, skipped M"). */
data class ResultadoArmado(
    val notasWords: List<NotaWords>,
    val notasKanji: List<NotaKanji>,
    val kanjisOmitidos: Int,
)

/** Un mazo de pre-lectura por historia (Plan 4a.1): todos los kanjis únicos de la
 *  historia en orden de primera aparición, oraciones SOLO de esa historia. */
data class MazoHistoria(val idHistoria: String, val titulo: String, val notas: List<NotaKanji>)

data class ResultadoHistorias(val mazos: List<MazoHistoria>, val kanjisOmitidos: Int)

/** Id/título/autor/dificultad de una historia local — lo que necesita la pantalla de Export
 *  para listar checkboxes sin cargar la historia completa (parrafos/oraciones). */
data class HistoriaResumen(val id: String, val titulo: String, val autor: String, val dificultad: String)

/** Junta los datos ya persistidos (Room + diccionario offline + historias
 *  locales) en las notas que consume `EscritorApkg`. Sin conocimiento de Anki:
 *  solo arma modelos de dominio — `ModeloNotas.kt` decide templates/formato de
 *  campo (Tasks 1-2). */
class ArmadorMazos(
    private val progresoDao: ProgresoDao,
    private val diccionario: Diccionario,
    private val historiasRepo: HistoriasRepo,
) {
    /** Id/título de cada historia local, en el mismo orden que `historiasLocales()`
     *  — la pantalla de Export lo usa para listar checkboxes de selección
     *  (Plan 4b Task 9) y para el count (`.size`), reemplazando al viejo
     *  `contarHistoriasLocales()` (ahora el VM ya necesita esta lista de todos
     *  modos, así que contar aparte sería I/O duplicado). */
    fun resumenHistorias(): List<HistoriaResumen> =
        historiasRepo.historiasLocales().map { HistoriaResumen(it.id, it.titulo, it.autor, it.dificultad) }

    /** Arma ambos mazos leyendo las historias locales una sola vez (evita I/O
     *  duplicado: `historiasLocales()` re-lee todos los JSON de assets/filesDir
     *  en cada llamada). */
    suspend fun armar(): ResultadoArmado {
        val historias = historiasRepo.historiasLocales()
        val notasWords = armarWords(historias)
        val (notasKanji, omitidos) = armarKanji(historias)
        return ResultadoArmado(notasWords, notasKanji, omitidos)
    }

    /** Una nota por término único tocado — `palabras_tocadas` tiene primary key
     *  (idHistoria, termino), la misma palabra puede repetirse en varias
     *  historias y no debe duplicar nota. */
    suspend fun armarWords(historias: List<Historia> = historiasRepo.historiasLocales()): List<NotaWords> {
        val terminos = progresoDao.todasPalabras().map { it.termino }.distinct()
        return terminos.map { termino -> armarNotaWords(termino, historias) }
    }

    /** Solo kanjis taggeados (dificultad != null); uno fuera del db se salta y
     *  cuenta en el segundo componente del `Pair` — nunca aborta el export. */
    suspend fun armarKanji(
        historias: List<Historia> = historiasRepo.historiasLocales(),
    ): Pair<List<NotaKanji>, Int> {
        var omitidos = 0
        val notas = progresoDao.kanjisTaggeados().mapNotNull { tocado ->
            val info = diccionario.buscarKanji(tocado.kanji)
            if (info == null) {
                omitidos++
                null
            } else {
                armarNotaKanji(tocado, info, historias)
            }
        }
        return notas to omitidos
    }

    /** Mazos de pre-lectura, uno por historia local. El tag del usuario (si existe)
     *  viaja en Dificultad; kanji sin entrada en el diccionario se omite y se
     *  cuenta (mismo criterio "exported N, skipped M" que armarKanji).
     *  `seleccion`: ids a incluir (checkboxes de la pantalla de Export); `null`
     *  arma todas las historias — compat con los llamadores/tests existentes. */
    suspend fun armarHistorias(
        historias: List<Historia> = historiasRepo.historiasLocales(),
        seleccion: Set<String>? = null,
    ): ResultadoHistorias {
        val elegidas = if (seleccion == null) historias else historias.filter { it.id in seleccion }
        val tagPorKanji = progresoDao.kanjisTaggeados()
            .associate { it.kanji to requireNotNull(it.dificultad) }
        var omitidos = 0
        val mazos = elegidas.map { historia ->
            val notas = kanjisEnOrdenDeAparicion(historia).mapNotNull { kanji ->
                val info = diccionario.buscarKanji(kanji)
                if (info == null) {
                    omitidos++
                    null
                } else {
                    val forma = formaDiccionario(info)
                    NotaKanji(
                        kanji = forma?.termino ?: kanji,
                        onYomi = info.onYomi.joinToString(" ・ "),
                        kunYomi = kunDeTarjeta(info, forma),
                        significados = significadosDeTarjeta(info, forma),
                        dificultad = tagPorKanji[kanji] ?: "",
                        oraciones = oracionesDeLaHistoria(historia, kanji),
                        claveGuidPropia = "story:${historia.id}:$kanji",
                    )
                }
            }
            MazoHistoria(historia.id, historia.titulo, notas)
        }
        return ResultadoHistorias(mazos, omitidos)
    }

    /** Kanjis únicos en orden de primera aparición — el mazo se estudia en el
     *  orden en que se van a encontrar leyendo. */
    private fun kanjisEnOrdenDeAparicion(historia: Historia): List<String> {
        val vistos = LinkedHashSet<Char>()
        for (parrafo in historia.parrafos)
            for (oracion in parrafo.oraciones)
                for (c in oracion.texto)
                    if (esKanji(c)) vistos.add(c)
        return vistos.map { it.toString() }
    }

    /** Oraciones de ESA historia solamente (spec 4a.1: sin relleno Tatoeba — el
     *  punto del mazo es prepararse para ese texto). */
    private fun oracionesDeLaHistoria(historia: Historia, kanji: String): List<String> {
        val candidatas = historia.parrafos.asSequence()
            .flatMap { it.oraciones.asSequence() }
            .filter { it.texto.contains(kanji) }
            .toList()
        return masSimples(candidatas, kanji) { it.texto }.map { oracionDeTarjeta(it, kanji) }
    }

    /** Las [cap] candidatas más simples: primero las que traen menos kanjis ajenos
     *  al objetivo (y más fáciles según JLPT), desempatando por oración más corta.
     *  Feedback de uso 2026-08-18: "no usar ejemplos con kanjis complejos o
     *  múltiples". Nunca EXCLUYE: si todas son complejas igual salen las mejores
     *  del lote — un kanji sin ejemplos simples es peor que uno con ejemplos
     *  difíciles. El orden es estable, así que a igual puntaje se respeta el orden
     *  de lectura / el de Tatoeba. */
    private fun <T> masSimples(
        candidatas: List<T>,
        objetivo: String,
        cap: Int = CAP_ORACIONES,
        texto: (T) -> String,
    ): List<T> =
        candidatas.sortedWith(
            compareBy({ puntajeSimplicidad(texto(it), objetivo, ::jlptDe) }, { texto(it).length }),
        ).take(cap)

    /** JLPT del kanji, memoizado: el puntaje consulta el mismo kanji una vez por
     *  oración candidata y `buscarKanji` pega al SQLite. El export es one-shot, el
     *  contenido del db no cambia mientras corre. */
    private val jlptPorKanji = mutableMapOf<String, Int?>()

    private fun jlptDe(kanji: String): Int? =
        if (jlptPorKanji.containsKey(kanji)) jlptPorKanji[kanji]
        else diccionario.buscarKanji(kanji)?.jlpt.also { jlptPorKanji[kanji] = it }

    private fun esKanji(c: Char): Boolean = esKanjiChar(c)

    private fun armarNotaWords(termino: String, historias: List<Historia>): NotaWords {
        // buscarPalabra por superficie; tokens en kana puro sin entrada propia
        // (p.ej. おじいさん) caen al índice de lectura — mismo fallback que
        // BuscadorPalabras (Plan 3.5 Frente C).
        val palabra = diccionario.buscarPalabra(termino).firstOrNull()
            ?: diccionario.buscarPorLectura(termino).firstOrNull()
        return NotaWords(
            palabra = termino,
            lectura = palabra?.lectura ?: termino,
            significados = palabra?.significados?.joinToString("; ") ?: "",
            tag = "",  // campo reservado vacío — spec: "Nota Words ... Tag (vacío)"
            oraciones = armarOraciones(historias, termino) { limite ->
                diccionario.oracionesDePalabra(termino, limite)
            },
        )
    }

    private fun armarNotaKanji(tocado: KanjiTocado, info: KanjiInfo, historias: List<Historia>): NotaKanji {
        val forma = formaDiccionario(info)
        return NotaKanji(
            kanji = forma?.termino ?: tocado.kanji,
            // ModeloNotas espera strings ya formateados (contrato de Task 1)
            onYomi = info.onYomi.joinToString(" ・ "),
            kunYomi = kunDeTarjeta(info, forma),
            significados = significadosDeTarjeta(info, forma),
            dificultad = requireNotNull(tocado.dificultad) {
                "kanjisTaggeados() no debería traer dificultad null"
            },
            // Las oraciones se buscan y resaltan por el KANJI, no por la forma de
            // diccionario: así una oración con 刈り sigue marcando 刈.
            oraciones = armarOraciones(historias, tocado.kanji) { limite ->
                diccionario.oracionesDeKanji(tocado.kanji, limite)
            },
            // El guid sigue atado al kanji pelado: si colgara de la forma de
            // diccionario, el primer export tras este cambio duplicaría cada nota
            // en vez de actualizarla.
            claveGuidPropia = "kanji:${tocado.kanji}",
        )
    }

    /** Forma de diccionario del kanji (feedback de uso 2026-08-18, criterio Kaishi):
     *  un verbo/adjetivo va como 刈る, no como 刈 pelado; un sustantivo sin okurigana
     *  (山, 水) se queda como está. Los candidatos salen de las lecturas kun de
     *  KANJIDIC que marcan okurigana con punto (`か.る` → 刈 + る); las marcadas con
     *  guion (`-ゆ.き`, `おお-`) son sufijos/prefijos, no formas de diccionario.
     *
     *  Cuando el kanji da varias (食 → 食う y 食べる) gana la que más oraciones de
     *  ejemplo tiene en el db, que es el mejor proxy de frecuencia disponible:
     *  `popularidad` satura en 200 y empata (食う y 食べる, 分かる y 分かつ). A igual
     *  cantidad manda el orden de KANJIDIC (見る antes que 見える). */
    private fun formaDiccionario(info: KanjiInfo): Palabra? =
        formaPorKanji.getOrPut(info.kanji) {
            info.kunYomi
                .filter { '.' in it && '-' !in it }
                .map { info.kanji + it.substringAfter('.') }
                .distinct()
                .mapNotNull { candidato -> diccionario.buscarPalabra(candidato).maxByOrNull { it.popularidad } }
                .maxByOrNull { diccionario.oracionesDePalabra(it.termino).size }
        }

    private val formaPorKanji = mutableMapOf<String, Palabra?>()

    /** Con forma de diccionario en el frente, la línea kun pasa a ser la lectura de
     *  ESA palabra (刈る → かる): repetir `か.る` al lado de 刈る no agrega nada. */
    private fun kunDeTarjeta(info: KanjiInfo, forma: Palabra?): String =
        forma?.lectura ?: info.kunYomi.joinToString(" ・ ")

    /** Idem significados: los de la palabra ("to cut (grass, hair, etc.)") le ganan a
     *  los del kanji suelto ("reap, cut, clip") cuando el frente es la palabra. */
    private fun significadosDeTarjeta(info: KanjiInfo, forma: Palabra?): String =
        (forma?.significados?.take(GLOSAS_EN_TARJETA) ?: info.significados).joinToString("; ")

    /** Prioridad historias > Tatoeba, cap 5. Las oraciones de historias AHORA
     *  pueden llevar traducción (PR B): si la trae, va en un
     *  `<span class="traduccion">` junto al ruby (ver `oracionDeTarjeta`). Las
     *  de Tatoeba siempre traen traducción (inglés provisto por Tatoeba),
     *  mismo formato de span — sin el `<br>` viejo, la clase ya es
     *  `display:block` en el CSS del template. */
    private fun armarOraciones(
        historias: List<Historia>,
        termino: String,
        tatoeba: (limite: Int) -> List<OracionEjemplo>,
    ): List<String> {
        val candidatas = historias.asSequence()
            .flatMap { it.parrafos.asSequence() }
            .flatMap { it.oraciones.asSequence() }
            .filter { it.texto.contains(termino) }
            .toList()
        val deHistorias = masSimples(candidatas, termino) { it.texto }.map { oracionDeTarjeta(it, termino) }
        if (deHistorias.size >= CAP_ORACIONES) return deHistorias
        val faltan = CAP_ORACIONES - deHistorias.size
        // Se piden más candidatas de las que entran para poder elegir las simples
        // (el LIMIT del db no sabe de complejidad); si el db trae menos, no pasa nada.
        val relleno = masSimples(tatoeba(faltan * FACTOR_CANDIDATAS), termino, faltan) { it.japones }.map {
            val japones = oracionARubyHtml(Oracion(it.japones, emptyList()), objetivo = termino)
            """$japones<span class="traduccion">${escapeHtml(it.ingles)}</span>"""
        }
        return deHistorias + relleno
    }
}

/** Convierte una oración con spans de furigana fin-exclusivo a HTML con
 *  `<ruby>` (formato que Anki/AnkiDroid renderiza en cualquier cliente, a
 *  diferencia del filtro `{{furigana:}}` que depende del parsing de
 *  corchetes). Pura, sin dependencias de Android — testeable en JVM plano.
 *
 *  [objetivo] (backlog feedback de uso 2026-07-13): ocurrencias del
 *  término/kanji objetivo envueltas en `<b class="objetivo">`. El resalte se
 *  calcula por RANGOS sobre el texto original, así un objetivo que cruza el
 *  límite de un span de ruby se resalta por fragmentos (la lectura `rt`
 *  nunca se resalta). */
internal fun oracionARubyHtml(oracion: Oracion, objetivo: String? = null): String {
    val texto = oracion.texto
    val resaltes = rangosDeObjetivo(texto, objetivo)
    fun tramo(desde: Int, hasta: Int) = emitirConResalte(texto, desde, hasta, resaltes)
    val sb = StringBuilder()
    var cursor = 0
    for (f in oracion.furigana.sortedBy { it.inicio }) {
        // defensivo: spans solapados son un bug de datos conocido (ledger Plan
        // 3.6 — momotaro.json llegó a traer furigana solapada); se ignora el
        // segundo span en vez de lanzar con un rango de substring inválido.
        if (f.inicio < cursor) continue
        if (f.inicio > cursor) sb.append(tramo(cursor, f.inicio))
        sb.append("<ruby>").append(tramo(f.inicio, f.fin))
            .append("<rt>").append(escapeHtml(f.lectura)).append("</rt></ruby>")
        cursor = f.fin
    }
    if (cursor < texto.length) sb.append(tramo(cursor, texto.length))
    return sb.toString()
}

/** Oración de historia lista para el campo de la tarjeta: ruby + objetivo
 *  resaltado + traducción (si la historia la trae — PR B) en un span con la
 *  clase `.traduccion` ya definida en el CSS del template. */
internal fun oracionDeTarjeta(oracion: Oracion, objetivo: String): String {
    val ruby = oracionARubyHtml(oracion, objetivo)
    val traduccion = oracion.traduccion
        ?.let { """<span class="traduccion">${escapeHtml(it)}</span>""" } ?: ""
    return ruby + traduccion
}

/** Rangos [inicio, fin) de cada ocurrencia (no solapada) del objetivo. */
private fun rangosDeObjetivo(texto: String, objetivo: String?): List<IntRange> {
    if (objetivo.isNullOrEmpty()) return emptyList()
    val rangos = mutableListOf<IntRange>()
    var i = texto.indexOf(objetivo)
    while (i >= 0) {
        rangos.add(i until i + objetivo.length)
        i = texto.indexOf(objetivo, i + objetivo.length)
    }
    return rangos
}

/** Emite texto[desde, hasta) escapado, envolviendo en `<b class="objetivo">`
 *  las intersecciones con [resaltes]. */
private fun emitirConResalte(texto: String, desde: Int, hasta: Int, resaltes: List<IntRange>): String {
    if (resaltes.isEmpty()) return escapeHtml(texto.substring(desde, hasta))
    val sb = StringBuilder()
    var cursor = desde
    for (r in resaltes) {
        val ini = maxOf(r.first, cursor)
        val fin = minOf(r.last + 1, hasta)
        if (fin <= ini) continue
        if (ini > cursor) sb.append(escapeHtml(texto.substring(cursor, ini)))
        sb.append("""<b class="objetivo">""").append(escapeHtml(texto.substring(ini, fin))).append("</b>")
        cursor = fin
    }
    if (cursor < hasta) sb.append(escapeHtml(texto.substring(cursor, hasta)))
    return sb.toString()
}

private fun escapeHtml(texto: String): String =
    texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Cuánto "cuesta" leer [texto] a quien está estudiando [objetivo]: cada kanji
 *  distinto ajeno al objetivo suma 1 (son los "múltiples") más su complejidad
 *  según el nivel JLPT de KANJIDIC (4 = el más fácil, 1 = el más difícil; un
 *  kanji sin nivel sale del set JLPT y se cobra como el más difícil). Menor es
 *  mejor. Pura: el acceso al diccionario entra por [jlptDe]. */
internal fun puntajeSimplicidad(texto: String, objetivo: String, jlptDe: (String) -> Int?): Int =
    texto.filter(::esKanjiChar).toSet()
        .filterNot { objetivo.contains(it) }
        .sumOf { kanji ->
            1 + when (jlptDe(kanji.toString())) {
                4 -> 0
                3 -> 1
                2 -> 2
                else -> 3  // nivel 1 o fuera del set JLPT
            }
        }

// Mismo rango que BuscadorPalabras.esKanji (helper de 1 línea, duplicado a
// propósito para no acoplar dominio/anki con dominio/).
private fun esKanjiChar(c: Char): Boolean = c in '一'..'鿿'
