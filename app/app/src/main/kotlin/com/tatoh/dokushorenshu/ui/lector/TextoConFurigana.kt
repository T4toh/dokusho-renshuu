package com.tatoh.dokushorenshu.ui.lector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.tatoh.dokushorenshu.datos.Furigana
import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.dominio.PalabraToken

/** Render por token: columna [furigana chica / superficie]. La furigana viene
 *  de la pre-alineada del JSON (spans solapados con el token), NUNCA de Kuromoji.
 *
 *  Todas las oraciones se renderizan al MISMO tamaño (headlineMedium / furigana
 *  12.sp), foco o no — fix del bug "la animación es confusa" (Plan 3.6 feedback):
 *  antes la oración enfocada crecía y la anterior encogía, cambiando la altura del
 *  item en la `LazyColumn` a mitad de scroll, lo que se sentía como que la vista
 *  entera (o la oración) se arrastraba sola. Con tamaño fijo la altura de cada item
 *  es constante y el foco se indica solo con color/alpha en el llamador
 *  ([ListaOracionesLibre]), nunca con un reflow de layout. */
@Composable
fun TextoConFurigana(
    oracion: Oracion,
    tokens: List<PalabraToken>,
    furiganaActiva: Boolean,
    onTapPalabra: ((PalabraToken) -> Unit)?,
) {
    val estiloBase = MaterialTheme.typography.headlineMedium
    FlowRow {
        for (token in tokens) {
            val segmentos = if (furiganaActiva) {
                segmentosDeToken(token, oracion.furigana)
            } else {
                listOf(Segmento(token.superficie, null))
            }
            // El Row entero (todos los sub-segmentos del token) es el tap target y es
            // un único hijo de FlowRow: el token nunca se parte entre líneas al wrapear.
            Row(
                modifier = if (token.esContenido && onTapPalabra != null) {
                    Modifier.clickable { onTapPalabra(token) }
                } else Modifier,
            ) {
                for (segmento in segmentos) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = segmento.lectura ?: " ",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = segmento.texto, style = estiloBase)
                    }
                }
            }
        }
    }
}

/** Concatena las lecturas de furigana cuyo span [inicio, fin) se solapa con el token.
 *  Usado solo como fallback para la hoja de detalle (nunca para el render de ruby). */
internal fun lecturaDelToken(oracion: Oracion, token: PalabraToken): String? =
    oracion.furigana
        .filter { it.inicio < token.fin && it.fin > token.inicio }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("") { it.lectura }

/** Un sub-tramo del `superficie` de un token con su propia lectura de ruby (o null si
 *  ese tramo no tiene furigana propia, p.ej. la okurigana de un verbo). */
internal data class Segmento(val texto: String, val lectura: String?)

/** Parte `token.superficie` en sub-segmentos alineados EXACTAMENTE a los spans de
 *  furigana que caen dentro del token (fix del bug "furigana corrida": antes toda la
 *  lectura se centraba sobre el token completo, p.ej. か quedaba centrada sobre 刈り en
 *  vez de sobre 刈).
 *
 *  Si una furigana solapada NO cae completamente dentro de los límites del token (cruza
 *  el borde) — no debería pasar porque tokens y furigana vienen del mismo texto, pero se
 *  guarda como red de seguridad ante datos inconsistentes — se cae al comportamiento v1:
 *  un solo segmento con el token entero y la lectura concatenada de esas furigana. */
internal fun segmentosDeToken(token: PalabraToken, furigana: List<Furigana>): List<Segmento> {
    val solapadas = furigana
        .filter { it.inicio < token.fin && it.fin > token.inicio }
        .sortedBy { it.inicio }
    if (solapadas.isEmpty()) return listOf(Segmento(token.superficie, null))

    val cruzaLimite = solapadas.any { it.inicio < token.inicio || it.fin > token.fin }
    if (cruzaLimite) {
        return listOf(Segmento(token.superficie, solapadas.joinToString("") { it.lectura }))
    }

    val segmentos = mutableListOf<Segmento>()
    var cursor = token.inicio
    fun subtramo(desde: Int, hasta: Int) =
        token.superficie.substring(desde - token.inicio, hasta - token.inicio)
    for (f in solapadas) {
        if (f.inicio > cursor) segmentos.add(Segmento(subtramo(cursor, f.inicio), null))
        segmentos.add(Segmento(subtramo(f.inicio, f.fin), f.lectura))
        cursor = f.fin
    }
    if (cursor < token.fin) segmentos.add(Segmento(subtramo(cursor, token.fin), null))
    return segmentos
}
