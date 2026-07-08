package com.tatoh.dokushorenshu.dominio

import com.atilika.kuromoji.ipadic.Tokenizer

/** Span [inicio, fin) exclusivo sobre el texto de la oración. */
data class PalabraToken(
    val superficie: String,
    val lecturaHiragana: String?,
    val formaBase: String?,
    val inicio: Int,
    val fin: Int,
    val esContenido: Boolean,
)

/** Wrapper de Kuromoji IPADIC. La instancia de Tokenizer es cara (~1s carga
 *  de diccionario): crear UNA por app (vive en el Contenedor). */
class Tokenizador {
    private val tokenizer = Tokenizer()

    // categorías gramaticales que la UI no hace tappeables
    private val noContenido = setOf("助詞", "助動詞", "記号")

    fun tokenizar(texto: String): List<PalabraToken> {
        if (texto.isEmpty()) return emptyList()
        return tokenizer.tokenize(texto).map { token ->
            PalabraToken(
                superficie = token.surface,
                lecturaHiragana = token.reading
                    ?.takeIf { it != "*" }
                    ?.let(::katakanaAHiragana),
                formaBase = token.baseForm?.takeIf { it != "*" },
                inicio = token.position,
                fin = token.position + token.surface.length,
                esContenido = token.partOfSpeechLevel1 !in noContenido,
            )
        }
    }

    private fun katakanaAHiragana(katakana: String): String =
        katakana.map { c ->
            if (c in 'ァ'..'ヶ') c - 0x60 else c  // ァ(30A1)→ぁ(3041)
        }.joinToString("")
}
