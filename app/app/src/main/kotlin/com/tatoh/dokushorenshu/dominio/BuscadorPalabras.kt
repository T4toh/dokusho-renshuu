package com.tatoh.dokushorenshu.dominio

import com.tatoh.dokushorenshu.datos.Diccionario
import com.tatoh.dokushorenshu.datos.KanjiInfo
import com.tatoh.dokushorenshu.datos.OracionEjemplo
import com.tatoh.dokushorenshu.datos.Palabra

data class ConsultaPalabra(
    val termino: String,
    val lecturaFallback: String?,
    val definiciones: List<Palabra>,
    val ejemplos: List<OracionEjemplo>,
    val kanjis: List<KanjiInfo>,
) {
    val sinDefinicion: Boolean get() = definiciones.isEmpty()
}

/** Resuelve un token contra el diccionario siguiendo el contrato del db (Plan 1):
 *  oracion_palabra solo indexa términos de 2-6 chars; 1 kanji → oracion_kanji. */
class BuscadorPalabras(private val diccionario: Diccionario) {

    fun consultar(token: PalabraToken): ConsultaPalabra {
        val definiciones = diccionario.buscarPalabra(token.superficie).ifEmpty {
            token.formaBase
                ?.takeIf { it != token.superficie }
                ?.let { diccionario.buscarPalabra(it) }
                ?: emptyList()
        }.ifEmpty {
            token.lecturaHiragana
                ?.let { diccionario.buscarPorLectura(it) }
                ?: emptyList()
        }
        val termino = definiciones.firstOrNull()?.termino ?: token.superficie
        val kanjisUnicos = token.superficie.filter(::esKanji).toList().distinct()

        val ejemplos = (
            if (termino.length in 2..6) diccionario.oracionesDePalabra(termino)
            else emptyList()
        ).ifEmpty {
            kanjisUnicos.firstOrNull()?.let { diccionario.oracionesDeKanji(it.toString()) }
                ?: emptyList()
        }

        return ConsultaPalabra(
            termino = termino,
            lecturaFallback = token.lecturaHiragana,
            definiciones = definiciones,
            ejemplos = ejemplos,
            kanjis = kanjisUnicos.mapNotNull { diccionario.buscarKanji(it.toString()) },
        )
    }

    private fun esKanji(c: Char): Boolean = c in '一'..'鿿'
}
