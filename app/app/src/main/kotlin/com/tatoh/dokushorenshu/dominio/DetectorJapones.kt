package com.tatoh.dokushorenshu.dominio

/** Heurística del spec 4b (§Manejo de errores): si menos del 50% de los
 *  caracteres visibles son japoneses, el import avisa antes de guardar. */
object DetectorJapones {
    private const val UMBRAL = 0.5

    fun pareceJapones(texto: String): Boolean {
        val visibles = texto.filterNot { it.isWhitespace() }
        if (visibles.isEmpty()) return false
        val japoneses = visibles.count(::esJapones)
        return japoneses.toDouble() / visibles.length >= UMBRAL
    }

    private fun esJapones(c: Char): Boolean =
        c in '぀'..'ヿ' ||  // hiragana + katakana
            c in '一'..'鿿' ||       // CJK unificado (4E00..9FFF)
            c in '　'..'〿' ||  // puntuación CJK: 。「」『』（）…
            c in '！'..'｠'     // formas fullwidth: ！？０-９Ａ-Ｚ
}
