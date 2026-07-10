package com.tatoh.dokushorenshu.dominio.anki

import com.tatoh.dokushorenshu.datos.Diccionario
import com.tatoh.dokushorenshu.datos.Historia
import com.tatoh.dokushorenshu.datos.HistoriasRepo
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.datos.OracionEjemplo
import com.tatoh.dokushorenshu.datos.progreso.KanjiTocado
import com.tatoh.dokushorenshu.datos.progreso.ProgresoDao

/** Cap de oraciones por nota (spec Plan 4a): historias locales primero,
 *  Tatoeba rellena el resto hasta llegar acá. */
private const val CAP_ORACIONES = 5

/** Resultado combinado de armar los dos mazos. `kanjisOmitidos` cuenta kanjis
 *  taggeados que ya no están en el diccionario (release nuevo, entrada
 *  movida/borrada) — el export nunca aborta por esto, solo informa (spec
 *  "Manejo de errores": "exported N, skipped M"). */
data class ResultadoArmado(
    val notasWords: List<NotaWords>,
    val notasKanji: List<NotaKanji>,
    val kanjisOmitidos: Int,
)

/** Junta los datos ya persistidos (Room + diccionario offline + historias
 *  locales) en las notas que consume `EscritorApkg`. Sin conocimiento de Anki:
 *  solo arma modelos de dominio — `ModeloNotas.kt` decide templates/formato de
 *  campo (Tasks 1-2). */
class ArmadorMazos(
    private val progresoDao: ProgresoDao,
    private val diccionario: Diccionario,
    private val historiasRepo: HistoriasRepo,
) {
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

    private fun armarNotaKanji(tocado: KanjiTocado, info: KanjiInfo, historias: List<Historia>): NotaKanji =
        NotaKanji(
            kanji = tocado.kanji,
            // ModeloNotas espera strings ya formateados (contrato de Task 1)
            onYomi = info.onYomi.joinToString("、"),
            kunYomi = info.kunYomi.joinToString("、"),
            significados = info.significados.joinToString("; "),
            dificultad = requireNotNull(tocado.dificultad) {
                "kanjisTaggeados() no debería traer dificultad null"
            },
            oraciones = armarOraciones(historias, tocado.kanji) { limite ->
                diccionario.oracionesDeKanji(tocado.kanji, limite)
            },
        )

    /** Prioridad historias > Tatoeba, cap 5. Las oraciones de historias no
     *  llevan traducción (las historias no traducen); las de Tatoeba sí,
     *  formato `"oración<br>traducción"`. */
    private fun armarOraciones(
        historias: List<Historia>,
        termino: String,
        tatoeba: (limite: Int) -> List<OracionEjemplo>,
    ): List<String> {
        val deHistorias = historias.asSequence()
            .flatMap { it.parrafos.asSequence() }
            .flatMap { it.oraciones.asSequence() }
            .filter { it.texto.contains(termino) }
            .map { oracionARubyHtml(it) }
            .take(CAP_ORACIONES)
            .toList()
        if (deHistorias.size >= CAP_ORACIONES) return deHistorias
        val relleno = tatoeba(CAP_ORACIONES - deHistorias.size)
            .map { "${it.japones}<br>${it.ingles}" }
        return deHistorias + relleno
    }
}

/** Convierte una oración con spans de furigana fin-exclusivo a HTML con
 *  `<ruby>` (formato que Anki/AnkiDroid renderiza en cualquier cliente, a
 *  diferencia del filtro `{{furigana:}}` que depende del parsing de
 *  corchetes). Pura, sin dependencias de Android — testeable en JVM plano. */
internal fun oracionARubyHtml(oracion: Oracion): String {
    val texto = oracion.texto
    val sb = StringBuilder()
    var cursor = 0
    for (f in oracion.furigana.sortedBy { it.inicio }) {
        // defensivo: spans solapados son un bug de datos conocido (ledger Plan
        // 3.6 — momotaro.json llegó a traer furigana solapada); se ignora el
        // segundo span en vez de lanzar con un rango de substring inválido.
        if (f.inicio < cursor) continue
        if (f.inicio > cursor) sb.append(escapeHtml(texto.substring(cursor, f.inicio)))
        sb.append("<ruby>").append(escapeHtml(texto.substring(f.inicio, f.fin)))
            .append("<rt>").append(escapeHtml(f.lectura)).append("</rt></ruby>")
        cursor = f.fin
    }
    if (cursor < texto.length) sb.append(escapeHtml(texto.substring(cursor)))
    return sb.toString()
}

private fun escapeHtml(texto: String): String =
    texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
