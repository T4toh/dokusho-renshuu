package com.tatoh.dokushorenshu.ui.comun

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.tatoh.dokushorenshu.datos.Oracion
import com.tatoh.dokushorenshu.datos.Parrafo
import com.tatoh.dokushorenshu.dominio.PalabraToken
import com.tatoh.dokushorenshu.dominio.Tokenizador
import com.tatoh.dokushorenshu.ui.lector.GrupoFurigana
import com.tatoh.dokushorenshu.ui.lector.TextoConFurigana
import com.tatoh.dokushorenshu.ui.lector.calcularGruposFurigana

data class OracionPlana(
    val parrafo: Int,
    val oracionEnParrafo: Int,
    val oracion: Oracion,
    val tokens: List<PalabraToken>,
    // Precomputado en cargar() (fix de performance, Plan 3.6 feedback de dispositivo):
    // antes TextoConFurigana calculaba esto (agruparTokens + segmentosDeGrupo) en cada
    // recomposición de item durante el scroll. Ver doc de [calcularGruposFurigana].
    val gruposFurigana: List<GrupoFurigana>,
)

/** Precomputa tokens y grupos de furigana por oración. Va acá y no en
 *  TextoConFurigana porque es caro: calcularlo en cada recomposición de item
 *  hacía tirones al scrollear (Plan 3.6, feedback de dispositivo).
 *  Llamar SIEMPRE desde un dispatcher de IO: Kuromoji bloquea. */
fun aplanar(parrafos: List<Parrafo>, tokenizador: Tokenizador): List<OracionPlana> =
    parrafos.flatMapIndexed { p, parrafo ->
        parrafo.oraciones.mapIndexed { o, oracion ->
            val tokens = tokenizador.tokenizar(oracion.texto)
            OracionPlana(p, o, oracion, tokens, calcularGruposFurigana(tokens, oracion.furigana))
        }
    }

/** Un item de oración de [ListaOracionesLibre], extraído a su propio composable (fix de
 *  performance, Plan 3.6 feedback de dispositivo): así Compose puede saltear su
 *  recomposición cuando cambia algo AJENO a esta oración (p.ej. se abre el sheet de
 *  palabra, o cualquier otro campo de [EstadoLector] no relacionado con el foco). Solo
 *  recibe [esActual] (un `Boolean` plano, ya comparado en el callsite de `itemsIndexed`)
 *  y los datos propios de la oración — nunca `EstadoLector` completo. */
@Composable
fun ItemOracion(
    esActual: Boolean,
    plana: OracionPlana,
    furiganaActiva: Boolean,
    katakanaActiva: Boolean,
    onTapPalabra: (PalabraToken) -> Unit,
    onLongPressPalabra: (PalabraToken) -> Unit,
    // rango de selección SOLO si pertenece a esta oración (ya filtrado en el
    // callsite de itemsIndexed, mismo criterio que esActual: nunca entra
    // EstadoLector completo — un cambio de selección solo recompone los items
    // cuyo param cambió).
    rangoSeleccion: IntRange?,
) {
    // Foco SOLO por alpha (animado), nunca por tamaño: todas las oraciones tienen la
    // misma altura de item siempre, así que cambiar el foco jamás reflowea la
    // LazyColumn — únicamente el scroll mueve cosas. El fundido de ~250ms hace que el
    // foco se deslice entre oraciones en vez de saltar.
    val alphaAnimada by animateFloatAsState(
        targetValue = if (esActual) 1f else 0.35f,
        animationSpec = tween(durationMillis = 250),
        label = "alphaOracion",
    )
    Box(Modifier.alpha(alphaAnimada).fillMaxWidth()) {
        TextoConFurigana(
            tokens = plana.tokens,
            gruposFurigana = plana.gruposFurigana,
            furiganaActiva = furiganaActiva,
            katakanaActiva = katakanaActiva,
            onTapPalabra = onTapPalabra,
            onLongPressPalabra = onLongPressPalabra,
            rangoSeleccion = rangoSeleccion,
        )
    }
}
