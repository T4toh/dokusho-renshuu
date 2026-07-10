package com.tatoh.dokushorenshu.ui.lector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tatoh.dokushorenshu.dominio.ConsultaPalabra

@Composable
fun PalabraSheet(consulta: ConsultaPalabra, onVerKanji: (String) -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    // LazyColumn (no Column+verticalScroll): ModalBottomSheet ya trae su propio nested-scroll
    // connection y solo lo delega correctamente a un scrollable "real" (LazyColumn/LazyList).
    // Un Column con verticalScroll no participa de ese nested scroll, así que el drag movía
    // todo el sheet en vez de desplazar el contenido. Sin heightIn ni verticalScroll: si el
    // contenido es corto el sheet se envuelve a su alto natural; si es largo, el sheet crece
    // (hasta el máximo que le da M3) y recién ahí el LazyColumn empieza a scrollear su interior.
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        // contentPadding (no Modifier.padding en el LazyColumn) para que el padding se
        // aplique dentro del área scrolleable y no recorte el scroll en sí.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 40.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(consulta.termino, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(4.dp))
                // Copiar el término solo (sin lectura ni definiciones): útil cuando la palabra
                // no está en el diccionario o es katakana, para buscarla en otro lado.
                val colorIcono = MaterialTheme.colorScheme.onSurfaceVariant
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(consulta.termino)) },
                    modifier = Modifier.semantics { contentDescription = "Copy" }
                ) {
                    // Ícono de copiar dibujado a mano (dos rectángulos superpuestos): ContentCopy
                    // no está en material-icons-core y no vale la pena icons-extended por un glifo.
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val trazo = 1.5.dp.toPx()
                        val esquina = CornerRadius(2.dp.toPx())
                        val lado = size.width * 0.62f
                        // rectángulo trasero (arriba-izquierda)
                        drawRoundRect(
                            color = colorIcono,
                            topLeft = Offset(0f, 0f),
                            size = Size(lado, lado),
                            cornerRadius = esquina,
                            style = Stroke(width = trazo)
                        )
                        // rectángulo delantero (abajo-derecha)
                        drawRoundRect(
                            color = colorIcono,
                            topLeft = Offset(size.width - lado, size.height - lado),
                            size = Size(lado, lado),
                            cornerRadius = esquina,
                            style = Stroke(width = trazo)
                        )
                    }
                }
            }
            val lectura = consulta.definiciones.firstOrNull()?.lectura ?: consulta.lecturaFallback
            lectura?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        }

        if (consulta.sinDefinicion) {
            item {
                Text("No definition", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            val significados = consulta.definiciones.first().significados
            itemsIndexed(significados) { indice, significado ->
                Text("${indice + 1}. $significado",
                    modifier = Modifier.padding(top = if (indice == 0) 8.dp else 0.dp, bottom = 2.dp))
            }
        }

        if (consulta.ejemplos.isNotEmpty()) {
            item {
                Text("Examples", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            }
            items(consulta.ejemplos) { ejemplo ->
                Column(Modifier.padding(top = 8.dp)) {
                    Text(ejemplo.japones)
                    Text(ejemplo.ingles, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (consulta.kanjis.isNotEmpty()) {
            item {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (kanji in consulta.kanjis) {
                        AssistChip(onClick = { onVerKanji(kanji.kanji) }, label = { Text(kanji.kanji) })
                    }
                }
            }
        }
    }
}
