package com.tatoh.dokushorenshu.datos

/** Diccionario fake en memoria: compartido entre BuscadorPalabrasTest (Task 7)
 *  y DetalleKanjiViewModelTest (Task 11). */
class DiccionarioFake : Diccionario {
    val palabras = mutableMapOf<String, List<Palabra>>()
    val kanjis = mutableMapOf<String, KanjiInfo>()
    val ejemplosPalabra = mutableMapOf<String, List<OracionEjemplo>>()
    val ejemplosKanji = mutableMapOf<String, List<OracionEjemplo>>()

    override fun buscarPalabra(termino: String) = palabras[termino] ?: emptyList()
    override fun buscarKanji(kanji: String) = kanjis[kanji]
    override fun oracionesDePalabra(termino: String, limite: Int) =
        (ejemplosPalabra[termino] ?: emptyList()).take(limite)
    override fun oracionesDeKanji(kanji: String, limite: Int) =
        (ejemplosKanji[kanji] ?: emptyList()).take(limite)
}
