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
}

/** Convierte katakana a hiragana carácter a carácter (desplazamiento fijo de 0x60 en
 *  el bloque Unicode, ァ 30A1 → ぁ 3041; caracteres fuera de ese rango, como el
 *  prolongador ー, pasan sin cambios). Top-level e `internal` (Plan 3.7: antes era
 *  `private fun` de [Tokenizador], donde solo convertía la lectura de Kuromoji; ahora
 *  la reusa también `particionarPorKatakana` en `ui/lector/TextoConFurigana.kt` para
 *  el ruby de katakana). Sacarla de la clase evita instanciar un [Tokenizador]
 *  completo —carga cara del diccionario Kuromoji, ~1s— solo para convertir texto. */
internal fun katakanaAHiragana(katakana: String): String =
    katakana.map { c ->
        if (c in 'ァ'..'ヶ') c - 0x60 else c  // ァ(30A1)→ぁ(3041)
    }.joinToString("")
