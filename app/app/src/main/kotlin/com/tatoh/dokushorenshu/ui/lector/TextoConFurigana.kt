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
 *  de la pre-alineada del JSON (spans solapados con el token), NUNCA de Kuromoji. */
@Composable
fun TextoConFurigana(
    oracion: Oracion,
    tokens: List<PalabraToken>,
    furiganaActiva: Boolean,
    grande: Boolean,
    onTapPalabra: ((PalabraToken) -> Unit)?,
) {
    val estiloBase = if (grande) MaterialTheme.typography.headlineMedium
    else MaterialTheme.typography.bodyLarge
    FlowRow {
        for (token in tokens) {
            val lectura = if (furiganaActiva) lecturaDelToken(oracion, token) else null
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = lectura ?: " ",
                    fontSize = if (grande) 12.sp else 9.sp,
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
