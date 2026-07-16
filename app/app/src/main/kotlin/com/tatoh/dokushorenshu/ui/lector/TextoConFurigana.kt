package com.tatoh.dokushorenshu.ui.lector

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.tatoh.dokushorenshu.datos.Furigana
import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.dominio.PalabraToken
import com.tatoh.dokushorenshu.dominio.katakanaAHiragana

/** Render por token: columna [furigana chica / superficie]. La furigana viene
 *  de la pre-alineada del JSON (spans solapados con el token), NUNCA de Kuromoji.
 *
 *  Todas las oraciones se renderizan al MISMO tamaño (headlineMedium / furigana
 *  12.sp), foco o no — fix del bug "la animación es confusa" (Plan 3.6 feedback):
 *  antes la oración enfocada crecía y la anterior encogía, cambiando la altura del
 *  item en la `LazyColumn` a mitad de scroll, lo que se sentía como que la vista
 *  entera (o la oración) se arrastraba sola. Con tamaño fijo la altura de cada item
 *  es constante y el foco se indica solo con color/alpha en el llamador
 *  ([ListaOracionesLibre]), nunca con un reflow de layout.
 *
 *  [gruposFurigana] viene PRECOMPUTADO por oración desde [LectorViewModel.cargar]
 *  (fix de performance, Plan 3.6 feedback de dispositivo): antes `agruparTokens` +
 *  `segmentosDeGrupo` corrían acá adentro, en CADA recomposición de item durante el
 *  scroll (alloc + O(n) por frame, para las ~decenas de oraciones visibles). Ahora se
 *  calculan una sola vez al cargar la historia y viajan en [OracionPlana]; este
 *  composable solo los recorre. Se usan SOLO si `furiganaActiva`: con la furigana
 *  apagada el criterio es otro (cada token en su propio grupo, sin agrupar por
 *  furigana cruzada — no tiene sentido agrupar lo que no se va a mostrar) y es tan
 *  barato (un `map` sin alocar sub-segmentos) que no hace falta precomputarlo. */
@Composable
fun TextoConFurigana(
    tokens: List<PalabraToken>,
    gruposFurigana: List<GrupoFurigana>,
    furiganaActiva: Boolean,
    // Katakana-ruby (Plan 3.7): decide SOLO el dibujo de Segmento.lecturaKana, ya
    // precomputada (Task 3) tanto en gruposFurigana como acá abajo — nunca dispara
    // trabajo nuevo. Independiente de furiganaActiva: cualquier combinación es válida.
    katakanaActiva: Boolean,
    onTapPalabra: ((PalabraToken) -> Unit)?,
    // Selección de rango (backlog feedback de uso 2026-07-13): long-press ancla la
    // selección (lo maneja el VM); acá solo se reporta el gesto y se pinta el fondo
    // de los tokens cuyo span cae dentro de rangoSeleccion (chars de la oración,
    // construido `inicio until fin` — IntRange con last INCLUSIVO). Defaults null:
    // los callsites sin selección (p.ej. previews de import) no cambian.
    onLongPressPalabra: ((PalabraToken) -> Unit)? = null,
    rangoSeleccion: IntRange? = null,
) {
    val estiloBase = MaterialTheme.typography.headlineMedium
    FlowRow {
        if (furiganaActiva) {
            for (grupo in gruposFurigana) {
                FilaGrupo(grupo.segmentos, estiloBase, katakanaActiva, onTapPalabra, onLongPressPalabra, rangoSeleccion)
            }
        } else {
            // Con la furigana apagada cada token es su propio "grupo" (sin cruzar
            // límites, ver doc de arriba). La partición de katakana sobre un único
            // segmento sin furigana es tan barata (scan lineal, sin diccionario) como
            // ya lo era construir el Segmento acá mismo: no hace falta precomputarla.
            for (token in tokens) {
                val segmentos = particionarPorKatakana(listOf(Segmento(token.superficie, null, token)))
                FilaGrupo(segmentos, estiloBase, katakanaActiva, onTapPalabra, onLongPressPalabra, rangoSeleccion)
            }
        }
    }
}

/** Un grupo de renderizado ([GrupoRenderizado]) junto con sus [segmentos] YA
 *  calculados (ver doc de [calcularGruposFurigana]). Público (no `internal`) porque
 *  viaja en [OracionPlana] y en la firma de [TextoConFurigana], ambos públicos. */
data class GrupoFurigana(val grupo: GrupoRenderizado, val segmentos: List<Segmento>)

/** Precomputa, para TODOS los grupos de una oración, el resultado de
 *  `agruparTokens` + `segmentosDeGrupo` — las dos funciones puras que antes corrían
 *  dentro de la composición de [TextoConFurigana] en cada recompose de item (fix de
 *  performance, Plan 3.6 feedback). Se llama UNA vez por oración desde
 *  [LectorViewModel.cargar] (en `ioDispatcher`), nunca en cada composición.
 *
 *  Plan 3.7 (katakana-ruby): cada grupo pasa además por [particionarPorKatakana],
 *  que parte los sub-segmentos SIN furigana en runs de katakana con su hiragana
 *  alineada ([Segmento.lecturaKana]). Corre siempre acá, independiente de cualquier
 *  toggle de UI (mismo criterio que la furigana: precómputo único, el toggle de
 *  `TextoConFurigana` solo decide si se DIBUJA, nunca si se calcula). */
internal fun calcularGruposFurigana(tokens: List<PalabraToken>, furigana: List<Furigana>): List<GrupoFurigana> =
    agruparTokens(tokens, furigana).map { grupo ->
        GrupoFurigana(grupo, particionarPorKatakana(segmentosDeGrupo(grupo, furigana)))
    }

// Rango Unicode de katakana convertible a hiragana (mismo rango que [katakanaAHiragana]).
private val RANGO_KATAKANA = 'ァ'..'ヶ'
private const val PROLONGADOR = 'ー'

/** Parte cada [Segmento] SIN furigana (`lectura == null`) en sub-tramos de katakana
 *  (con [Segmento.lecturaKana] calculada) y de no-katakana (sin ruby) — mecanismo de
 *  katakana-ruby del Plan 3.7. Un segmento con `lectura != null` queda SIEMPRE
 *  intacto: la furigana de kanji manda, nunca se re-particiona por katakana.
 *
 *  Un run de katakana EMPIEZA con un carácter convertible (ァ..ヶ) y puede seguir con
 *  el prolongador ー en el medio o al final, preservado tal cual (カード → かーど). Un
 *  ー sin katakana inmediatamente antes NO inicia run por sí solo — queda en el tramo
 *  de no-katakana, sin ruby (ver test `ー suelto sin katakana antes no inicia run`).
 *
 *  Conversión determinística carácter a carácter vía [katakanaAHiragana] — no
 *  requiere diccionario ni lecturas de Kuromoji, así que nunca falla ni crashea. */
internal fun particionarPorKatakana(segmentos: List<Segmento>): List<Segmento> =
    segmentos.flatMap { segmento ->
        if (segmento.lectura != null) listOf(segmento) else particionarTexto(segmento.texto, segmento.token)
    }

private fun particionarTexto(texto: String, token: PalabraToken): List<Segmento> {
    if (texto.isEmpty()) return emptyList()
    val resultado = mutableListOf<Segmento>()
    var i = 0
    while (i < texto.length) {
        if (texto[i] in RANGO_KATAKANA) {
            var j = i + 1
            while (j < texto.length && (texto[j] in RANGO_KATAKANA || texto[j] == PROLONGADOR)) j++
            val run = texto.substring(i, j)
            resultado.add(Segmento(run, null, token, katakanaAHiragana(run)))
            i = j
        } else {
            var j = i + 1
            while (j < texto.length && texto[j] !in RANGO_KATAKANA) j++
            resultado.add(Segmento(texto.substring(i, j), null, token))
            i = j
        }
    }
    return resultado
}

/** Un grupo entero (todos sus sub-segmentos) como único hijo de `FlowRow`: el grupo
 *  nunca se parte entre líneas al wrapear (mismo criterio que antes tenía cada token
 *  individual). El tap target es POR SEGMENTO, no por grupo: cada segmento sabe a
 *  qué token pertenece (ver [Segmento.token]) así que un cluster de varios tokens
 *  conserva un área tappeable por token. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilaGrupo(
    segmentos: List<Segmento>,
    estiloBase: TextStyle,
    katakanaActiva: Boolean,
    onTapPalabra: ((PalabraToken) -> Unit)?,
    onLongPressPalabra: ((PalabraToken) -> Unit)?,
    rangoSeleccion: IntRange?,
) {
    Row {
        for (segmento in segmentos) {
            // token seleccionado si su span [inicio, fin) pisa el rango de selección
            // (last es inclusivo: rango construido como `inicio until fin`).
            val seleccionado = rangoSeleccion != null &&
                segmento.token.inicio <= rangoSeleccion.last && segmento.token.fin > rangoSeleccion.first
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .then(
                        if (seleccionado) {
                            Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                        } else Modifier,
                    )
                    .then(
                        if (segmento.token.esContenido && onTapPalabra != null) {
                            Modifier.combinedClickable(
                                onClick = { onTapPalabra(segmento.token) },
                                onLongClick = onLongPressPalabra?.let { alMantener ->
                                    { alMantener(segmento.token) }
                                },
                            )
                        } else Modifier,
                    ),
            ) {
                // Furigana de kanji manda; si no hay, se dibuja lecturaKana SOLO
                // si el toggle de katakana está activo (Plan 3.7) — el dato ya
                // viene precomputado, esto nunca calcula nada.
                val ruby = segmento.lectura ?: (segmento.lecturaKana.takeIf { katakanaActiva })
                Text(
                    text = ruby ?: " ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = segmento.texto, style = estiloBase)
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
 *  mismo grupo (ver [agruparTokens]) es el primero (más a la izquierda) de ellos.
 *
 *  [lecturaKana] (Plan 3.7, katakana-ruby): hiragana de un run de katakana dentro de
 *  este segmento, calculada por [particionarPorKatakana]. NUNCA coexiste con
 *  [lectura] — la furigana de kanji manda; la partición de katakana solo actúa sobre
 *  segmentos que YA quedaron sin furigana (`lectura == null`) tras [segmentosDeGrupo]. */
data class Segmento(val texto: String, val lectura: String?, val token: PalabraToken, val lecturaKana: String? = null)

/** Una corrida MAXIMAL de tokens consecutivos que se renderiza como una sola unidad
 *  porque alguna furigana cruza el límite entre ellos (fix del bug "furigana
 *  duplicada", Plan 3.6.pulido: 二人 tokenizado 二+人 con una sola ふたり que cubre
 *  ambos tokens antes renderizaba "ふたりふたり", una vez por cada token overlapeado).
 *  Un token sin ninguna furigana cruzada en sus bordes queda en un grupo de tamaño 1
 *  (comportamiento sin cambios respecto de antes). El grupo entero es la unidad que
 *  [TextoConFurigana] usa para wrappear en `FlowRow` — nunca se parte entre líneas. */
data class GrupoRenderizado(val tokens: List<PalabraToken>) {
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
 *  desbordarlo.
 *
 *  Otra red de seguridad, esta vez contra furigana que se solapa CONSIGO MISMA (dato de
 *  catálogo mal alineado — visto en producción: `momotaro.json`, oración
 *  「おばあさん、今帰ったよ。」, trae `[7,8,"いま"]` para 今 y `[7,9,"かえ"]` para 帰, con
 *  el segundo span arrancando en 7 en vez de 8): el `desde` de cada tramo se recorta
 *  también al `cursor` (lo ya emitido por un span anterior), nunca solo al rango del
 *  grupo. Sin este clamp, un span que arranca ANTES de donde terminó el anterior volvía
 *  a incluir esos caracteres ya emitidos —el bug de "今今帰ったよ", un carácter
 *  duplicado— y si el span queda enteramente cubierto por el anterior (`hasta <= desde`)
 *  se descarta entero en vez de emitir un segmento vacío o de ancho negativo. */
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
        // clamp a `cursor`, no solo a `inicioGrupo`: un span solapado con el anterior
        // nunca puede volver a emitir texto que un span previo ya cubrió.
        val desde = maxOf(f.inicio, cursor)
        val hasta = minOf(f.fin, finGrupo)
        if (hasta <= desde) continue // span íntegramente cubierto por uno anterior (o fuera de rango)
        if (desde > cursor) segmentos.add(Segmento(subtramo(cursor, desde), null, tokenEn(cursor)))
        segmentos.add(Segmento(subtramo(desde, hasta), f.lectura, tokenEn(desde)))
        cursor = hasta
    }
    if (cursor < finGrupo) segmentos.add(Segmento(subtramo(cursor, finGrupo), null, tokenEn(cursor)))
    return segmentos
}
