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
    // Grupos de renderizado (fix "furigana duplicada", Plan 3.6.pulido): cuando una
    // furigana cruza el límite entre dos tokens (p.ej. 二人 tokenizado 二+人 con una sola
    // ふたり que cubre ambos), esos tokens se agrupan y la lectura se alinea UNA sola vez
    // sobre el rango combinado, no una vez por token overlapeado.
    val grupos = if (furiganaActiva) {
        agruparTokens(tokens, oracion.furigana)
    } else {
        tokens.map { GrupoRenderizado(listOf(it)) }
    }
    FlowRow {
        for (grupo in grupos) {
            val segmentos = if (furiganaActiva) {
                segmentosDeGrupo(grupo, oracion.furigana)
            } else {
                grupo.tokens.map { Segmento(it.superficie, null, it) }
            }
            // El Row entero (todos los sub-segmentos del grupo) es un único hijo de
            // FlowRow: el grupo nunca se parte entre líneas al wrapear (mismo criterio
            // que antes tenía cada token individual). El tap target es POR SEGMENTO, no
            // por grupo: cada segmento sabe a qué token pertenece (ver [Segmento.token])
            // así que un cluster de varios tokens conserva un área tappeable por token.
            Row {
                for (segmento in segmentos) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = if (segmento.token.esContenido && onTapPalabra != null) {
                            Modifier.clickable { onTapPalabra(segmento.token) }
                        } else Modifier,
                    ) {
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

/** Un sub-tramo del `superficie` de un token (o de un cluster de tokens, ver
 *  [GrupoRenderizado]) con su propia lectura de ruby (o null si ese tramo no tiene
 *  furigana propia, p.ej. la okurigana de un verbo). [token] es el token DUEÑO del
 *  segmento a efectos de tap: para un segmento que cae dentro de un solo token es ese
 *  token; para un segmento de furigana que cruza el límite entre varios tokens del
 *  mismo grupo (ver [agruparTokens]) es el primero (más a la izquierda) de ellos. */
internal data class Segmento(val texto: String, val lectura: String?, val token: PalabraToken)

/** Una corrida MAXIMAL de tokens consecutivos que se renderiza como una sola unidad
 *  porque alguna furigana cruza el límite entre ellos (fix del bug "furigana
 *  duplicada", Plan 3.6.pulido: 二人 tokenizado 二+人 con una sola ふたり que cubre
 *  ambos tokens antes renderizaba "ふたりふたり", una vez por cada token overlapeado).
 *  Un token sin ninguna furigana cruzada en sus bordes queda en un grupo de tamaño 1
 *  (comportamiento sin cambios respecto de antes). El grupo entero es la unidad que
 *  [TextoConFurigana] usa para wrappear en `FlowRow` — nunca se parte entre líneas. */
internal data class GrupoRenderizado(val tokens: List<PalabraToken>) {
    val inicio: Int get() = tokens.first().inicio
    val fin: Int get() = tokens.last().fin
}

/** Agrupa tokens consecutivos uniendo los que están unidos por una furigana que cruza
 *  el límite entre ambos (`f.inicio < limite && f.fin > limite`, con `limite` el punto
 *  de corte entre un token y el siguiente). La unión es transitiva: si una furigana
 *  cruza A-B y otra cruza B-C, A, B y C terminan en el mismo grupo. */
internal fun agruparTokens(tokens: List<PalabraToken>, furigana: List<Furigana>): List<GrupoRenderizado> {
    if (tokens.isEmpty()) return emptyList()
    val grupos = mutableListOf(mutableListOf(tokens.first()))
    for (i in 1 until tokens.size) {
        val limite = tokens[i - 1].fin // == tokens[i].inicio (tokens contiguos)
        val cruza = furigana.any { it.inicio < limite && it.fin > limite }
        if (cruza) {
            grupos.last().add(tokens[i])
        } else {
            grupos.add(mutableListOf(tokens[i]))
        }
    }
    return grupos.map { GrupoRenderizado(it) }
}

/** Parte el rango combinado de un [GrupoRenderizado] en sub-segmentos alineados
 *  EXACTAMENTE a los spans de furigana que caen dentro del grupo (fix del bug "furigana
 *  corrida": antes toda la lectura se centraba sobre el token completo, p.ej. か quedaba
 *  centrada sobre 刈り en vez de sobre 刈; y fix "furigana duplicada": una furigana que
 *  cruza el límite entre tokens ahora queda contenida ENTERA dentro del grupo, así que
 *  se alinea una sola vez sobre el rango combinado en vez de repetirse por token).
 *
 *  Si una furigana solapada NO cae completamente dentro de los límites del GRUPO —no
 *  debería pasar porque tokens y furigana vienen del mismo texto y toda furigana que
 *  cruza un límite entre tokens los agrupa (ver [agruparTokens]), pero se guarda como
 *  red de seguridad ante datos inconsistentes— se recorta al rango del grupo en vez de
 *  desbordarlo. */
internal fun segmentosDeGrupo(grupo: GrupoRenderizado, furigana: List<Furigana>): List<Segmento> {
    val inicioGrupo = grupo.inicio
    val finGrupo = grupo.fin
    val solapadas = furigana
        .filter { it.inicio < finGrupo && it.fin > inicioGrupo }
        .sortedBy { it.inicio }
    if (solapadas.isEmpty()) return grupo.tokens.map { Segmento(it.superficie, null, it) }

    val superficie = grupo.tokens.joinToString("") { it.superficie }
    fun subtramo(desde: Int, hasta: Int) = superficie.substring(desde - inicioGrupo, hasta - inicioGrupo)
    // Token dueño de una posición: el primero (más a la izquierda) cuyo rango la cubre.
    fun tokenEn(pos: Int): PalabraToken = grupo.tokens.first { pos < it.fin }

    val segmentos = mutableListOf<Segmento>()
    var cursor = inicioGrupo
    for (f in solapadas) {
        val desde = maxOf(f.inicio, inicioGrupo)
        val hasta = minOf(f.fin, finGrupo)
        if (desde > cursor) segmentos.add(Segmento(subtramo(cursor, desde), null, tokenEn(cursor)))
        segmentos.add(Segmento(subtramo(desde, hasta), f.lectura, tokenEn(desde)))
        cursor = hasta
    }
    if (cursor < finGrupo) segmentos.add(Segmento(subtramo(cursor, finGrupo), null, tokenEn(cursor)))
    return segmentos
}
