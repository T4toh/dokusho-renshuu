package com.tatoh.dokushorenshu.ui.lector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
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
            val lectura = if (furiganaActiva) lecturaDelToken(oracion, token) else null
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = lectura ?: " ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = token.superficie,
                    style = estiloBase,
                    modifier = if (token.esContenido && onTapPalabra != null) {
                        Modifier.clickable { onTapPalabra(token) }
                    } else Modifier,
                )
            }
        }
    }
}

/** Concatena las lecturas de furigana cuyo span [inicio, fin) se solapa con el token. */
internal fun lecturaDelToken(oracion: Oracion, token: PalabraToken): String? =
    oracion.furigana
        .filter { it.inicio < token.fin && it.fin > token.inicio }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("") { it.lectura }
