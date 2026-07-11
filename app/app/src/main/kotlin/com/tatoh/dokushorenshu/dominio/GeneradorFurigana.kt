package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.Furigana

/** Furigana automática para texto importado (Plan 4b): Kuromoji puro, sin
 *  alineador Aozora. Una terna por token con kanji; el ruby se recorta al
 *  núcleo kanji comparando superficie y lectura por ambos extremos (trim de
 *  okurigana: 走った/はしった → はし sobre 走). Si el recorte degenera, la
 *  terna cubre el token completo (degradación segura). */
class GeneradorFurigana(private val tokenizador: Tokenizador) {

    fun generar(oracion: String): List<Furigana> =
        tokenizador.tokenizar(oracion).mapNotNull(::ternaDelToken)

    private fun ternaDelToken(token: PalabraToken): Furigana? {
        val lectura = token.lecturaHiragana ?: return null
        if (token.superficie.none(::esKanji)) return null
        val superficieHira = katakanaAHiragana(token.superficie)
        var pre = 0
        while (pre < superficieHira.length && pre < lectura.length &&
            !esKanji(token.superficie[pre]) && superficieHira[pre] == lectura[pre]
        ) pre++
        var post = 0
        while (post < superficieHira.length - pre && post < lectura.length - pre &&
            !esKanji(token.superficie[token.superficie.length - 1 - post]) &&
            superficieHira[superficieHira.length - 1 - post] == lectura[lectura.length - 1 - post]
        ) post++
        val nucleo = token.superficie.substring(pre, token.superficie.length - post)
        val lecturaNucleo = lectura.substring(pre, lectura.length - post)
        return if (nucleo.any(::esKanji) && lecturaNucleo.isNotEmpty()) {
            Furigana(token.inicio + pre, token.fin - post, lecturaNucleo)
        } else {
            Furigana(token.inicio, token.fin, lectura)
        }
    }

    // Mismo rango que BuscadorPalabras/ArmadorMazos (helper de 1 línea,
    // duplicado a propósito para no acoplar módulos — convención del repo).
    private fun esKanji(c: Char): Boolean = c in '一'..'鿿'
}
