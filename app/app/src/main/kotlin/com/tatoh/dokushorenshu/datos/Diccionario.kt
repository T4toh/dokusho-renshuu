package com.tatoh.dokushorenshu.datos

data class Palabra(
    val termino: String, val lectura: String?,
    val significados: List<String>, val tags: List<String>, val popularidad: Int,
)
data class KanjiInfo(
    val kanji: String, val significados: List<String>,
    val onYomi: List<String>, val kunYomi: List<String>,
    val jlpt: Int?, val strokes: Int?,
)
data class OracionEjemplo(val japones: String, val ingles: String)

interface Diccionario {
    fun buscarPalabra(termino: String): List<Palabra>
    /** Fallback para tokens en kana sin kanji (p.ej. おじいさん): busca por la
     *  columna lectura. Usa el índice idx_palabras_lectura del db (Frente A). */
    fun buscarPorLectura(lectura: String): List<Palabra>
    fun buscarKanji(kanji: String): KanjiInfo?
    fun oracionesDePalabra(termino: String, limite: Int = 3): List<OracionEjemplo>
    fun oracionesDeKanji(kanji: String, limite: Int = 3): List<OracionEjemplo>
}
