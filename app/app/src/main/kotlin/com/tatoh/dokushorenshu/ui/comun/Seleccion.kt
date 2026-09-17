package com.tatoh.dokushorenshu.ui.comun

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Selección de un rango de tokens dentro de UNA oración (backlog feedback de uso
 *  2026-07-13: buscar en el browser expresiones/frases que el diccionario no tiene).
 *  [inicio]/[fin] son offsets de chars sobre `oracion.texto` (fin EXCLUSIVO, mismo
 *  contrato que `PalabraToken`): el rango cubre tokens completos, y el texto
 *  seleccionado es el substring crudo — partículas intermedias incluidas, sin
 *  furigana.
 *
 *  Vive en ui/comun, con la [BarraSeleccion] que la muestra: el lector y la vista de
 *  recorte la usan igual, y tenerla en uno de los dos paquetes obligaba al otro a
 *  depender de su hermano — justo el acoplamiento que ui/comun vino a sacar. */
data class SeleccionTexto(val indiceOracion: Int, val inicio: Int, val fin: Int)

/** Barra contextual de selección: texto elegido + Search web / Copy / cancelar.
 *  Reemplaza a Previous/Next en el bottomBar mientras hay selección activa. */
@Composable
fun BarraSeleccion(
    texto: String,
    onBuscarWeb: () -> Unit,
    onCopiar: () -> Unit,
    onCancelar: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            texto,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCopiar) { Text("Copy") }
        Button(onClick = onBuscarWeb) { Text("Search web") }
        TextButton(onClick = onCancelar) { Text("✕") }
    }
}

/** Abre la búsqueda web del texto seleccionado en el BROWSER DEFAULT del usuario:
 *  ACTION_VIEW con la URL de búsqueda. Antes se intentaba ACTION_WEB_SEARCH
 *  primero, pero en MIUI (y otros OEM) lo captura la app de búsqueda
 *  (Google/Xiaomi) que abre su webview embebido en vez del browser elegido por
 *  el usuario — feedback de uso 2026-07-16. Si no hay browser (emulador
 *  pelado), no crashear: la selección queda para Copy. */
fun buscarEnWeb(contexto: Context, texto: String) {
    val url = "https://www.google.com/search?q=${Uri.encode(texto)}"
    runCatching { contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
